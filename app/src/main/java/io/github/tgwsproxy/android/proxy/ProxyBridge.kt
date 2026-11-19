package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.min

interface ClientStream {
    fun read(buf: ByteArray): Int
    fun readExactly(n: Int): ByteArray
    fun write(data: ByteArray)
    fun write(data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (off == 0 && len == data.size) {
            write(data)
        } else {
            write(data.copyOfRange(off, off + len))
        }
    }
    fun close()
}

class RawClientStream(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
) : ClientStream {
    private val writeLock = Any()

    override fun read(buf: ByteArray): Int = input.read(buf)

    override fun readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        input.readFully(out)
        return out
    }

    override fun write(data: ByteArray) = write(data, 0, data.size)

    override fun write(data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        synchronized(writeLock) {
            output.write(data, off, len)
            output.flush()
        }
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}

class FakeTlsClientStream(
    private val tlsStream: FakeTlsStream,
) : ClientStream {
    override fun read(buf: ByteArray): Int = tlsStream.read(buf)

    override fun readExactly(n: Int): ByteArray = tlsStream.readExactly(n)

    override fun write(data: ByteArray) = tlsStream.write(data, 0, data.size)

    override fun write(data: ByteArray, off: Int, len: Int) = tlsStream.write(data, off, len)

    override fun close() = tlsStream.close()
}

object ProxyBridge {
    private const val TCP_BACKOFF_INITIAL_MS = 30_000L
    private const val TCP_BACKOFF_MAX_MS = 3_600_000L
    private const val CF_RACE_STAGGER_MS = 1_200L
    private const val WS_HEARTBEAT_IDLE_MS = 25_000L
    private const val WS_HEARTBEAT_PONG_TIMEOUT_MS = 10_000L

    private data class TcpEndpoint(val dst: String, val port: Int)

    private data class CfConnectWinner(
        val ws: RawWebSocket,
        val baseDomain: String,
        val usedPort80: Boolean,
        val rttMs: Long,
    )

    private val tcpFailures = ConcurrentHashMap<TcpEndpoint, Int>()
    private val tcpRetryAfterMs = ConcurrentHashMap<TcpEndpoint, Long>()
    private val tcpConnecting = ConcurrentHashMap.newKeySet<TcpEndpoint>()

    fun resetTcpBackoff() {
        tcpFailures.clear()
        tcpRetryAfterMs.clear()
        tcpConnecting.clear()
    }

