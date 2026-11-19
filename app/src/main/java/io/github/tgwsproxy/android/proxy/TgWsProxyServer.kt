package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

class TgWsProxyServer {
    companion object {
        private const val BAD_HANDSHAKE_DRAIN_LIMIT = 64 * 1024
        private const val MAX_CONCURRENT_CLIENTS = 256
        private val random = SecureRandom()
    }

    private val lastClientActivityMs = AtomicLong(System.currentTimeMillis())
    private val sessionGeneration = AtomicLong(0L)
    private val isMetered = AtomicBoolean(false)
    private val isOffline = AtomicBoolean(false)

    private val balancer = DomainBalancer()
    private val cfRefresher = CfDomainRefresher(balancer)
    private val configRef = AtomicReference(ProxyConfig())

    private val idleHibernatingCheck: () -> Boolean = {
        System.currentTimeMillis() - lastClientActivityMs.get() > 180_000L
    }
    private val wsPool = WsPool(
        configProvider = { configRef.get() },
        isIdleHibernating = idleHibernatingCheck,
        isMeteredProvider = { isMetered.get() },
        isOfflineProvider = { isOffline.get() },
    )
    private val cfProxyPool = CfProxyWsPool(
        configProvider = { configRef.get() },
        balancer = balancer,
        isIdleHibernating = idleHibernatingCheck,
        isMeteredProvider = { isMetered.get() },
        isOfflineProvider = { isOffline.get() },
    )
    private val cfWorkerPool = CfWorkerPool(
        configProvider = { configRef.get() },
        isIdleHibernating = idleHibernatingCheck,
        isMeteredProvider = { isMetered.get() },
        isOfflineProvider = { isOffline.get() },
    )

    @Volatile private var h2Pool: CfH2Pool? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var socketDispatcher: ExecutorCoroutineDispatcher? = null
    private var serverScope: CoroutineScope? = null
    private val clientJobs = ConcurrentHashMap.newKeySet<Job>()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    var isRunning: Boolean = false
        private set

    fun setNetworkMetered(metered: Boolean) {
        isMetered.set(metered)
    }

    fun onNetworkLost() {
        if (!isRunning) return
        if (isOffline.compareAndSet(false, true)) {
            ProxyLogger.i("Network lost: pausing background pool refills")
        }
    }

    fun applyDiagnosticWinner(domain: String, latencyMs: Long) {
        val clean = domain.trim().lowercase()
        if (clean.isEmpty()) return
        balancer.applyProbeWinner(clean, latencyMs)
        ProxyLogger.i("Applied diagnostic winner domain (${latencyMs}ms)")
    }

    fun start(
        config: ProxyConfig,
        onDomainsPersisted: (List<String>) -> Unit = {},
    ) {
        if (isRunning) {
            stop()
        }

        val gen = sessionGeneration.incrementAndGet()
        isOffline.set(false)
        lastClientActivityMs.set(System.currentTimeMillis())
        ProxyStats.reset()
        ProxyBridge.resetTcpBackoff()
        DnsResolver.clearCache()
        FakeTls.setProfiledAppdataLen(null)
        ProxyLogger.setVerbose(config.verbose)
        configRef.set(config)

        val secretBytes = hexToBytes(config.secret)
        val threadSeq = AtomicInteger(0)
        val dedicatedDispatcher = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "tg-proxy-${threadSeq.incrementAndGet()}").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()
        socketDispatcher = dedicatedDispatcher

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope

        wsPool.attachScope(scope)
        cfProxyPool.attachScope(scope)
        cfWorkerPool.attachScope(scope)

        val h2 = if (config.h2Enabled) CfH2Pool(balancer) { configRef.get() } else null
        h2Pool = h2

