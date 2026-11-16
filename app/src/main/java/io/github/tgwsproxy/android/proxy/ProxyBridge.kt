package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

interface ClientStream {
    fun read(buf: ByteArray): Int
    fun readExactly(n: Int): ByteArray
    fun write(data: ByteArray)
    fun close()
}

class RawClientStream(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
) : ClientStream {
    override fun read(buf: ByteArray): Int = input.read(buf)

    override fun readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        input.readFully(out)
        return out
    }

    @Synchronized
    override fun write(data: ByteArray) {
        if (data.isEmpty()) return
        output.write(data)
        output.flush()
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

    override fun write(data: ByteArray) = tlsStream.write(data)

    override fun close() = tlsStream.close()
}

object ProxyBridge {
    private const val TCP_BACKOFF_INITIAL_MS = 30_000L
    private const val TCP_BACKOFF_MAX_MS = 3_600_000L

    private data class TcpEndpoint(val dst: String, val port: Int)

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
    ): Boolean {
        val mediaTag = if (isMedia) " media" else ""
        val workerDomains = config.cfproxyWorkerDomains
        if (workerDomains.isEmpty()) return false

        val pooled = if (isTestDc) null else cfWorkerPool.get(dc, fallbackDst, workerDomains)
        val ws: RawWebSocket
        if (pooled != null) {
            ws = pooled.first
            ProxyLogger.i("[$label] DC$dc$mediaTag -> CF worker pool hit via ${pooled.second} for $fallbackDst")
        } else {
            val dstEnc = URLEncoder.encode(fallbackDst, "UTF-8")
            val path = "/apiws?dst=$dstEnc&dc=$dc"
            var connected: RawWebSocket? = null
            for (workerDomain in cfWorkerPool.availableDomains(workerDomains)) {
                ProxyLogger.i("[$label] DC$dc$mediaTag -> trying CF worker $workerDomain for $fallbackDst")
                try {
                    connected = RawWebSocket.connect(
                        host = workerDomain,
                        domain = workerDomain,
                        timeoutMs = 10_000,
                        path = path,
                        secure = !config.disableSecure,
                        bufferSize = config.bufferSize,
                    )
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (exc: Exception) {
                    cfWorkerPool.reportFailure(workerDomain, exc)
                    ProxyLogger.w("[$label] DC$dc$mediaTag CF worker $workerDomain failed: ${exc.javaClass.simpleName}: ${exc.message}")
                }
            }
            if (connected == null) return false
            ws = connected
        }

        ProxyStats.connectionsCfproxy.incrementAndGet()
        try {
            ws.send(relayInit)
        } catch (exc: Exception) {
            ws.closeQuietly()
            throw exc
        }
        bridgeWsReencrypt(
            stream = stream,
            ws = ws,
            label = label,
            ctx = ctx,
            dc = dc,
            isMedia = isMedia,
            splitter = null,
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
    ): Boolean {
        val mediaTag = if (isMedia) " media" else ""
        ProxyLogger.i("[$label] DC$dc$mediaTag -> trying CF proxy (${if (config.disableSecure) "port 80" else "TLS 443"})")
        val connectStarted = System.currentTimeMillis()
        var ws: RawWebSocket? = null
        var chosenDomain: String? = null

        for (baseDomain in balancer.getDomainsForDc(dc)) {
            val domain = "kws$dc.$baseDomain"
            try {
                ws = RawWebSocket.connect(
                    host = domain,
                    domain = domain,
                    timeoutMs = 10_000,
                    path = WS_PATH,
                    secure = !config.disableSecure,
                    bufferSize = config.bufferSize,
                )
                chosenDomain = baseDomain
                break
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                ProxyLogger.w("[$label] DC$dc$mediaTag CF proxy failed: ${exc.javaClass.simpleName}: ${exc.message}")
            }
        }

        if (ws == null) return false

        val setupMs = System.currentTimeMillis() - connectStarted
        ProxyLogger.d("[$label] WS CONNECT DC$dc$mediaTag route=cf setup_ms=$setupMs")
        if (chosenDomain != null && balancer.updateDomainForDc(dc, chosenDomain)) {
            ProxyLogger.i("[$label] Switched active CF domain")
        }

        ProxyStats.connectionsCfproxy.incrementAndGet()
        try {
            ws.send(relayInit)
        } catch (exc: Exception) {
            ws.closeQuietly()
            throw exc
        }
        bridgeWsReencrypt(
            stream = stream,
            ws = ws,
            label = label,
            ctx = ctx,
            dc = dc,
            isMedia = isMedia,
            splitter = splitter,
        )
        return true
    }

    private suspend fun tcpFallback(
        stream: ClientStream,
        dst: String,
        port: Int,
        relayInit: ByteArray,
        label: String,
        ctx: CryptoCtx,
        bufferSize: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        val key = TcpEndpoint(dst, port)
        val now = System.currentTimeMillis()
        val retryAt = tcpRetryAfterMs[key] ?: 0L
        if (now < retryAt || !tcpConnecting.add(key)) {
            ProxyLogger.d("[$label] TCP fallback to $dst:$port skipped (backoff or connecting)")
            return@withContext false
        }

        val remote = Socket()
        try {
            try {
                ProxyLogger.i("[$label] TCP fallback to $dst:$port")
                remote.tcpNoDelay = true
                runCatching {
                    remote.receiveBufferSize = bufferSize
                    remote.sendBufferSize = bufferSize
                }
                remote.connect(InetSocketAddress(dst, port), 10_000)
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
            bridgeTcpReencrypt(stream, remote, label, ctx)
            true
        } finally {
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
    ) = withContext(Dispatchers.IO) {
        val dcTag = if (dc != null) "DC$dc${if (isMedia) "m" else ""}" else "DC?"
        var upBytes = 0L
        var downBytes = 0L
        var upPackets = 0L
        var downPackets = 0L
        val startTime = System.currentTimeMillis()
        val closeReason = AtomicReference("normal")

        try {
            coroutineScope {
                val tcpToWs = launch {
                    val buf = ByteArray(65536)
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
                            val chunk = if (n == buf.size) buf.copyOf() else buf.copyOfRange(0, n)
                            val plain = ctx.cltDec.update(chunk)
                            val encrypted = ctx.tgEnc.update(plain)
                            if (splitter != null) {
                                val parts = splitter.split(encrypted, plain)
                                if (parts.isEmpty()) continue
                                if (parts.size > 1) {
                                    ws.sendBatch(parts)
                                } else {
                                    ws.send(parts[0])
                                }
                            } else {
                                ws.send(encrypted)
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
                            val plain = ctx.tgDec.update(data)
                            val outData = ctx.cltEnc.update(plain)
                            stream.write(outData)
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
            }
        } finally {
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
    ) = withContext(Dispatchers.IO) {
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
                            val chunk = if (n == buf.size) buf.copyOf() else buf.copyOfRange(0, n)
                            val plain = ctx.cltDec.update(chunk)
                            val enc = ctx.tgEnc.update(plain)
                            remoteOut.write(enc)
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
                            val chunk = if (n == buf.size) buf.copyOf() else buf.copyOfRange(0, n)
                            val plain = ctx.tgDec.update(chunk)
                            val enc = ctx.cltEnc.update(plain)
                            stream.write(enc)
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
