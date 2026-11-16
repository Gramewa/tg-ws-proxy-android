package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class WsPool(
    private val configProvider: () -> ProxyConfig,
) {
    companion object {
        const val WS_POOL_MAX_AGE_MS = 120_000L
        const val WS_POOL_CHECK_INTERVAL_MS = 5_000L
        const val REFILL_BACKOFF_INITIAL_MS = 1_000L
        const val REFILL_BACKOFF_MAX_MS = 3_600_000L
    }

    data class PoolKey(val dc: Int, val isMedia: Boolean, val isTestDc: Boolean) {
        fun tag(): String = "DC$dc${if (isTestDc) "t" else ""}${if (isMedia) "m" else ""}"
    }

    private data class Entry(val ws: RawWebSocket, val createdMs: Long)

    private val lock = Any()
    private val idle = HashMap<PoolKey, ArrayDeque<Entry>>()
    private val refilling = HashMap<PoolKey, Job>()
    private val rotating = HashMap<PoolKey, Job>()
    private val refillFailures = HashMap<PoolKey, Int>()
    private val refillAfterMs = HashMap<PoolKey, Long>()
    private val tryFrontingFirst = AtomicBoolean(false)

    private var scope: CoroutineScope? = null

    fun attachScope(parentScope: CoroutineScope) {
        scope = parentScope
    }

    fun get(dc: Int, isMedia: Boolean, isTestDc: Boolean = false): RawWebSocket? {
        val cfg = configProvider()
        val targetIp = cfg.dcRedirects[dc] ?: return null
        if (cfg.poolSize <= 0) return null

        val key = PoolKey(dc, isMedia, isTestDc)
        val domains = wsDomains(dc, isMedia)
        val now = System.currentTimeMillis()
        var hit: RawWebSocket? = null
        var hitAgeSec = 0.0
        var left = 0
        val staleList = ArrayList<RawWebSocket>()

        synchronized(lock) {
            val bucket = idle.getOrPut(key) { ArrayDeque() }
            while (bucket.isNotEmpty()) {
                val entry = bucket.removeFirst()
                val ageMs = now - entry.createdMs
                if (isEntryStale(entry.ws, ageMs)) {
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
            scheduleRefill(key, targetIp, domains)
            return hit
        }

        ProxyStats.poolMisses.incrementAndGet()
        scheduleRefill(key, targetIp, domains)
        return null
    }

    private fun isEntryStale(ws: RawWebSocket, ageMs: Long): Boolean {
        return ageMs >= WS_POOL_MAX_AGE_MS || ws.isStale()
    }

    private fun scheduleRefill(key: PoolKey, targetIp: String, domains: List<String>) {
        val cfg = configProvider()
        if (cfg.poolSize <= 0) return
        val activeScope = scope ?: return
        scheduleRotation(key, targetIp, domains)

        val now = System.currentTimeMillis()
        val launchedJob = synchronized(lock) {
            if (refilling[key]?.isActive == true) return
            if (now < (refillAfterMs[key] ?: 0L)) return
            lateinit var job: Job
            job = activeScope.launch(start = CoroutineStart.LAZY) {
                refill(key, targetIp, domains, job)
            }
            refilling[key] = job
            job
        }
        launchedJob.start()
    }

    private suspend fun refill(
        key: PoolKey,
        targetIp: String,
        domains: List<String>,
        selfJob: Job,
    ) {
        val cfg = configProvider()
        val needed = synchronized(lock) {
            val bucket = idle.getOrPut(key) { ArrayDeque() }
            cfg.poolSize - bucket.size
        }
        if (needed <= 0) {
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
                        val ws = connectOne(targetIp, domains, path, cfg.bufferSize)
                        if (ws != null) {
                            var adopted = false
                            synchronized(lock) {
                                if (refilling[key] === selfJob && isActive) {
                                    val bucket = idle.getOrPut(key) { ArrayDeque() }
                                    bucket.addLast(Entry(ws, System.currentTimeMillis()))
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
                } else {
                    val failures = (refillFailures[key] ?: 0) + 1
                    refillFailures[key] = failures
                    val exp = min(failures - 1, 12)
                    val delayMs = min(REFILL_BACKOFF_INITIAL_MS shl exp, REFILL_BACKOFF_MAX_MS)
                    refillAfterMs[key] = System.currentTimeMillis() + delayMs
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

    private fun scheduleRotation(key: PoolKey, targetIp: String, domains: List<String>) {
        val activeScope = scope ?: return
        val launchedJob = synchronized(lock) {
            if (rotating[key]?.isActive == true) return
            lateinit var job: Job
            job = activeScope.launch(start = CoroutineStart.LAZY) {
                rotateLoop(key, targetIp, domains, job)
            }
            rotating[key] = job
            job
        }
        launchedJob.start()
    }

    private suspend fun rotateLoop(
        key: PoolKey,
        targetIp: String,
        domains: List<String>,
        selfJob: Job,
    ) {
        try {
            while (configProvider().poolSize > 0) {
                val now = System.currentTimeMillis()
                val expired = ArrayList<RawWebSocket>()
                var readyCount = 0
                var oldestExpiry = Long.MAX_VALUE

                synchronized(lock) {
                    val bucket = idle.getOrPut(key) { ArrayDeque() }
                    val kept = ArrayDeque<Entry>(bucket.size)
                    while (bucket.isNotEmpty()) {
                        val entry = bucket.removeFirst()
                        if (isEntryStale(entry.ws, now - entry.createdMs)) {
                            expired.add(entry.ws)
                        } else {
                            kept.addLast(entry)
                            oldestExpiry = min(oldestExpiry, entry.createdMs + WS_POOL_MAX_AGE_MS)
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

                if (readyCount < configProvider().poolSize) {
                    scheduleRefill(key, targetIp, domains)
                }

                var wakeAt = now + WS_POOL_CHECK_INTERVAL_MS
                if (oldestExpiry < wakeAt) {
                    wakeAt = oldestExpiry
                }
                synchronized(lock) {
                    val after = refillAfterMs[key]
                    if (refilling[key]?.isActive != true && after != null && after > now) {
                        wakeAt = min(wakeAt, after)
                    }
                }
                val sleepMs = (wakeAt - System.currentTimeMillis()).coerceAtLeast(100L)
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
        bufferSize: Int,
    ): RawWebSocket? {
        val modes = if (tryFrontingFirst.get()) listOf(true, false) else listOf(false, true)
        for (domain in domains) {
            for (fronted in modes) {
                try {
                    val ws = RawWebSocket.connect(
                        host = targetIp,
                        domain = domain,
                        timeoutMs = if (fronted) 7_000 else 8_000,
                        path = path,
                        sni = if (fronted) "sprinthost.ru" else null,
                        secure = true,
                        bufferSize = bufferSize,
                    )
                    tryFrontingFirst.set(fronted)
                    if (fronted) {
                        ProxyStats.connectionsFronting.incrementAndGet()
                    }
                    ProxyLogger.d("WS pool connected $domain$path via $targetIp (fronting=$fronted)")
                    return ws
                } catch (e: CancellationException) {
                    throw e
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
        if (cfg.poolSize <= 0 || cfg.dcRedirects.isEmpty()) return
        for ((dc, targetIp) in cfg.dcRedirects) {
            for (isMedia in listOf(false, true)) {
                val domains = wsDomains(dc, isMedia)
                val key = PoolKey(dc, isMedia, cfg.forceTestDc)
                scheduleRefill(key, targetIp, domains)
            }
        }
        ProxyLogger.i("WS pool warmup started for ${cfg.dcRedirects.size} DC(s)")
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
        tryFrontingFirst.set(false)
        toCancel.forEach { it.cancel() }
        toClose.forEach { it.closeQuietly() }
    }
}

class CfWorkerPool(
    private val configProvider: () -> ProxyConfig,
) {
    companion object {
        const val WS_POOL_MAX_AGE_MS = 100_000L
        const val WS_POOL_CHECK_INTERVAL_MS = 5_000L
        const val PER_DC_LIMIT = 1
        const val REFILL_BACKOFF_INITIAL_MS = 1_000L
        const val REFILL_BACKOFF_MAX_MS = 3_600_000L
    }

    private data class Entry(
        val ws: RawWebSocket,
        val createdMs: Long,
        val workerDomain: String,
    )

    private val lock = Any()
    private val random = SecureRandom()
    private val idle = HashMap<Int, ArrayDeque<Entry>>()
    private val refilling = HashMap<Int, Job>()
    private val rotating = HashMap<Int, Job>()
    private val refillFailures = HashMap<Int, Int>()
    private val refillAfterMs = HashMap<Int, Long>()
    private val exhaustedUntilMs = ConcurrentHashMap<String, Long>()

    private var scope: CoroutineScope? = null

    fun attachScope(parentScope: CoroutineScope) {
        scope = parentScope
    }

    fun get(dc: Int, fallbackDst: String, workerDomains: List<String>): Pair<RawWebSocket, String>? {
        val cfg = configProvider()
        if (cfg.poolSize <= 0 || workerDomains.isEmpty()) return null

        val now = System.currentTimeMillis()
        val staleList = ArrayList<RawWebSocket>()
        var hit: Pair<RawWebSocket, String>? = null
        var hitAgeSec = 0.0
        var left = 0

        synchronized(lock) {
            val bucket = idle.getOrPut(dc) { ArrayDeque() }
            while (bucket.isNotEmpty()) {
                val entry = bucket.removeFirst()
                val ageMs = now - entry.createdMs
                if (ageMs >= WS_POOL_MAX_AGE_MS || entry.ws.isStale()) {
                    staleList.add(entry.ws)
                    continue
                }
                hit = entry.ws to entry.workerDomain
                hitAgeSec = ageMs / 1000.0
                left = bucket.size
                break
            }
        }

        staleList.forEach { it.closeQuietly() }

        if (hit != null) {
            ProxyStats.cfPoolHits.incrementAndGet()
            ProxyLogger.d("CF worker pool hit DC$dc via ${hit!!.second} (age=${"%.1f".format(hitAgeSec)}s, left=$left)")
            scheduleRefill(dc, fallbackDst, workerDomains)
            return hit
        }

        ProxyStats.cfPoolMisses.incrementAndGet()
        scheduleRefill(dc, fallbackDst, workerDomains)
        return null
    }

    fun availableDomains(workerDomains: List<String>): List<String> {
        val now = System.currentTimeMillis()
        val list = ArrayList<String>(workerDomains.size)
        for (domain in workerDomains) {
            if (domain in list) continue
            val until = exhaustedUntilMs[domain] ?: 0L
            if (until > now) continue
            if (until > 0L) exhaustedUntilMs.remove(domain)
            list.add(domain)
        }
        list.shuffle(random)
        return list
    }

    fun reportFailure(workerDomain: String, exc: Throwable) {
        // Mirrors upstream comment (currently no-op until daily quota check is finalized)
        val _unused = workerDomain to exc
    }

    private fun scheduleRefill(dc: Int, fallbackDst: String, workerDomains: List<String>) {
        val cfg = configProvider()
        if (cfg.poolSize <= 0 || workerDomains.isEmpty()) return
        val activeScope = scope ?: return
        scheduleRotation(dc, fallbackDst, workerDomains)

        val now = System.currentTimeMillis()
        val launchedJob = synchronized(lock) {
            if (refilling[dc]?.isActive == true) return
            if (now < (refillAfterMs[dc] ?: 0L)) return
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
            val targetSize = min(cfg.poolSize, PER_DC_LIMIT)
            val needed = synchronized(lock) {
                targetSize - (idle.getOrPut(dc) { ArrayDeque() }.size)
            }
            if (needed <= 0) return

            var connected = 0
            for (i in 0 until needed) {
                val res = connectOne(workerDomains, fallbackDst, dc, cfg)
                var stillCurrent = false
                if (res != null) {
                    synchronized(lock) {
                        if (refilling[dc] === selfJob) {
                            idle.getOrPut(dc) { ArrayDeque() }.addLast(
                                Entry(res.first, System.currentTimeMillis(), res.second)
                            )
                            connected++
                            stillCurrent = true
                        }
                    }
                    if (!stillCurrent) {
                        res.first.closeQuietly()
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
                } else {
                    val failures = (refillFailures[dc] ?: 0) + 1
                    refillFailures[dc] = failures
                    val exp = min(failures - 1, 12)
                    val delayMs = min(REFILL_BACKOFF_INITIAL_MS shl exp, REFILL_BACKOFF_MAX_MS)
                    refillAfterMs[dc] = System.currentTimeMillis() + delayMs
                    ProxyLogger.i("CF worker pool refill failed for DC$dc, retry in ${delayMs / 1000}s")
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
            while (configProvider().poolSize > 0) {
                val now = System.currentTimeMillis()
                val expired = ArrayList<RawWebSocket>()
                var readyCount = 0
                var oldestExpiry = Long.MAX_VALUE

                synchronized(lock) {
                    val bucket = idle.getOrPut(dc) { ArrayDeque() }
                    val kept = ArrayDeque<Entry>(bucket.size)
                    while (bucket.isNotEmpty()) {
                        val entry = bucket.removeFirst()
                        if (now - entry.createdMs >= WS_POOL_MAX_AGE_MS || entry.ws.isStale()) {
                            expired.add(entry.ws)
                        } else {
                            kept.addLast(entry)
                            oldestExpiry = min(oldestExpiry, entry.createdMs + WS_POOL_MAX_AGE_MS)
                        }
                    }
                    bucket.addAll(kept)
                    readyCount = bucket.size
                }

                expired.forEach { it.closeQuietly() }
                if (expired.isNotEmpty()) {
                    ProxyLogger.d("CF worker pool rotated DC$dc: ${expired.size} stale, $readyCount ready")
                }

                val limit = min(configProvider().poolSize, PER_DC_LIMIT)
                if (readyCount < limit) {
                    scheduleRefill(dc, fallbackDst, workerDomains)
                }

                var wakeAt = now + WS_POOL_CHECK_INTERVAL_MS
                if (oldestExpiry < wakeAt) wakeAt = oldestExpiry
                synchronized(lock) {
                    val after = refillAfterMs[dc]
                    if (refilling[dc]?.isActive != true && after != null && after > now) {
                        wakeAt = min(wakeAt, after)
                    }
                }
                delay((wakeAt - System.currentTimeMillis()).coerceAtLeast(100L))
            }
        } finally {
            synchronized(lock) {
                if (rotating[dc] === selfJob) rotating.remove(dc)
            }
        }
    }

    private suspend fun connectOne(
        workerDomains: List<String>,
        fallbackDst: String,
        dc: Int,
        cfg: ProxyConfig,
    ): Pair<RawWebSocket, String>? {
        val dstEnc = URLEncoder.encode(fallbackDst, "UTF-8")
        val path = "/apiws?dst=$dstEnc&dc=$dc"
        for (workerDomain in availableDomains(workerDomains)) {
            try {
                val ws = RawWebSocket.connect(
                    host = workerDomain,
                    domain = workerDomain,
                    timeoutMs = 8_000,
                    path = path,
                    secure = !cfg.disableSecure,
                    bufferSize = cfg.bufferSize,
                )
                return ws to workerDomain
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                ProxyLogger.d("CF worker pool connect DC$dc via $workerDomain for $fallbackDst failed: ${exc.javaClass.simpleName}: ${exc.message}")
                reportFailure(workerDomain, exc)
            }
        }
        return null
    }

    fun warmup() {
        val cfg = configProvider()
        val fallbacks = DC_DEFAULT_IPS.filterKeys { it !in cfg.dcRedirects }
        if (cfg.poolSize <= 0 || fallbacks.isEmpty() || cfg.cfproxyWorkerDomains.isEmpty()) return
        val domains = cfg.cfproxyWorkerDomains.toList()
        for ((dc, fallbackDst) in fallbacks) {
            scheduleRefill(dc, fallbackDst, domains)
        }
        ProxyLogger.i("CF worker pool warmup started for ${fallbacks.size} DC(s)")
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
        toCancel.forEach { it.cancel() }
        toClose.forEach { it.closeQuietly() }
    }
}