        // Bind ServerSocket synchronously with SO_REUSEADDR so rapid restarts succeed cleanly.
        val addr = InetAddress.getByName(config.host)
        val boundSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(addr, config.port), 128)
        }
        serverSocket = boundSocket
        isRunning = true

        cfRefresher.start(
            scope = scope,
            userDomains = config.cfproxyUserDomains,
            cachedDomains = config.cachedCfDomains,
            onDomainsPersisted = onDomainsPersisted,
        )

        val maskedSecret = if (config.secret.length >= 8) {
            "${config.secret.take(4)}...${config.secret.takeLast(4)}"
        } else {
            "***"
        }
        val linkPreview = "tg://proxy?server=${getLinkHost(config.host)}&port=${config.port}&secret=***"
        ProxyLogger.i("============================================================")
        ProxyLogger.i("  Telegram MTProto WS Bridge Proxy (Android)")
        ProxyLogger.i("  Listening on   ${config.host}:${config.port}")
        ProxyLogger.i("  Secret:        $maskedSecret")
        if (config.fakeTlsDomain.isNotBlank()) {
            ProxyLogger.i("  Fake TLS:      ${config.fakeTlsDomain}")
        }
        ProxyLogger.i("  Target DC IPs:")
        for (dc in config.dcRedirects.keys.sorted()) {
            ProxyLogger.i("    DC$dc: ${config.dcRedirects[dc]}")
        }
        if (config.fallbackCfproxy) {
            val cfDesc = if (config.cfproxyUserDomains.isNotEmpty()) {
                config.cfproxyUserDomains.joinToString(", ")
            } else {
                "auto"
            }
            ProxyLogger.i("  CF proxy:      enabled ($cfDesc)")
        }
        if (config.cfproxyWorkerDomains.isNotEmpty()) {
            ProxyLogger.i("  CF worker:     enabled (${config.cfproxyWorkerDomains.joinToString(", ")})")
        }
        if (h2 != null) {
            ProxyLogger.i("  CF H2 media:   enabled")
        }
        if (config.disableSecure) {
            ProxyLogger.i("  No secure:     enabled (port 80 for CF proxy/worker)")
        } else if (config.autoPort80Fallback) {
            ProxyLogger.i("  Auto :80:      enabled (fallback on TLS failure)")
        }
        if (config.effectiveCfCleanIp.isNotEmpty()) {
            ProxyLogger.i("  CF clean IP:   ${config.effectiveCfCleanIp}")
        }
        if (config.dohProvider != DnsResolver.DOH_SYSTEM) {
            ProxyLogger.i("  DNS (DoH):     ${config.dohProvider} (pref=${config.ipPreference})")
        }
        ProxyLogger.i("============================================================")
        ProxyLogger.i("  Connect: $linkPreview")
        ProxyLogger.i("============================================================")

        if (config.fakeTlsDomain.isNotBlank()) {
            scope.launch {
                val appdataLen = FakeTls.probeAppdataLen(config.fakeTlsDomain.trim())
                FakeTls.setProfiledAppdataLen(appdataLen)
                if (appdataLen == null) {
                    ProxyLogger.w("Fake TLS profile unavailable, ServerHello uses random record sizes")
                } else {
                    ProxyLogger.i("Fake TLS profile: first encrypted record $appdataLen bytes")
                }
            }
        }

        wsPool.warmup()
        cfWorkerPool.warmup()

        scope.launch {
            while (isActive) {
                delay(60_000L)
                ProxyLogger.i("stats: ${ProxyStats.summary()}")
                h2Pool?.logStats()
            }
        }

        scope.launch(dedicatedDispatcher) {
            try {
                while (isActive && !boundSocket.isClosed) {
                    val client = try {
                        boundSocket.accept()
                    } catch (e: IOException) {
                        if (!isActive || boundSocket.isClosed) break
                        ProxyLogger.w("Accept error: ${e.message}")
                        delay(200)
                        continue
                    }
                    if (clientJobs.size >= MAX_CONCURRENT_CLIENTS) {
                        ProxyLogger.w("Too many concurrent clients (${clientJobs.size}); rejecting connection")
                        runCatching { client.close() }
                        continue
                    }
                    activeSockets.add(client)
                    lateinit var cJob: Job
                    cJob = scope.launch(context = dedicatedDispatcher, start = CoroutineStart.LAZY) {
                        try {
                            handleClient(client, secretBytes, configRef.get(), gen, dedicatedDispatcher)
                        } finally {
                            activeSockets.remove(client)
                            clientJobs.remove(cJob)
                        }
                    }
                    clientJobs.add(cJob)
                    cJob.start()
                }
            } finally {
                isRunning = false
            }
        }
    }

    fun updateConfig(
        newConfig: ProxyConfig,
        onDomainsPersisted: (List<String>) -> Unit = {},
    ) {
        if (!isRunning) {
            configRef.set(newConfig)
            return
        }
        val prev = configRef.getAndSet(newConfig)
        ProxyLogger.setVerbose(newConfig.verbose)
        DnsResolver.clearCache()

        val scope = serverScope
        if (scope != null && prev.cfproxyUserDomains != newConfig.cfproxyUserDomains) {
            cfRefresher.start(
                scope = scope,
                userDomains = newConfig.cfproxyUserDomains,
                cachedDomains = newConfig.cachedCfDomains,
                onDomainsPersisted = onDomainsPersisted,
            )
        }

        if (newConfig.h2Enabled && h2Pool == null) {
            h2Pool = CfH2Pool(balancer) { configRef.get() }
        } else if (!newConfig.h2Enabled && h2Pool != null) {
            h2Pool?.close()
            h2Pool = null
        } else {
            h2Pool?.onConfigUpdated()
        }

        wsPool.onConfigUpdated()
        cfProxyPool.onConfigUpdated()
        cfWorkerPool.onConfigUpdated()
        ProxyLogger.i("Applied live configuration update (hot reload)")
    }

    fun onNetworkChanged() {
        if (!isRunning) return
        isOffline.set(false)
        ProxyLogger.i("Network change detected: closing stale sessions, clearing DNS & resetting pools")
        DnsResolver.clearCache()
        balancer.onNetworkChanged()
        ProxyBridge.resetTcpBackoff()

        // Drop active sockets stuck on the old network interface so Telegram reconnects immediately.
        val staleSockets = activeSockets.toList()
        staleSockets.forEach { runCatching { it.close() } }

        wsPool.onNetworkChanged()
        cfProxyPool.onNetworkChanged()
        cfWorkerPool.onNetworkChanged()
        h2Pool?.onNetworkChanged()
        cfRefresher.triggerRefreshIfStale()
    }

    fun stop() {
        isRunning = false
        sessionGeneration.incrementAndGet()
        cfRefresher.stop()
        wsPool.reset()
        cfProxyPool.reset()
        cfWorkerPool.reset()
        h2Pool?.close()
        h2Pool = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null

        val socketsToClose = activeSockets.toList()
        activeSockets.clear()
        socketsToClose.forEach { runCatching { it.close() } }

        clientJobs.toList().forEach { it.cancel() }
        clientJobs.clear()
        serverScope?.cancel()
        serverScope = null

        val disp = socketDispatcher
        socketDispatcher = null
        runCatching { disp?.close() }

        ProxyLogger.i("Proxy stopped. Final stats: ${ProxyStats.summary()}")
    }

    private suspend fun handleClient(
        socket: Socket,
        secret: ByteArray,
        config: ProxyConfig,
        gen: Long,
        relayDispatcher: CoroutineDispatcher,
    ) {
        lastClientActivityMs.set(System.currentTimeMillis())
        ProxyStats.connectionsTotal.incrementAndGet()
        ProxyStats.connectionsActive.incrementAndGet()
        val remoteIp = socket.inetAddress?.hostAddress ?: "?"
        var label = "$remoteIp:${socket.port}"

        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                runCatching { socket.close() }
            }
        }

        try {
            socket.tcpNoDelay = true
            runCatching { socket.keepAlive = true }
            runCatching {
                socket.receiveBufferSize = config.bufferSize
                socket.sendBufferSize = config.bufferSize
            }
            socket.soTimeout = 10_000

            val bufferedIn = BufferedInputStream(socket.getInputStream(), 8192)
            val dataIn = DataInputStream(bufferedIn)
            val rawOut = socket.getOutputStream()

            val init = readClientInit(socket, dataIn, rawOut, secret, label, config) ?: return
            label = init.label
            val stream = init.stream
            val handshake = init.handshake

            val result = HandshakeCrypto.tryHandshake(handshake, secret)
            if (result == null) {
                ProxyStats.connectionsBad.incrementAndGet()
                ProxyLogger.w("[$label] bad handshake (wrong secret or proto)")
                drainBadHandshake(stream)
                return
            }

            // Disable 10s handshake read timeout for long-lived proxied session
            socket.soTimeout = 0

            var dc = result.dcId
            val isMedia = result.isMedia
            val protoTag = result.protoTag
            val protoInt = result.protoInt
            val isTestDc = config.forceTestDc || dc >= 10000
            if (dc >= 10000) {
                ProxyLogger.i("[$label] test DC$dc -> DC${dc - 10000}")
                dc -= 10000
            }

            val dcIdx = if (isMedia) -dc else dc
            val mediaTag = if (isMedia) " media" else ""
            ProxyLogger.d("[$label] handshake ok: DC$dc$mediaTag proto=0x%08X".format(protoInt))

            val relayInit = HandshakeCrypto.generateRelayInit(protoTag, dcIdx)
            val ctx = HandshakeCrypto.buildCryptoCtx(result.decPrekeyAndIv, secret, relayInit)
            val splitter = runCatching { MsgSplitter(protoInt) }.getOrNull()

            var directWs = wsPool.getOrConnect(dc, isMedia, isTestDc = isTestDc)
            if (directWs != null) {
                try {
                    directWs.send(relayInit)
                } catch (e: CancellationException) {
                    directWs.closeQuietly()
                    throw e
                } catch (exc: Exception) {
                    directWs.closeQuietly()
                    ProxyLogger.d("[$label] Stale direct WS for DC$dc$mediaTag (${exc.message}) -> falling back")
                    directWs = null
                }
            }

            if (directWs == null) {
                ProxyLogger.i("[$label] DC$dc$mediaTag direct WS unavailable -> fallback")
                val ok = ProxyBridge.doFallback(
                    stream = stream,
                    relayInit = relayInit,
                    label = label,
                    dc = dc,
                    isTestDc = isTestDc,
                    isMedia = isMedia,
                    ctx = ctx,
                    splitter = splitter,
                    config = configRef.get(),
                    balancer = balancer,
                    cfWorkerPool = cfWorkerPool,
                    h2Pool = h2Pool,
                    protoTag = protoTag,
                    cfProxyPool = cfProxyPool,
                    relayDispatcher = relayDispatcher,
                )
                if (ok) {
                    ProxyLogger.i("[$label] DC$dc$mediaTag fallback closed")
                } else {
                    ProxyLogger.w("[$label] DC$dc$mediaTag no fallback available")
                }
                return
            }

            ProxyLogger.i("[$label] DC$dc$mediaTag -> direct WS connected")
            ProxyStats.connectionsWs.incrementAndGet()
            ProxyBridge.bridgeWsReencrypt(
                stream = stream,
                ws = directWs,
                label = label,
                ctx = ctx,
                dc = dc,
                isMedia = isMedia,
                splitter = splitter,
                relayDispatcher = relayDispatcher,
            )
        } catch (e: CancellationException) {
            ProxyLogger.d("[$label] cancelled")
            throw e
        } catch (_: EOFException) {
            ProxyLogger.d("[$label] client disconnected")
        } catch (exc: Exception) {
            ProxyLogger.d("[$label] session ended: ${exc.javaClass.simpleName}: ${exc.message}")
        } finally {
            cancelHandle?.dispose()
            if (sessionGeneration.get() == gen) {
                ProxyStats.connectionsActive.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private data class ClientInit(
        val handshake: ByteArray,
        val stream: ClientStream,
        val label: String,
    )

    private suspend fun readClientInit(
        socket: Socket,
        input: DataInputStream,
        output: java.io.OutputStream,
        secret: ByteArray,
        initialLabel: String,
        config: ProxyConfig,
    ): ClientInit? {
        var label = initialLabel
        if (config.proxyProtocol) {
            val lineBytes = ByteArrayOutputStream(128)
            while (lineBytes.size() < 256) {
                val b = input.read()
                if (b == -1) {
                    ProxyLogger.d("[$label] disconnected during PROXY header")
                    return null
                }
                lineBytes.write(b)
                if (b == '\n'.code) break
            }
            val ppText = lineBytes.toString(Charsets.US_ASCII.name()).trim()
            if (ppText.startsWith("PROXY ")) {
                val parts = ppText.split(Regex("\\s+"))
                if (parts.size >= 6) {
                    label = "${parts[2]}:${parts[4]}"
                }
                ProxyLogger.d("[$label] PROXY protocol: $ppText")
            } else {
                ProxyLogger.d("[$label] expected PROXY header, got: ${ppText.take(60)}")
            }
        }

        val firstByte = try {
            input.readUnsignedByte()
        } catch (_: EOFException) {
            ProxyLogger.d("[$label] client disconnected before handshake")
            return null
        }

        val masking = config.fakeTlsDomain.trim()
        if (firstByte == FakeTls.TLS_RECORD_HANDSHAKE && masking.isNotEmpty()) {
            val hdrRest = ByteArray(4)
            try {
                input.readFully(hdrRest)
            } catch (_: EOFException) {
                ProxyLogger.d("[$label] incomplete TLS record header")
                return null
            }
            val tlsHeader = byteArrayOf(firstByte.toByte()) + hdrRest
            val recordLen = ((tlsHeader[3].toInt() and 0xFF) shl 8) or (tlsHeader[4].toInt() and 0xFF)
            if (recordLen !in 1..FakeTls.TLS_APPDATA_MAX) {
                ProxyLogger.d("[$label] invalid TLS record length $recordLen")
                return null
            }
            val recordBody = ByteArray(recordLen)
            try {
                input.readFully(recordBody)
            } catch (_: EOFException) {
                ProxyLogger.d("[$label] incomplete TLS record body")
                return null
            }
            val clientHello = tlsHeader + recordBody
            val tlsResult = FakeTls.verifyClientHello(clientHello, secret)
            val replayed = tlsResult != null && !FakeTls.replayCache.firstSeen(tlsResult.clientRandom)

            if (tlsResult == null || replayed) {
                ProxyLogger.d(
                    "[$label] Fake TLS ${if (replayed) "hello replayed" else "verify failed"} " +
                        "(size=${clientHello.size} rec=$recordLen) -> masking"
                )
                FakeTls.proxyToMaskingDomain(socket, input, output, clientHello, masking, label)
                return null
            }

            ProxyLogger.d("[$label] Fake TLS handshake ok (ts=${tlsResult.timestamp})")
            val serverHello = FakeTls.buildServerHello(
                secret = secret,
                clientRandom = tlsResult.clientRandom,
                sessionId = tlsResult.sessionId,
                info = FakeTls.parseClientHello(clientHello),
            )
            val delayMs = FakeTls.SERVER_HELLO_DELAY_MIN_MS +
                random.nextInt((FakeTls.SERVER_HELLO_DELAY_MAX_MS - FakeTls.SERVER_HELLO_DELAY_MIN_MS + 1).toInt())
            delay(delayMs)
            output.write(serverHello)
            output.flush()

            val tlsStream = FakeTlsStream(input, output, socket)
            val handshake = try {
                tlsStream.readExactly(HandshakeCrypto.HANDSHAKE_LEN)
            } catch (_: EOFException) {
                ProxyLogger.d("[$label] incomplete obfs2 init inside TLS")
                return null
            }
            return ClientInit(handshake, FakeTlsClientStream(tlsStream), label)
        } else if (masking.isNotEmpty()) {
            ProxyLogger.d("[$label] non-TLS byte 0x%02X -> HTTP redirect".format(firstByte))
            val redirect = (
                "HTTP/1.1 301 Moved Permanently\r\n" +
                    "Location: https://$masking/\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            output.write(redirect)
            output.flush()
            return null
        } else {
            val rest = ByteArray(HandshakeCrypto.HANDSHAKE_LEN - 1)
            try {
                input.readFully(rest)
            } catch (_: EOFException) {
                ProxyLogger.d("[$label] client disconnected before handshake")
                return null
            }
            val handshake = byteArrayOf(firstByte.toByte()) + rest
            return ClientInit(handshake, RawClientStream(socket, input, output), label)
        }
    }

    private fun drainBadHandshake(stream: ClientStream) {
        val buf = ByteArray(4096)
        var remaining = BAD_HANDSHAKE_DRAIN_LIMIT
        try {
            while (remaining > 0) {
                val n = stream.read(buf)
                if (n <= 0) break
                remaining -= n
            }
        } catch (_: Exception) {
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
