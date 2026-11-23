package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.math.min

class WsPool(
    private val configProvider: () -> ProxyConfig,
    private val isIdleHibernating: () -> Boolean = { false },
    private val isMeteredProvider: () -> Boolean = { false },
    private val isOfflineProvider: () -> Boolean = { false },
) {
    companion object {
        const val WS_POOL_MAX_AGE_MS = 120_000L
        const val WS_POOL_MAX_AGE_METERED_MS = 55_000L
        const val WS_POOL_CHECK_INTERVAL_MS = 5_000L
        const val ACTIVE_KEY_WINDOW_MS = 10 * 60_000L
        const val REFILL_BACKOFF_INITIAL_MS = 1_000L
        const val REFILL_BACKOFF_MAX_MS = 3_600_000L
    }

    data class PoolKey(val dc: Int, val isMedia: Boolean, val isTestDc: Boolean) {
        fun tag(): String = "DC$dc${if (isTestDc) "t" else ""}${if (isMedia) "m" else ""}"
    }

    private data class Entry(val ws: RawWebSocket, val createdMs: Long, val targetIp: String)

    private val lock = Any()
    private val idle = HashMap<PoolKey, ArrayDeque<Entry>>()
    private val refilling = HashMap<PoolKey, Job>()
    private val rotating = HashMap<PoolKey, Job>()
    private val refillFailures = HashMap<PoolKey, Int>()
    private val refillAfterMs = HashMap<PoolKey, Long>()
    private val activeKeys = ConcurrentHashMap<PoolKey, Long>()
    private val tryFrontingFirst = AtomicBoolean(false)

    private var scope: CoroutineScope? = null

    fun attachScope(parentScope: CoroutineScope) {
        scope = parentScope
    }

    private fun currentMaxAgeMs(): Long =
        if (isMeteredProvider()) WS_POOL_MAX_AGE_METERED_MS else WS_POOL_MAX_AGE_MS

    private fun targetSizeForKey(key: PoolKey, cfg: ProxyConfig, now: Long): Int {
        if (cfg.poolSize <= 0 || key.dc !in cfg.dcRedirects) return 0
        val lastActive = activeKeys[key]
        if (lastActive != null && now - lastActive <= ACTIVE_KEY_WINDOW_MS) {
            return cfg.poolSize
        }
        return if (!key.isMedia) 1 else 0
    }

    fun isDcInBackoff(dc: Int, isMedia: Boolean = false, isTestDc: Boolean = false): Boolean {
        val key = PoolKey(dc, isMedia, isTestDc)
        val now = ProxyClock.monotonicMs()
        return synchronized(lock) {
            (refillFailures[key] ?: 0) > 0 && now < (refillAfterMs[key] ?: 0L)
        }
    }

    fun get(dc: Int, isMedia: Boolean, isTestDc: Boolean = false): RawWebSocket? {
        val cfg = configProvider()
        if (cfg.disableSecure && cfg.fallbackCfproxy && !isTestDc) return null
        val targetIp = cfg.dcRedirects[dc] ?: return null
        val key = PoolKey(dc, isMedia, isTestDc)
        val now = ProxyClock.monotonicMs()
        activeKeys[key] = now
        if (cfg.poolSize <= 0) return null

        val domains = wsDomains(dc, isMedia)
        var hit: RawWebSocket? = null
        var hitAgeSec = 0.0
        var left = 0
        val staleList = ArrayList<RawWebSocket>()

        synchronized(lock) {
            val bucket = idle.getOrPut(key) { ArrayDeque() }
            while (bucket.isNotEmpty()) {
                val entry = bucket.removeFirst()
                val ageMs = now - entry.createdMs
                if (entry.targetIp != targetIp || isEntryStale(entry.ws, ageMs)) {
                    staleList.add(entry.ws)
                    continue
                }
                hit = entry.ws
                hitAgeSec = ageMs / 1000.0
                left = bucket.size
                break
            }
        }

        for (s in staleList) {
            s.closeQuietly()
        }

        if (hit != null) {
            ProxyStats.poolHits.incrementAndGet()
            ProxyLogger.d("WS pool hit ${key.tag()} (age=${"%.1f".format(hitAgeSec)}s, left=$left)")
            scheduleRefill(key, domains)
            return hit
        }

        ProxyStats.poolMisses.incrementAndGet()
        scheduleRefill(key, domains)
        return null
    }

    suspend fun getOrConnect(dc: Int, isMedia: Boolean, isTestDc: Boolean = false): RawWebSocket? {
        val pooled = get(dc, isMedia, isTestDc)
        if (pooled != null) return pooled

        val cfg = configProvider()
        if (cfg.disableSecure && cfg.fallbackCfproxy && !isTestDc) return null
        val targetIp = cfg.dcRedirects[dc] ?: return null
        if (isOfflineProvider()) return null

        val key = PoolKey(dc, isMedia, isTestDc)
        val now = ProxyClock.monotonicMs()
        val inBackoff = synchronized(lock) { now < (refillAfterMs[key] ?: 0L) }
        if (inBackoff) return null

        // Keep on-demand probe fast (primary domain + single preferred SNI) when poolSize == 0.
        val domains = wsDomains(dc, isMedia).take(1)
        val path = if (isTestDc) WS_PATH_TEST else WS_PATH
        val ws = connectOne(targetIp, domains, path, cfg, connectTimeoutMs = 2_500, singleSniOnly = true)
        if (ws != null) {
            synchronized(lock) {
                refillFailures.remove(key)
                refillAfterMs.remove(key)
            }
            return ws
        }
        synchronized(lock) {
            val failures = (refillFailures[key] ?: 0) + 1
            refillFailures[key] = failures
            val exp = min(failures - 1, 12)
            val delayMs = min(REFILL_BACKOFF_INITIAL_MS shl exp, REFILL_BACKOFF_MAX_MS)
            refillAfterMs[key] = ProxyClock.monotonicMs() + delayMs
        }
        return null
    }

    private fun isEntryStale(ws: RawWebSocket, ageMs: Long): Boolean {
        return ageMs >= currentMaxAgeMs() || ws.isStale()
    }

    private fun scheduleRefill(key: PoolKey, domains: List<String>) {
        val cfg = configProvider()
        if (cfg.poolSize <= 0 || key.dc !in cfg.dcRedirects || isOfflineProvider()) return
        if (cfg.disableSecure && cfg.fallbackCfproxy && !key.isTestDc) return
        val activeScope = scope ?: return
        scheduleRotation(key, domains)

        val now = ProxyClock.monotonicMs()
        val launchedJob = synchronized(lock) {
            if (refilling[key]?.isActive == true) return
            if (now < (refillAfterMs[key] ?: 0L)) return
            lateinit var job: Job
            job = activeScope.launch(start = CoroutineStart.LAZY) {
                refill(key, domains, job)
            }
            refilling[key] = job
            job
        }
        launchedJob.start()
    }

    private suspend fun refill(
        key: PoolKey,
        domains: List<String>,
        selfJob: Job,
    ) {
        val cfg = configProvider()
        val targetIp = cfg.dcRedirects[key.dc]
        val now = ProxyClock.monotonicMs()
        val desired = targetSizeForKey(key, cfg, now)
        val needed = synchronized(lock) {
            val bucket = idle.getOrPut(key) { ArrayDeque() }
            if (targetIp == null || desired <= 0) 0 else desired - bucket.size
        }
        if (needed <= 0 || targetIp == null || isOfflineProvider()) {
            synchronized(lock) {
                if (refilling[key] === selfJob) refilling.remove(key)
            }
            return
        }

        val path = if (key.isTestDc) WS_PATH_TEST else WS_PATH
        var connected = 0
        try {
            coroutineScope {
                val jobs = (0 until needed).map {
                    launch {
                        val ws = connectOne(targetIp, domains, path, cfg)
                        if (ws != null) {
                            var adopted = false
                            synchronized(lock) {
                                if (refilling[key] === selfJob && isActive) {
                                    val bucket = idle.getOrPut(key) { ArrayDeque() }
                                    bucket.addLast(Entry(ws, ProxyClock.monotonicMs(), targetIp))
                                    adopted = true
                                    connected++
                                }
                            }
                            if (!adopted) {
                                ws.closeQuietly()
                            }
                        }
                    }
                }
                jobs.forEach { it.join() }
            }

            synchronized(lock) {
                if (connected > 0) {
                    refillFailures.remove(key)
                    refillAfterMs.remove(key)
                } else if (!isOfflineProvider()) {
                    val failures = (refillFailures[key] ?: 0) + 1
                    refillFailures[key] = failures
                    val exp = min(failures - 1, 12)
                    val delayMs = min(REFILL_BACKOFF_INITIAL_MS shl exp, REFILL_BACKOFF_MAX_MS)
                    refillAfterMs[key] = ProxyClock.monotonicMs() + delayMs
                    ProxyLogger.i("WS pool refill failed for ${key.tag()}, retry in ${delayMs / 1000}s")
                }
                val readyCount = idle[key]?.size ?: 0
                ProxyLogger.d("WS pool refilled ${key.tag()}: $readyCount ready")
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            synchronized(lock) {
                if (refilling[key] === selfJob) {
                    refilling.remove(key)
                }
            }
        }
    }

    private fun scheduleRotation(key: PoolKey, domains: List<String>) {
        val activeScope = scope ?: return
        val launchedJob = synchronized(lock) {
            if (rotating[key]?.isActive == true) return
            lateinit var job: Job
            job = activeScope.launch(start = CoroutineStart.LAZY) {
                rotateLoop(key, domains, job)
            }
            rotating[key] = job
            job
        }
        launchedJob.start()
    }

    private suspend fun rotateLoop(
        key: PoolKey,
        domains: List<String>,
        selfJob: Job,
    ) {
        try {
            while (true) {
                val cfg = configProvider()
                val targetIp = cfg.dcRedirects[key.dc]
                if (cfg.poolSize <= 0 || targetIp == null) break

                val now = ProxyClock.monotonicMs()
                val maxAge = currentMaxAgeMs()
                val expired = ArrayList<RawWebSocket>()
                var readyCount = 0
                var oldestExpiry = Long.MAX_VALUE

                synchronized(lock) {
                    val bucket = idle.getOrPut(key) { ArrayDeque() }
                    val kept = ArrayDeque<Entry>(bucket.size)
                    while (bucket.isNotEmpty()) {
                        val entry = bucket.removeFirst()
                        if (entry.targetIp != targetIp || now - entry.createdMs >= maxAge || entry.ws.isStale()) {
                            expired.add(entry.ws)
                        } else {
                            kept.addLast(entry)
                            oldestExpiry = min(oldestExpiry, entry.createdMs + maxAge)
                        }
                    }
                    bucket.addAll(kept)
                    readyCount = bucket.size
                }

                for (ws in expired) {
                    ws.closeQuietly()
                }
                if (expired.isNotEmpty()) {
                    ProxyLogger.d("WS pool rotated ${key.tag()}: ${expired.size} stale, $readyCount ready")
                }

                val desired = targetSizeForKey(key, cfg, now)
                if (!isIdleHibernating() && !isOfflineProvider() && readyCount < desired) {
                    scheduleRefill(key, domains)
                }

                val sleepy = (isIdleHibernating() || isOfflineProvider() || desired == 0) && readyCount == 0
                val checkInterval = if (sleepy) 30_000L else WS_POOL_CHECK_INTERVAL_MS
                var wakeAt = now + checkInterval
                if (oldestExpiry < wakeAt) {
                    wakeAt = oldestExpiry
                }
                synchronized(lock) {
                    val after = refillAfterMs[key]
                    if (refilling[key]?.isActive != true && after != null && after > now) {
                        wakeAt = min(wakeAt, after)
                    }
                }
                val sleepMs = (wakeAt - ProxyClock.monotonicMs()).coerceAtLeast(100L)
                delay(sleepMs)
            }
        } finally {
            synchronized(lock) {
                if (rotating[key] === selfJob) {
                    rotating.remove(key)
                }
            }
        }
    }

    private suspend fun connectOne(
        targetIp: String,
        domains: List<String>,
        path: String,
        cfg: ProxyConfig,
        connectTimeoutMs: Int = 7_000,
        singleSniOnly: Boolean = false,
    ): RawWebSocket? {
        val frontingSnis = cfg.frontingSniList
        val fullPlan: List<String?> = if (tryFrontingFirst.get()) {
            frontingSnis + listOf(null)
        } else {
            listOf<String?>(null) + frontingSnis
        }
        val sniPlan = if (singleSniOnly) fullPlan.take(1) else fullPlan

        for (domain in domains) {
            for (sni in sniPlan) {
                val fronted = sni != null
                try {
                    val ws = RawWebSocket.connect(
                        host = targetIp,
                        domain = domain,
                        timeoutMs = if (fronted) min(connectTimeoutMs, 6_000) else connectTimeoutMs,
                        path = path,
                        sni = sni,
                        secure = true,
                        bufferSize = cfg.bufferSize,
                        dohProvider = cfg.dohProvider,
                        ipPreference = cfg.ipPreference,
                    )
                    tryFrontingFirst.set(fronted)
                    if (fronted) {
                        ProxyStats.connectionsFronting.incrementAndGet()
                    }
                    ProxyLogger.d("WS pool connected $domain$path via $targetIp (fronting=$fronted)")
                    return ws
                } catch (e: CancellationException) {
                    throw e
                } catch (exc: TcpConnectFailedException) {
                    // Fail fast: TCP to targetIp:443 is dead, so retrying SNI fronting on the same IP is pointless.
                    ProxyStats.wsErrors.incrementAndGet()
                    ProxyLogger.d("WS pool TCP failed for $targetIp: ${exc.message}")
                    return null
                } catch (exc: Exception) {
                    ProxyStats.wsErrors.incrementAndGet()
                    ProxyLogger.d("WS pool connect $domain$path via $targetIp (fronting=$fronted): ${exc.javaClass.simpleName}: ${exc.message}")
                }
            }
        }
        return null
    }

    fun warmup() {
        val cfg = configProvider()
        if (cfg.poolSize <= 0 || cfg.dcRedirects.isEmpty() || isOfflineProvider()) return
        if (cfg.disableSecure && cfg.fallbackCfproxy && !cfg.forceTestDc) return
        val now = ProxyClock.monotonicMs()
        for ((dc, _) in cfg.dcRedirects) {
            val mainKey = PoolKey(dc, false, cfg.forceTestDc)
            scheduleRefill(mainKey, wsDomains(dc, false))
            val mediaKey = PoolKey(dc, true, cfg.forceTestDc)
            if (targetSizeForKey(mediaKey, cfg, now) > 0) {
                scheduleRefill(mediaKey, wsDomains(dc, true))
            }
        }
        ProxyLogger.i("WS pool warmup started for ${cfg.dcRedirects.size} DC(s)")
    }

    fun onConfigUpdated() {
        val cfg = configProvider()
        val skipDirect = cfg.disableSecure && cfg.fallbackCfproxy && !cfg.forceTestDc
        val toCancel = ArrayList<Job>()
        val toClose = ArrayList<RawWebSocket>()
        synchronized(lock) {
            toCancel.addAll(refilling.values)
            refilling.clear()
            val it = idle.entries.iterator()
            while (it.hasNext()) {
                val (key, bucket) = it.next()
                val expectedIp = cfg.dcRedirects[key.dc]
                if (skipDirect || cfg.poolSize <= 0 || expectedIp == null) {
                    bucket.forEach { e -> toClose.add(e.ws) }
                    it.remove()
                } else {
                    val kept = ArrayDeque<Entry>(bucket.size)
                    while (bucket.isNotEmpty()) {
                        val entry = bucket.removeFirst()
                        if (entry.targetIp != expectedIp) {
                            toClose.add(entry.ws)
                        } else {
                            kept.addLast(entry)
                        }
                    }
                    bucket.addAll(kept)
                }
            }
            refillFailures.clear()
            refillAfterMs.clear()
        }
        toCancel.forEach { it.cancel() }
        toClose.forEach { it.closeQuietly() }
        if (!skipDirect && !isIdleHibernating() && !isOfflineProvider()) {
            warmup()
        }
    }

    fun onNetworkChanged() {
        val toCancel = ArrayList<Job>()
        val toClose = ArrayList<RawWebSocket>()
        synchronized(lock) {
            toCancel.addAll(refilling.values)
            refilling.clear()
            for (bucket in idle.values) {
                for (entry in bucket) {
                    toClose.add(entry.ws)
                }
            }
            idle.clear()
            refillFailures.clear()
            refillAfterMs.clear()
        }
        toCancel.forEach { it.cancel() }
        toClose.forEach { it.closeQuietly() }
        if (!isIdleHibernating() && !isOfflineProvider()) {
            warmup()
        }
    }

    fun reset() {
        val toCancel = ArrayList<Job>()
        val toClose = ArrayList<RawWebSocket>()
        synchronized(lock) {
            toCancel.addAll(rotating.values)
            toCancel.addAll(refilling.values)
            rotating.clear()
            refilling.clear()
            for (bucket in idle.values) {
                for (entry in bucket) {
                    toClose.add(entry.ws)
                }
            }
            idle.clear()
            refillFailures.clear()
            refillAfterMs.clear()
        }
        activeKeys.clear()
        tryFrontingFirst.set(false)
        toCancel.forEach { it.cancel() }
        toClose.forEach { it.closeQuietly() }
    }
}

data class PooledCfSocket(
    val ws: RawWebSocket,
    val baseDomain: String,
    val usedPort80: Boolean,
)

class CfProxyWsPool(
    private val configProvider: () -> ProxyConfig,
    private val balancer: DomainBalancer,
    private val isIdleHibernating: () -> Boolean = { false },
    private val isMeteredProvider: () -> Boolean = { false },
    private val isOfflineProvider: () -> Boolean = { false },
) {
    companion object {
        const val MAX_AGE_WIFI_MS = 95_000L
        const val MAX_AGE_METERED_MS = 50_000L
        const val CHECK_INTERVAL_MS = 5_000L
        const val PER_DC_LIMIT = 2
        const val ACTIVE_DC_WINDOW_MS = 10 * 60_000L
        const val REFILL_BACKOFF_INITIAL_MS = 2_000L
        const val REFILL_BACKOFF_MAX_MS = 300_000L
    }

    private data class Entry(
        val ws: RawWebSocket,
        val createdMs: Long,
        val baseDomain: String,
        val usedPort80: Boolean,
        val cleanIp: String,
    )

    private val lock = Any()
    private val idle = HashMap<Int, ArrayDeque<Entry>>()
    private val refilling = HashMap<Int, Job>()
    private val rotating = HashMap<Int, Job>()
    private val refillFailures = HashMap<Int, Int>()
    private val refillAfterMs = HashMap<Int, Long>()
    private val activeDcs = ConcurrentHashMap<Int, Long>()

    private var scope: CoroutineScope? = null

    fun attachScope(parentScope: CoroutineScope) {
        scope = parentScope
    }

    private fun currentMaxAgeMs(): Long =
        if (isMeteredProvider()) MAX_AGE_METERED_MS else MAX_AGE_WIFI_MS

    fun get(dc: Int): PooledCfSocket? {
        val cfg = configProvider()
        if (!cfg.fallbackCfproxy || cfg.forceTestDc || cfg.poolSize <= 0) return null

        val now = ProxyClock.monotonicMs()
        activeDcs[dc] = now
        val maxAge = currentMaxAgeMs()
        val expectedCleanIp = cfg.effectiveCfCleanIp
        val tlsBlocked = cfg.autoPort80Fallback && balancer.isCfTlsBlocked(now)
        val staleList = ArrayList<RawWebSocket>()
        var hit: PooledCfSocket? = null
        var hitAgeSec = 0.0
        var left = 0

        synchronized(lock) {
            val bucket = idle.getOrPut(dc) { ArrayDeque() }
            while (bucket.isNotEmpty()) {
                val entry = bucket.removeFirst()
                val ageMs = now - entry.createdMs
                val modeMismatch = (cfg.disableSecure && !entry.usedPort80) ||
                    (!cfg.disableSecure && !cfg.autoPort80Fallback && entry.usedPort80) ||
                    (!cfg.disableSecure && tlsBlocked && !entry.usedPort80) ||
                    entry.cleanIp != expectedCleanIp
                if (ageMs >= maxAge || entry.ws.isStale() || modeMismatch) {
                    staleList.add(entry.ws)
                    continue
                }
                hit = PooledCfSocket(entry.ws, entry.baseDomain, entry.usedPort80)
                hitAgeSec = ageMs / 1000.0
                left = bucket.size
                break
            }
        }

        staleList.forEach { it.closeQuietly() }

        if (hit != null) {
            ProxyStats.cfPoolHits.incrementAndGet()
            ProxyLogger.d("CF proxy pool hit DC$dc (age=${"%.1f".format(hitAgeSec)}s, left=$left)")
            scheduleRefill(dc)
            return hit
        }

        ProxyStats.cfPoolMisses.incrementAndGet()
        scheduleRefill(dc)
        return null
    }

    fun warmup(dcs: List<Int> = listOf(2, 4)) {
        val cfg = configProvider()
        if (!cfg.fallbackCfproxy || cfg.forceTestDc || cfg.poolSize <= 0 || isOfflineProvider()) return
        for (dc in dcs) {
            scheduleRefill(dc)
        }
        ProxyLogger.d("CF proxy pool warmup started for DCs: ${dcs.joinToString()}")
    }

    private fun scheduleRefill(dc: Int) {
        val cfg = configProvider()
        if (!cfg.fallbackCfproxy || cfg.forceTestDc || cfg.poolSize <= 0 || isOfflineProvider()) return
        val activeScope = scope ?: return
        scheduleRotation(dc)

        val now = ProxyClock.monotonicMs()
        val launchedJob = synchronized(lock) {
            if (refilling[dc]?.isActive == true) return
            if (now < (refillAfterMs[dc] ?: 0L)) return
            lateinit var job: Job
            job = activeScope.launch(start = CoroutineStart.LAZY) {
                refill(dc, job)
            }
            refilling[dc] = job
            job
        }
        launchedJob.start()
    }

    private suspend fun refill(dc: Int, selfJob: Job) {
        try {
            val cfg = configProvider()
            if (!cfg.fallbackCfproxy || cfg.forceTestDc || isOfflineProvider()) return
            val lastActive = activeDcs[dc]
            val recentlyActive = lastActive != null && (ProxyClock.monotonicMs() - lastActive) <= ACTIVE_DC_WINDOW_MS
            val targetSize = if (recentlyActive) min(cfg.poolSize, PER_DC_LIMIT) else 1
            val needed = synchronized(lock) {
                targetSize - idle.getOrPut(dc) { ArrayDeque() }.size
            }
            if (needed <= 0) return

            var connected = 0
            for (i in 0 until needed) {
                val res = connectOne(dc, cfg) ?: break
                var adopted = false
                synchronized(lock) {
                    if (refilling[dc] === selfJob) {
                        idle.getOrPut(dc) { ArrayDeque() }.addLast(res)
                        connected++
                        adopted = true
                    }
                }
                if (!adopted) {
                    res.ws.closeQuietly()
                    return
                }
            }

            synchronized(lock) {
                if (connected > 0) {
                    refillFailures.remove(dc)
                    refillAfterMs.remove(dc)
                } else if (!isOfflineProvider()) {
                    val failures = (refillFailures[dc] ?: 0) + 1
                    refillFailures[dc] = failures
                    val exp = min(failures - 1, 8)
                    val delayMs = min(REFILL_BACKOFF_INITIAL_MS shl exp, REFILL_BACKOFF_MAX_MS)
                    refillAfterMs[dc] = ProxyClock.monotonicMs() + delayMs
                    ProxyLogger.d("CF proxy pool refill failed for DC$dc, retry in ${delayMs / 1000}s")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            synchronized(lock) {
                if (refilling[dc] === selfJob) refilling.remove(dc)
            }
        }
    }

    private fun scheduleRotation(dc: Int) {
        val activeScope = scope ?: return
        val launchedJob = synchronized(lock) {
            if (rotating[dc]?.isActive == true) return
            lateinit var job: Job
            job = activeScope.launch(start = CoroutineStart.LAZY) {
                rotateLoop(dc, job)
            }
            rotating[dc] = job
            job
        }
        launchedJob.start()
    }

    private suspend fun rotateLoop(dc: Int, selfJob: Job) {
        try {
            while (true) {
                val cfg = configProvider()
                if (!cfg.fallbackCfproxy || cfg.forceTestDc || cfg.poolSize <= 0) break

                val now = ProxyClock.monotonicMs()
                val maxAge = currentMaxAgeMs()
                val expired = ArrayList<RawWebSocket>()
                var readyCount = 0
                var oldestExpiry = Long.MAX_VALUE

                synchronized(lock) {
                    val bucket = idle.getOrPut(dc) { ArrayDeque() }
                    val kept = ArrayDeque<Entry>(bucket.size)
                    while (bucket.isNotEmpty()) {
                        val entry = bucket.removeFirst()
                        if (now - entry.createdMs >= maxAge || entry.ws.isStale()) {
                            expired.add(entry.ws)
                        } else {
                            kept.addLast(entry)
                            oldestExpiry = min(oldestExpiry, entry.createdMs + maxAge)
                        }
                    }
                    bucket.addAll(kept)
                    readyCount = bucket.size
                }

                expired.forEach { it.closeQuietly() }

                val lastActive = activeDcs[dc]
                val recentlyActive = lastActive != null && (now - lastActive) <= ACTIVE_DC_WINDOW_MS
                val limit = if (recentlyActive) min(cfg.poolSize, PER_DC_LIMIT) else 0
                if (!isIdleHibernating() && !isOfflineProvider() && readyCount < limit) {
                    scheduleRefill(dc)
                }

                if (!recentlyActive && readyCount == 0) break

                val sleepy = (isIdleHibernating() || isOfflineProvider()) && readyCount == 0
                val checkInterval = if (sleepy) 30_000L else CHECK_INTERVAL_MS
                var wakeAt = now + checkInterval
                if (oldestExpiry < wakeAt) wakeAt = oldestExpiry
                synchronized(lock) {
                    val after = refillAfterMs[dc]
                    if (refilling[dc]?.isActive != true && after != null && after > now) {
                        wakeAt = min(wakeAt, after)
                    }
                }
                delay((wakeAt - ProxyClock.monotonicMs()).coerceAtLeast(100L))
            }
        } finally {
            synchronized(lock) {
                if (rotating[dc] === selfJob) rotating.remove(dc)
            }
        }
    }

    private suspend fun connectOne(dc: Int, cfg: ProxyConfig): Entry? {
        val candidates = balancer.getDomainsForDc(dc).take(2)
        val cleanIp = cfg.effectiveCfCleanIp
        for (baseDomain in candidates) {
            val winner = ProxyBridge.connectSingleCfDomain(
                dc = dc,
                baseDomain = baseDomain,
                config = cfg,
                balancer = balancer,
                label = "cf-pool",
                mediaTag = "",
            )
            if (winner != null) {
                balancer.reportSuccess(dc, winner.baseDomain, winner.rttMs, usedPort80 = winner.usedPort80)
                if (winner.usedPort80 && !cfg.disableSecure && ProxyStats.activeRoute.get() == ActiveRoute.NONE) {
                    ProxyStats.setActiveRoute(ActiveRoute.CF_HTTP80_AUTO)
                }
                return Entry(
                    ws = winner.ws,
                    createdMs = ProxyClock.monotonicMs(),
                    baseDomain = winner.baseDomain,
                    usedPort80 = winner.usedPort80,
                    cleanIp = cleanIp,
                )
            }
        }
        return null
    }

    fun onConfigUpdated() {
        val toCancel = ArrayList<Job>()
        val toClose = ArrayList<RawWebSocket>()
        synchronized(lock) {
            toCancel.addAll(refilling.values)
            refilling.clear()
            for (bucket in idle.values) {
                bucket.forEach { toClose.add(it.ws) }
            }
            idle.clear()
            refillFailures.clear()
            refillAfterMs.clear()
        }
        toCancel.forEach { it.cancel() }
        toClose.forEach { it.closeQuietly() }
        if (!isIdleHibernating() && !isOfflineProvider()) {
            val now = ProxyClock.monotonicMs()
            val dcs = activeDcs.entries
                .filter { now - it.value <= ACTIVE_DC_WINDOW_MS }
                .map { it.key }
                .ifEmpty { listOf(2, 4) }
            warmup(dcs)
        }
    }

    fun onNetworkChanged() {
        val toCancel = ArrayList<Job>()
        val toClose = ArrayList<RawWebSocket>()
        synchronized(lock) {
            toCancel.addAll(refilling.values)
            refilling.clear()
            for (bucket in idle.values) {
                bucket.forEach { toClose.add(it.ws) }
            }
            idle.clear()
            refillFailures.clear()
            refillAfterMs.clear()
        }
        toCancel.forEach { it.cancel() }
        toClose.forEach { it.closeQuietly() }
        if (!isIdleHibernating() && !isOfflineProvider()) {
            val now = ProxyClock.monotonicMs()
            val dcs = activeDcs.entries
                .filter { now - it.value <= ACTIVE_DC_WINDOW_MS }
                .map { it.key }
                .ifEmpty { listOf(2, 4) }
            warmup(dcs)
        }
    }

    fun reset() {
        val toCancel = ArrayList<Job>()
        val toClose = ArrayList<RawWebSocket>()
        synchronized(lock) {
            toCancel.addAll(rotating.values)
            toCancel.addAll(refilling.values)
            rotating.clear()
            refilling.clear()
            for (bucket in idle.values) {
                bucket.forEach { toClose.add(it.ws) }
            }
            idle.clear()
            refillFailures.clear()
            refillAfterMs.clear()
        }
        activeDcs.clear()
        toCancel.forEach { it.cancel() }
        toClose.forEach { it.closeQuietly() }
    }
}

data class PooledWorkerSocket(
    val ws: RawWebSocket,
    val workerDomain: String,
    val usedPort80: Boolean,
)

class CfWorkerPool(
    private val configProvider: () -> ProxyConfig,
    private val balancer: DomainBalancer? = null,
    private val isIdleHibernating: () -> Boolean = { false },
    private val isMeteredProvider: () -> Boolean = { false },
    private val isOfflineProvider: () -> Boolean = { false },
    internal val connector: suspend (
        workerDomain: String,
        path: String,
        secure: Boolean,
        timeoutMs: Int,
        cfg: ProxyConfig,
    ) -> RawWebSocket = { workerDomain, path, secure, timeoutMs, cfg ->
        RawWebSocket.connect(
            host = workerDomain,
            domain = workerDomain,
            timeoutMs = timeoutMs,
            path = path,
            secure = secure,
            bufferSize = cfg.bufferSize,
            dohProvider = cfg.dohProvider,
            ipPreference = cfg.ipPreference,
            cleanIpOverride = cfg.effectiveCfCleanIp,
        )
    },
) {
    companion object {
        const val WS_POOL_MAX_AGE_MS = 100_000L
        const val WS_POOL_MAX_AGE_METERED_MS = 50_000L
        const val WS_POOL_CHECK_INTERVAL_MS = 5_000L
        const val PER_DC_LIMIT = 1
        const val REFILL_BACKOFF_INITIAL_MS = 1_000L
        const val REFILL_BACKOFF_MAX_MS = 3_600_000L
        const val FAILURE_COOLDOWN_MS = 60_000L
        private const val DAY_MS = 86_400_000L

        internal fun isTimeoutException(t: Throwable?): Boolean {
            var cur = t
            var depth = 0
            while (cur != null && depth < 6) {
                if (cur is SocketTimeoutException) return true
                cur = cur.cause
                depth++
            }
            return false
        }

        internal fun shouldFallbackToAlternatePort(exc: Throwable, attemptedSecure: Boolean): Boolean {
            if (exc is WsHandshakeError) return false
            if (isTimeoutException(exc)) return true
            if (attemptedSecure && (exc is SSLException || exc is EOFException)) return true
            return false
        }
    }

    private data class Entry(
        val ws: RawWebSocket,
        val createdMs: Long,
        val workerDomain: String,
        val usedPort80: Boolean,
        val cleanIp: String,
    )

    private val lock = Any()
    private val random = SecureRandom()
    private val idle = HashMap<Int, ArrayDeque<Entry>>()
    private val refilling = HashMap<Int, Job>()
    private val rotating = HashMap<Int, Job>()
    private val refillFailures = HashMap<Int, Int>()
    private val refillAfterMs = HashMap<Int, Long>()
    private val exhaustedUntilMs = ConcurrentHashMap<String, Long>()
    private val trySecureFirst = AtomicBoolean(true)

    private var scope: CoroutineScope? = null

    fun attachScope(parentScope: CoroutineScope) {
        scope = parentScope
    }

    internal fun isTryingSecureFirst(): Boolean = trySecureFirst.get()

    internal fun nextRefillAfterMs(dc: Int): Long? = synchronized(lock) { refillAfterMs[dc] }

    private fun currentMaxAgeMs(): Long =
        if (isMeteredProvider()) WS_POOL_MAX_AGE_METERED_MS else WS_POOL_MAX_AGE_MS

    fun get(dc: Int, fallbackDst: String, workerDomains: List<String>): PooledWorkerSocket? {
        val cfg = configProvider()
        if (cfg.poolSize <= 0 || workerDomains.isEmpty()) return null

        val now = ProxyClock.monotonicMs()
        val maxAge = currentMaxAgeMs()
        val expectedCleanIp = cfg.effectiveCfCleanIp
        val staleList = ArrayList<RawWebSocket>()
        var hit: PooledWorkerSocket? = null
        var hitAgeSec = 0.0
        var left = 0

        synchronized(lock) {
            val bucket = idle.getOrPut(dc) { ArrayDeque() }
            while (bucket.isNotEmpty()) {
                val entry = bucket.removeFirst()
                val ageMs = now - entry.createdMs
                val modeMismatch = (cfg.disableSecure && !entry.usedPort80) ||
                    (!cfg.disableSecure && !cfg.autoPort80Fallback && entry.usedPort80) ||
                    entry.cleanIp != expectedCleanIp ||
                    entry.workerDomain !in workerDomains
                if (ageMs >= maxAge || entry.ws.isStale() || modeMismatch) {
                    staleList.add(entry.ws)
                    continue
                }
                hit = PooledWorkerSocket(entry.ws, entry.workerDomain, entry.usedPort80)
                hitAgeSec = ageMs / 1000.0
                left = bucket.size
                break
            }
        }

        staleList.forEach { it.closeQuietly() }

        if (hit != null) {
            ProxyStats.cfPoolHits.incrementAndGet()
            ProxyLogger.d("CF worker pool hit DC$dc via ${hit!!.workerDomain} (age=${"%.1f".format(hitAgeSec)}s, left=$left)")
            scheduleRefill(dc, fallbackDst, workerDomains)
            return hit
        }

        ProxyStats.cfPoolMisses.incrementAndGet()
        scheduleRefill(dc, fallbackDst, workerDomains)
        return null
    }

    fun availableDomains(
        workerDomains: List<String>,
        nowMs: Long = ProxyClock.monotonicMs(),
    ): List<String> {
        val list = ArrayList<String>(workerDomains.size)
        val seen = HashSet<String>(workerDomains.size)
        for (domain in workerDomains) {
            if (!seen.add(domain)) continue
            val until = exhaustedUntilMs[domain] ?: 0L
            if (until > nowMs) continue
            if (until > 0L) exhaustedUntilMs.remove(domain)
            list.add(domain)
        }
        if (list.size > 1) {
            list.shuffle(random)
        }
        return list
    }

    fun reportSuccess(workerDomain: String) {
        exhaustedUntilMs.remove(workerDomain)
    }

    fun reportFailure(
        workerDomain: String,
        exc: Throwable,
        nowMs: Long = ProxyClock.monotonicMs(),
        wallClockMs: Long = ProxyClock.wallClockMs(),
    ) {
        val existing = exhaustedUntilMs[workerDomain] ?: 0L
        if (existing > nowMs) return

        val cooldownMs: Long
        val reason: String
        when {
            exc is WsHandshakeError -> {
                when {
                    exc.statusCode == 429 -> {
                        // Cloudflare Workers free plan resets daily request quota at 00:00 UTC.
                        val mod = ((wallClockMs % DAY_MS) + DAY_MS) % DAY_MS
                        cooldownMs = (DAY_MS - mod).coerceAtLeast(1_000L)
                        reason = "daily request limit"
                    }
                    exc.statusCode >= 500 -> {
                        cooldownMs = FAILURE_COOLDOWN_MS
                        reason = "HTTP ${exc.statusCode}"
                    }
                    else -> {
                        // A 4xx challenge or misconfigured route is not a reason to disable the whole domain.
                        return
                    }
                }
            }
            exc is IOException -> {
                cooldownMs = FAILURE_COOLDOWN_MS
                reason = "${exc.javaClass.simpleName}: ${exc.message}"
            }
            else -> return
        }

        exhaustedUntilMs[workerDomain] = nowMs + cooldownMs
        ProxyLogger.w(
            "CF worker $workerDomain disabled for ${(cooldownMs / 1000L).coerceAtLeast(1L)}s: $reason"
        )
    }

    // Must be called while holding `lock`.
    internal fun deferIfAllWorkersPausedLocked(
        dc: Int,
        workerDomains: List<String>,
        nowMs: Long,
    ): Boolean {
        if (workerDomains.isEmpty()) return false
        var firstAvailable = Long.MAX_VALUE
        for (domain in workerDomains) {
            val until = exhaustedUntilMs[domain] ?: nowMs
            if (until < firstAvailable) firstAvailable = until
        }
        if (firstAvailable <= nowMs) return false
        refillAfterMs[dc] = firstAvailable
        return true
    }

    internal fun deferIfAllWorkersPaused(
        dc: Int,
        workerDomains: List<String>,
        nowMs: Long = ProxyClock.monotonicMs(),
    ): Boolean = synchronized(lock) {
        deferIfAllWorkersPausedLocked(dc, workerDomains, nowMs)
    }

    private fun scheduleRefill(dc: Int, fallbackDst: String, workerDomains: List<String>) {
        val cfg = configProvider()
        if (cfg.poolSize <= 0 || workerDomains.isEmpty() || isOfflineProvider()) return
        val activeScope = scope ?: return
        scheduleRotation(dc, fallbackDst, workerDomains)

        val now = ProxyClock.monotonicMs()
        val launchedJob = synchronized(lock) {
            if (refilling[dc]?.isActive == true ||
                deferIfAllWorkersPausedLocked(dc, workerDomains, now) ||
                now < (refillAfterMs[dc] ?: 0L)
            ) {
                return
            }
            lateinit var job: Job
            job = activeScope.launch(start = CoroutineStart.LAZY) {
                refill(dc, fallbackDst, workerDomains.toList(), job)
            }
            refilling[dc] = job
            job
        }
        launchedJob.start()
    }

    private suspend fun refill(
        dc: Int,
        fallbackDst: String,
        workerDomains: List<String>,
        selfJob: Job,
    ) {
        try {
            val cfg = configProvider()
            val activeWorkers = cfg.cfproxyWorkerDomains.ifEmpty { workerDomains }
            if (isOfflineProvider() || activeWorkers.isEmpty()) return
            val targetSize = min(cfg.poolSize, PER_DC_LIMIT)
            val needed = synchronized(lock) {
                targetSize - (idle.getOrPut(dc) { ArrayDeque() }.size)
            }
            if (needed <= 0) return

            val cleanIp = cfg.effectiveCfCleanIp
            var connected = 0
            for (i in 0 until needed) {
                val res = connectOne(activeWorkers, fallbackDst, dc, cfg, timeoutMs = 8_000)
                var stillCurrent = false
                if (res != null) {
                    synchronized(lock) {
                        if (refilling[dc] === selfJob) {
                            idle.getOrPut(dc) { ArrayDeque() }.addLast(
                                Entry(
                                    ws = res.ws,
                                    createdMs = ProxyClock.monotonicMs(),
                                    workerDomain = res.workerDomain,
                                    usedPort80 = res.usedPort80,
                                    cleanIp = cleanIp,
                                )
                            )
                            connected++
                            stillCurrent = true
                        }
                    }
                    if (!stillCurrent) {
                        res.ws.closeQuietly()
                        return
                    }
                } else {
                    break
                }
            }

            synchronized(lock) {
                if (connected > 0) {
                    refillFailures.remove(dc)
                    refillAfterMs.remove(dc)
                } else if (!isOfflineProvider()) {
                    val failNow = ProxyClock.monotonicMs()
                    if (!deferIfAllWorkersPausedLocked(dc, activeWorkers, failNow)) {
                        val failures = (refillFailures[dc] ?: 0) + 1
                        refillFailures[dc] = failures
                        val exp = min(failures - 1, 12)
                        val delayMs = min(REFILL_BACKOFF_INITIAL_MS shl exp, REFILL_BACKOFF_MAX_MS)
                        refillAfterMs[dc] = failNow + delayMs
                        ProxyLogger.i("CF worker pool refill failed for DC$dc, retry in ${delayMs / 1000}s")
                    }
                }
                ProxyLogger.d("CF worker pool refilled DC$dc: ${idle[dc]?.size ?: 0} ready")
            }
        } finally {
            synchronized(lock) {
                if (refilling[dc] === selfJob) refilling.remove(dc)
            }
        }
    }

    private fun scheduleRotation(dc: Int, fallbackDst: String, workerDomains: List<String>) {
        val activeScope = scope ?: return
        val launchedJob = synchronized(lock) {
            if (rotating[dc]?.isActive == true) return
            lateinit var job: Job
            job = activeScope.launch(start = CoroutineStart.LAZY) {
                rotateLoop(dc, fallbackDst, workerDomains.toList(), job)
            }
            rotating[dc] = job
            job
        }
        launchedJob.start()
    }

    private suspend fun rotateLoop(
        dc: Int,
        fallbackDst: String,
        workerDomains: List<String>,
        selfJob: Job,
    ) {
        try {
            while (true) {
                val cfg = configProvider()
                val currentWorkers = cfg.cfproxyWorkerDomains
                if (cfg.poolSize <= 0 || currentWorkers.isEmpty()) break

                val now = ProxyClock.monotonicMs()
                val maxAge = currentMaxAgeMs()
                val expired = ArrayList<RawWebSocket>()
                var readyCount = 0
                var oldestExpiry = Long.MAX_VALUE

                synchronized(lock) {
                    val bucket = idle.getOrPut(dc) { ArrayDeque() }
                    val kept = ArrayDeque<Entry>(bucket.size)
                    while (bucket.isNotEmpty()) {
                        val entry = bucket.removeFirst()
                        if (now - entry.createdMs >= maxAge || entry.ws.isStale() || entry.workerDomain !in currentWorkers) {
                            expired.add(entry.ws)
                        } else {
                            kept.addLast(entry)
                            oldestExpiry = min(oldestExpiry, entry.createdMs + maxAge)
                        }
                    }
                    bucket.addAll(kept)
                    readyCount = bucket.size
                }

                expired.forEach { it.closeQuietly() }
                if (expired.isNotEmpty()) {
                    ProxyLogger.d("CF worker pool rotated DC$dc: ${expired.size} stale, $readyCount ready")
                }

                val limit = min(cfg.poolSize, PER_DC_LIMIT)
                if (!isIdleHibernating() && !isOfflineProvider() && readyCount < limit) {
                    scheduleRefill(dc, fallbackDst, currentWorkers)
                }

                val sleepy = (isIdleHibernating() || isOfflineProvider()) && readyCount == 0
                val checkInterval = if (sleepy) 30_000L else WS_POOL_CHECK_INTERVAL_MS
                var wakeAt = now + checkInterval
                if (oldestExpiry < wakeAt) wakeAt = oldestExpiry
                synchronized(lock) {
                    val after = refillAfterMs[dc]
                    if (refilling[dc]?.isActive != true && after != null && after > now) {
                        wakeAt = min(wakeAt, after)
                    }
                }
                delay((wakeAt - ProxyClock.monotonicMs()).coerceAtLeast(100L))
            }
        } finally {
            synchronized(lock) {
                if (rotating[dc] === selfJob) rotating.remove(dc)
            }
        }
    }

    /**
     * Connects to the first healthy CF worker domain. When TLS is enabled alongside
     * [ProxyConfig.autoPort80Fallback], automatically falls back to plain HTTP :80 on timeout
     * or TLS failure and remembers the working mode (upstream Flowseal v1.12.0 parity).
     */
    suspend fun connectOne(
        workerDomains: List<String>,
        fallbackDst: String,
        dc: Int,
        cfg: ProxyConfig = configProvider(),
        timeoutMs: Int = 8_000,
    ): PooledWorkerSocket? {
        val dstEnc = URLEncoder.encode(fallbackDst, "UTF-8")
        val path = "/apiws?dst=$dstEnc&dc=$dc"
        val modes: List<Boolean> = when {
            cfg.disableSecure -> listOf(false)
            !cfg.autoPort80Fallback -> listOf(true)
            else -> {
                val preferTls = trySecureFirst.get() && (balancer?.isCfTlsBlocked() != true)
                listOf(preferTls, !preferTls)
            }
        }

        for (workerDomain in availableDomains(workerDomains)) {
            for ((idx, secure) in modes.withIndex()) {
                try {
                    val ws = connector(workerDomain, path, secure, timeoutMs, cfg)
                    if (!cfg.disableSecure) {
                        trySecureFirst.set(secure)
                        if (!secure && cfg.autoPort80Fallback) {
                            balancer?.markCfTlsBlocked()
                        }
                    }
                    reportSuccess(workerDomain)
                    return PooledWorkerSocket(ws, workerDomain, usedPort80 = !secure)
                } catch (e: CancellationException) {
                    throw e
                } catch (exc: Exception) {
                    ProxyLogger.d(
                        "CF worker connect DC$dc via $workerDomain for $fallbackDst failed (tls=$secure): " +
                            "${exc.javaClass.simpleName}: ${exc.message}"
                    )
                    if (idx + 1 < modes.size && shouldFallbackToAlternatePort(exc, attemptedSecure = secure)) {
                        ProxyLogger.i(
                            "CF worker $workerDomain failed on ${if (secure) "TLS :443" else "HTTP :80"} -> " +
                                "trying ${if (secure) "HTTP :80" else "TLS :443"}"
                        )
                        continue
                    }
                    reportFailure(workerDomain, exc)
                    break
                }
            }
        }
        return null
    }

    fun warmup() {
        val cfg = configProvider()
        val fallbacks = DC_DEFAULT_IPS.filterKeys { it !in cfg.dcRedirects }
        if (cfg.poolSize <= 0 || fallbacks.isEmpty() || cfg.cfproxyWorkerDomains.isEmpty() || isOfflineProvider()) return
        val domains = cfg.cfproxyWorkerDomains.toList()
        for ((dc, fallbackDst) in fallbacks) {
            scheduleRefill(dc, fallbackDst, domains)
        }
        ProxyLogger.i("CF worker pool warmup started for ${fallbacks.size} DC(s)")
    }

    fun onConfigUpdated() {
        val toCancel = ArrayList<Job>()
        val toClose = ArrayList<RawWebSocket>()
        synchronized(lock) {
            toCancel.addAll(refilling.values)
            refilling.clear()
            for (bucket in idle.values) {
                bucket.forEach { toClose.add(it.ws) }
            }
            idle.clear()
            refillFailures.clear()
            refillAfterMs.clear()
        }
        exhaustedUntilMs.clear()
        trySecureFirst.set(true)
        toCancel.forEach { it.cancel() }
        toClose.forEach { it.closeQuietly() }
        if (!isIdleHibernating() && !isOfflineProvider()) {
            warmup()
        }
    }

    fun onNetworkChanged() {
        val toCancel = ArrayList<Job>()
        val toClose = ArrayList<RawWebSocket>()
        synchronized(lock) {
            toCancel.addAll(refilling.values)
            refilling.clear()
            for (bucket in idle.values) {
                for (entry in bucket) {
                    toClose.add(entry.ws)
                }
            }
            idle.clear()
            refillFailures.clear()
            refillAfterMs.clear()
        }
        exhaustedUntilMs.clear()
        trySecureFirst.set(true)
        toCancel.forEach { it.cancel() }
        toClose.forEach { it.closeQuietly() }
        if (!isIdleHibernating() && !isOfflineProvider()) {
            warmup()
        }
    }

    fun reset() {
        val toCancel = ArrayList<Job>()
        val toClose = ArrayList<RawWebSocket>()
        synchronized(lock) {
            toCancel.addAll(rotating.values)
            toCancel.addAll(refilling.values)
            rotating.clear()
            refilling.clear()
            for (bucket in idle.values) {
                for (entry in bucket) {
                    toClose.add(entry.ws)
                }
            }
            idle.clear()
            refillFailures.clear()
            refillAfterMs.clear()
        }
        exhaustedUntilMs.clear()
        trySecureFirst.set(true)
        toCancel.forEach { it.cancel() }
        toClose.forEach { it.closeQuietly() }
    }
}