    suspend fun doFallback(
        stream: ClientStream,
        relayInit: ByteArray,
        label: String,
        dc: Int,
        isTestDc: Boolean,
        isMedia: Boolean,
        ctx: CryptoCtx,
        splitter: MsgSplitter?,
        config: ProxyConfig,
        balancer: DomainBalancer,
        cfWorkerPool: CfWorkerPool,
        h2Pool: CfH2Pool?,
        protoTag: ByteArray,
        cfProxyPool: CfProxyWsPool? = null,
        relayDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Boolean {
        val ipTable = if (isTestDc) DC_TEST_IPS else DC_DEFAULT_IPS
        val fallbackDst = ipTable[dc]
        val useCf = config.fallbackCfproxy && !isTestDc
        val workerDomains = config.cfproxyWorkerDomains

        val methods = ArrayList<String>(3)
        if (workerDomains.isNotEmpty() && fallbackDst != null) {
            methods.add("cf_worker")
        }
        if (useCf) {
            methods.add("cf")
        }
        if (fallbackDst != null) {
            methods.add("tcp")
        }

        for (method in methods) {
            when (method) {
                "cf_worker" -> if (fallbackDst != null) {
                    val ok = cfWorkerFallback(
                        stream = stream,
                        relayInit = relayInit,
                        label = label,
                        ctx = ctx,
                        dc = dc,
                        isTestDc = isTestDc,
                        isMedia = isMedia,
                        fallbackDst = fallbackDst,
                        config = config,
                        cfWorkerPool = cfWorkerPool,
                        relayDispatcher = relayDispatcher,
                    )
                    if (ok) return true
                }
                "cf" -> {
                    if (isMedia && h2Pool != null) {
                        val channel = h2Pool.open(dc, label)
                        if (channel != null) {
                            ProxyStats.connectionsH2.incrementAndGet()
                            ProxyStats.connectionsCfproxy.incrementAndGet()
                            h2Pool.bridgeH2(stream, channel, ctx, protoTag)
                            return true
                        }
                    }
                    val ok = cfProxyFallback(
                        stream = stream,
                        relayInit = relayInit,
                        label = label,
                        ctx = ctx,
                        dc = dc,
                        isMedia = isMedia,
                        splitter = splitter,
                        config = config,
                        balancer = balancer,
                        cfProxyPool = cfProxyPool,
                        relayDispatcher = relayDispatcher,
                    )
                    if (ok) return true
                }
                "tcp" -> if (fallbackDst != null) {
                    val ok = tcpFallback(
                        stream = stream,
                        dst = fallbackDst,
                        port = 443,
                        relayInit = relayInit,
                        label = label,
                        ctx = ctx,
                        bufferSize = config.bufferSize,
                        relayDispatcher = relayDispatcher,
                    )
                    if (ok) return true
                }
            }
        }
        return false
    }

    private suspend fun cfWorkerFallback(
        stream: ClientStream,
        relayInit: ByteArray,
        label: String,
        ctx: CryptoCtx,
        dc: Int,
        isTestDc: Boolean,
        isMedia: Boolean,
        fallbackDst: String,
        config: ProxyConfig,
        cfWorkerPool: CfWorkerPool,
        relayDispatcher: CoroutineDispatcher,
    ): Boolean {
        val mediaTag = if (isMedia) " media" else ""
        val workerDomains = config.cfproxyWorkerDomains
        if (workerDomains.isEmpty()) return false

        var ws: RawWebSocket? = null
        val pooled = if (isTestDc) null else cfWorkerPool.get(dc, fallbackDst, workerDomains)
        if (pooled != null) {
            try {
                pooled.first.send(relayInit)
                ws = pooled.first
                ProxyLogger.i("[$label] DC$dc$mediaTag -> CF worker pool hit via ${pooled.second} for $fallbackDst")
            } catch (e: CancellationException) {
                pooled.first.closeQuietly()
                throw e
            } catch (exc: Exception) {
                pooled.first.closeQuietly()
                ProxyLogger.d("[$label] Stale CF worker pooled socket for DC$dc (${exc.message}), reconnecting fresh")
            }
        }

        if (ws == null) {
            val dstEnc = URLEncoder.encode(fallbackDst, "UTF-8")
            val path = "/apiws?dst=$dstEnc&dc=$dc"
            val cleanIp = config.effectiveCfCleanIp
            for (workerDomain in cfWorkerPool.availableDomains(workerDomains)) {
                ProxyLogger.i("[$label] DC$dc$mediaTag -> trying CF worker $workerDomain for $fallbackDst")
                try {
                    val candidate = RawWebSocket.connect(
                        host = workerDomain,
                        domain = workerDomain,
                        timeoutMs = 8_000,
                        path = path,
                        secure = !config.disableSecure,
                        bufferSize = config.bufferSize,
                        dohProvider = config.dohProvider,
                        ipPreference = config.ipPreference,
                        cleanIpOverride = cleanIp,
                    )
                    try {
                        candidate.send(relayInit)
                    } catch (exc: Exception) {
                        candidate.closeQuietly()
                        throw exc
                    }
                    cfWorkerPool.reportSuccess(workerDomain)
                    ws = candidate
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (exc: Exception) {
                    cfWorkerPool.reportFailure(workerDomain, exc)
                    ProxyLogger.w("[$label] DC$dc$mediaTag CF worker $workerDomain failed: ${exc.javaClass.simpleName}: ${exc.message}")
                }
            }
        }

        val readyWs = ws ?: return false
        ProxyStats.connectionsCfproxy.incrementAndGet()
        bridgeWsReencrypt(
            stream = stream,
            ws = readyWs,
            label = label,
            ctx = ctx,
            dc = dc,
            isMedia = isMedia,
            splitter = null,
            relayDispatcher = relayDispatcher,
        )
        return true
    }

    private suspend fun cfProxyFallback(
        stream: ClientStream,
        relayInit: ByteArray,
        label: String,
        ctx: CryptoCtx,
        dc: Int,
        isMedia: Boolean,
        splitter: MsgSplitter?,
        config: ProxyConfig,
        balancer: DomainBalancer,
        cfProxyPool: CfProxyWsPool?,
        relayDispatcher: CoroutineDispatcher,
    ): Boolean {
        val mediaTag = if (isMedia) " media" else ""

        // Fast path: reuse a pre-warmed CF proxy WebSocket if available.
        val pooled = cfProxyPool?.get(dc)
        if (pooled != null) {
            try {
                pooled.first.send(relayInit)
                ProxyLogger.i("[$label] DC$dc$mediaTag -> CF proxy pool hit")
                ProxyStats.connectionsCfproxy.incrementAndGet()
                bridgeWsReencrypt(
                    stream = stream,
                    ws = pooled.first,
                    label = label,
                    ctx = ctx,
                    dc = dc,
                    isMedia = isMedia,
                    splitter = splitter,
                    relayDispatcher = relayDispatcher,
                )
                return true
            } catch (e: CancellationException) {
                pooled.first.closeQuietly()
                throw e
            } catch (exc: Exception) {
                pooled.first.closeQuietly()
                ProxyLogger.d("[$label] Stale CF proxy pooled socket for DC$dc (${exc.message}), racing fresh")
            }
        }

        val modeDesc = when {
            config.disableSecure -> "port 80"
            config.autoPort80Fallback -> "TLS 443 / auto :80"
            else -> "TLS 443"
        }
        ProxyLogger.i("[$label] DC$dc$mediaTag -> trying CF proxy ($modeDesc)")
        val connectStarted = System.currentTimeMillis()
        val orderedDomains = balancer.getDomainsForDc(dc)
        if (orderedDomains.isEmpty()) return false

        // Race first wave (up to 3 top-ranked domains), then a second wave of 3 if all failed.
        var winner = raceCfDomains(
            dc = dc,
            candidates = orderedDomains.take(3),
            config = config,
            balancer = balancer,
            label = label,
            mediaTag = mediaTag,
        )
        if (winner == null && orderedDomains.size > 3) {
            winner = raceCfDomains(
                dc = dc,
                candidates = orderedDomains.drop(3).take(3),
                config = config,
                balancer = balancer,
                label = label,
                mediaTag = mediaTag,
            )
        }
        if (winner == null) return false

        val setupMs = System.currentTimeMillis() - connectStarted
        val routeTag = if (winner.usedPort80) "cf-p80" else "cf"
        ProxyLogger.d("[$label] WS CONNECT DC$dc$mediaTag route=$routeTag setup_ms=$setupMs")
        if (balancer.reportSuccess(dc, winner.baseDomain, winner.rttMs, usedPort80 = winner.usedPort80)) {
            ProxyLogger.i("[$label] Switched active CF domain (rtt=${winner.rttMs}ms, $routeTag)")
        }

        ProxyStats.connectionsCfproxy.incrementAndGet()
        try {
            winner.ws.send(relayInit)
        } catch (exc: Exception) {
            winner.ws.closeQuietly()
            throw exc
        }
        bridgeWsReencrypt(
            stream = stream,
            ws = winner.ws,
            label = label,
            ctx = ctx,
            dc = dc,
            isMedia = isMedia,
            splitter = splitter,
            relayDispatcher = relayDispatcher,
        )
        return true
    }

    private suspend fun raceCfDomains(
        dc: Int,
        candidates: List<String>,
        config: ProxyConfig,
        balancer: DomainBalancer,
        label: String,
        mediaTag: String,
    ): CfConnectWinner? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) {
            return connectSingleCfDomain(dc, candidates[0], config, balancer, label, mediaTag)
        }

        val winnerRef = AtomicReference<CfConnectWinner?>(null)
        val done = CompletableDeferred<CfConnectWinner?>()
        val remaining = AtomicInteger(candidates.size)
        val failedSignal = Channel<Unit>(Channel.CONFLATED)

        return coroutineScope {
            val launcher = launch(Dispatchers.IO) {
                for ((idx, baseDomain) in candidates.withIndex()) {
                    if (winnerRef.get() != null || !isActive) break
                    if (idx > 0) {
                        withTimeoutOrNull(CF_RACE_STAGGER_MS) { failedSignal.receive() }
                        if (winnerRef.get() != null || !isActive) break
                    }
                    launch {
                        val res = try {
                            connectSingleCfDomain(dc, baseDomain, config, balancer, label, mediaTag)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        if (res != null) {
                            if (winnerRef.compareAndSet(null, res)) {
                                if (!done.complete(res)) {
                                    res.ws.closeQuietly()
                                }
                            } else {
                                res.ws.closeQuietly()
                            }
                        } else {
                            failedSignal.trySend(Unit)
                            if (remaining.decrementAndGet() == 0) {
                                done.complete(null)
                            }
                        }
                    }
                }
            }

            val result = try {
                done.await()
            } catch (e: CancellationException) {
                done.cancel()
                launcher.cancel()
                failedSignal.close()
                winnerRef.getAndSet(null)?.ws?.closeQuietly()
                throw e
            }
            launcher.cancel()
            failedSignal.close()
            result
        }
    }

    private suspend fun connectSingleCfDomain(
        dc: Int,
        baseDomain: String,
        config: ProxyConfig,
        balancer: DomainBalancer,
        label: String,
        mediaTag: String,
    ): CfConnectWinner? {
        val domain = "kws$dc.$baseDomain"
        val cleanIp = config.effectiveCfCleanIp
        val preferPort80 = config.disableSecure ||
            (config.autoPort80Fallback && balancer.shouldPreferPort80(baseDomain))
        val portPlan = when {
            config.disableSecure -> listOf(false)
            preferPort80 -> listOf(false, true)
            config.autoPort80Fallback -> listOf(true, false)
            else -> listOf(true)
        }

        var tlsFailed = false
        for (secure in portPlan) {
            val started = System.currentTimeMillis()
            try {
                val ws = RawWebSocket.connect(
                    host = domain,
                    domain = domain,
                    timeoutMs = if (secure) 6_000 else 5_500,
                    path = WS_PATH,
                    secure = secure,
                    bufferSize = config.bufferSize,
                    dohProvider = config.dohProvider,
                    ipPreference = config.ipPreference,
                    cleanIpOverride = cleanIp,
                )
                val rtt = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                if (tlsFailed && !secure) {
                    balancer.reportFailure(baseDomain, tlsSpecific = true)
                    ProxyLogger.i("[$label] DC$dc$mediaTag auto port-80 fallback succeeded (rtt=${rtt}ms)")
                }
                return CfConnectWinner(ws, baseDomain, usedPort80 = !secure, rttMs = rtt)
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                if (secure) tlsFailed = true
                ProxyLogger.w(
                    "[$label] DC$dc$mediaTag CF proxy (${if (secure) "443" else "80"}) failed: " +
                        "${exc.javaClass.simpleName}: ${exc.message}"
                )
            }
        }
        balancer.reportFailure(baseDomain, tlsSpecific = false)
        return null
    }

    private suspend fun tcpFallback(
        stream: ClientStream,
        dst: String,
        port: Int,
        relayInit: ByteArray,
        label: String,
        ctx: CryptoCtx,
        bufferSize: Int,
        relayDispatcher: CoroutineDispatcher,
    ): Boolean = withContext(relayDispatcher) {
        val key = TcpEndpoint(dst, port)
        val now = System.currentTimeMillis()
        val retryAt = tcpRetryAfterMs[key] ?: 0L
        if (now < retryAt || !tcpConnecting.add(key)) {
            ProxyLogger.d("[$label] TCP fallback to $dst:$port skipped (backoff or connecting)")
            return@withContext false
        }

        val remote = Socket()
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                try { remote.close() } catch (_: Exception) {}
                stream.close()
            }
        }
        try {
            try {
                ProxyLogger.i("[$label] TCP fallback to $dst:$port")
                remote.tcpNoDelay = true
                runCatching { remote.keepAlive = true }
                runCatching {
                    remote.receiveBufferSize = bufferSize
                    remote.sendBufferSize = bufferSize
                }
                remote.connect(InetSocketAddress(dst, port), 8_000)
                val remoteOut = remote.getOutputStream()
                remoteOut.write(relayInit)
                remoteOut.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                val failures = (tcpFailures[key] ?: 0) + 1
                tcpFailures[key] = failures
                val exp = min(failures - 1, 7)
                val delayMs = min(TCP_BACKOFF_INITIAL_MS shl exp, TCP_BACKOFF_MAX_MS)
                tcpRetryAfterMs[key] = System.currentTimeMillis() + delayMs
                ProxyLogger.w("[$label] TCP fallback to $dst:$port failed: ${exc.javaClass.simpleName}: ${exc.message}; retry in ${delayMs / 1000}s")
                try { remote.close() } catch (_: Exception) {}
                return@withContext false
            } finally {
                tcpConnecting.remove(key)
            }

            tcpFailures.remove(key)
            tcpRetryAfterMs.remove(key)
            ProxyStats.connectionsTcpFallback.incrementAndGet()
            bridgeTcpReencrypt(stream, remote, label, ctx, relayDispatcher)
            true
        } finally {
            cancelHandle?.dispose()
            try { remote.close() } catch (_: Exception) {}
        }
    }

    suspend fun bridgeWsReencrypt(
        stream: ClientStream,
        ws: RawWebSocket,
        label: String,
        ctx: CryptoCtx,
        dc: Int? = null,
        isMedia: Boolean = false,
        splitter: MsgSplitter? = null,
        relayDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = withContext(relayDispatcher) {
        val dcTag = if (dc != null) "DC$dc${if (isMedia) "m" else ""}" else "DC?"
        var upBytes = 0L
        var downBytes = 0L
        var upPackets = 0L
        var downPackets = 0L
        val startTime = System.currentTimeMillis()
        val closeReason = AtomicReference("normal")

        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                ws.closeQuietly()
                stream.close()
            }
        }

        try {
            coroutineScope {
                val heartbeatJob = launch {
                    while (isActive && !ws.isClosed) {
                        delay(WS_HEARTBEAT_IDLE_MS)
                        if (ws.isClosed) break
                        val idleMs = System.currentTimeMillis() - ws.lastRecvMs
                        if (idleMs < WS_HEARTBEAT_IDLE_MS) continue
                        val pingSentMs = System.currentTimeMillis()
                        try {
                            ws.ping()
                        } catch (_: Exception) {
                            closeReason.compareAndSet("normal", "upstream: ping_failed")
                            ws.closeQuietly()
                            stream.close()
                            break
                        }
                        delay(WS_HEARTBEAT_PONG_TIMEOUT_MS)
                        if (!ws.isClosed && ws.lastRecvMs < pingSentMs && ws.lastPongMs < pingSentMs) {
                            closeReason.compareAndSet("normal", "upstream: pong_timeout")
                            ProxyLogger.d("[$label] $dcTag WS heartbeat pong timeout; closing dead socket")
                            ws.closeQuietly()
                            stream.close()
                            break
                        }
                    }
                }

                val tcpToWs = launch {
                    val buf = ByteArray(65536)
                    val plainBuf = if (splitter != null) ByteArray(65536) else null
                    val encBuf = if (splitter != null) ByteArray(65536) else null
                    try {
                        while (true) {
                            val n = stream.read(buf)
                            if (n <= 0) {
                                if (splitter != null) {
                                    val tail = splitter.flush()
                                    if (tail.isNotEmpty()) {
                                        ws.send(tail[0])
                                    }
                                }
                                break
                            }
                            ProxyStats.bytesUp.addAndGet(n.toLong())
                            upBytes += n
                            upPackets++
                            if (splitter != null && plainBuf != null && encBuf != null) {
                                ctx.cltDec.updateInto(buf, 0, n, plainBuf, 0)
                                ctx.tgEnc.updateInto(plainBuf, 0, n, encBuf, 0)
                                val parts = splitter.split(encBuf, 0, plainBuf, 0, n)
                                if (parts.isEmpty()) continue
                                if (parts.size > 1) {
                                    ws.sendBatch(parts)
                                } else {
                                    ws.send(parts[0])
                                }
                            } else {
                                ctx.cltDec.updateInPlace(buf, 0, n)
                                ctx.tgEnc.updateInPlace(buf, 0, n)
                                ws.send(buf, 0, n)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        closeReason.compareAndSet("normal", "client: ${e.javaClass.simpleName}")
                        ProxyLogger.d("[$label] tcp->ws ended: ${e.message}")
                    } finally {
                        ws.closeQuietly()
                    }
                }

                val wsToTcp = launch {
                    try {
                        while (true) {
                            val data = ws.recv()
                            if (data == null) {
                                closeReason.compareAndSet("normal", "upstream: ws_close")
                                break
                            }
                            val n = data.size.toLong()
                            ProxyStats.bytesDown.addAndGet(n)
                            downBytes += n
                            downPackets++
                            ctx.tgDec.updateInPlace(data)
                            ctx.cltEnc.updateInPlace(data)
                            stream.write(data)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: EOFException) {
                        closeReason.compareAndSet("normal", "upstream: tcp_reset")
                    } catch (e: Exception) {
                        closeReason.compareAndSet("normal", "upstream: ${e.javaClass.simpleName}")
                        ProxyLogger.d("[$label] ws->tcp ended: ${e.message}")
                    } finally {
                        stream.close()
                    }
                }

                tcpToWs.join()
                wsToTcp.join()
                heartbeatJob.cancel()
            }
        } finally {
            cancelHandle?.dispose()
            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
            ProxyLogger.i(
                "[$label] $dcTag WS session closed (${closeReason.get()}): " +
                    "^${humanBytes(upBytes)} ($upPackets pkts) " +
                    "v${humanBytes(downBytes)} ($downPackets pkts) in ${"%.1f".format(elapsedSec)}s"
            )
            ws.closeQuietly()
            stream.close()
        }
    }

    private suspend fun bridgeTcpReencrypt(
        stream: ClientStream,
        remote: Socket,
        label: String,
        ctx: CryptoCtx,
        relayDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = withContext(relayDispatcher) {
        val remoteIn = remote.getInputStream()
        val remoteOut = remote.getOutputStream()
        try {
            coroutineScope {
                val up = launch {
                    val buf = ByteArray(65536)
                    try {
                        while (true) {
                            val n = stream.read(buf)
                            if (n <= 0) break
                            ProxyStats.bytesUp.addAndGet(n.toLong())
                            ctx.cltDec.updateInPlace(buf, 0, n)
                            ctx.tgEnc.updateInPlace(buf, 0, n)
                            remoteOut.write(buf, 0, n)
                            remoteOut.flush()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ProxyLogger.d("[$label] tcp forward up ended: ${e.message}")
                    } finally {
                        try { remote.close() } catch (_: Exception) {}
                    }
                }
                val down = launch {
                    val buf = ByteArray(65536)
                    try {
                        while (true) {
                            val n = remoteIn.read(buf)
                            if (n <= 0) break
                            ProxyStats.bytesDown.addAndGet(n.toLong())
                            ctx.tgDec.updateInPlace(buf, 0, n)
                            ctx.cltEnc.updateInPlace(buf, 0, n)
                            stream.write(buf, 0, n)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ProxyLogger.d("[$label] tcp forward down ended: ${e.message}")
                    } finally {
                        stream.close()
                    }
                }
                up.join()
                down.join()
            }
        } finally {
            try { remote.close() } catch (_: Exception) {}
            stream.close()
        }
    }
}
