package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

class TcpConnectFailedException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class WsHandshakeError(
    val statusCode: Int,
    val statusLine: String,
    val headers: Map<String, String> = emptyMap(),
    val location: String? = null,
) : IOException("HTTP $statusCode: $statusLine") {
    val isRedirect: Boolean
        get() = statusCode in setOf(301, 302, 303, 307, 308)
}

class RawWebSocket private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
) {
    @Volatile
    var isClosed: Boolean = false
        private set

    @Volatile
    var lastRecvMs: Long = ProxyClock.monotonicMs()
        private set

    @Volatile
    var lastPongMs: Long = ProxyClock.monotonicMs()
        private set

    @Volatile
    var lastCloseCode: Int? = null
        private set

    private val writeMutex = Mutex()
    private val fragBuffer = ByteArrayOutputStream()

    fun isStale(): Boolean {
        return isClosed || socket.isClosed || socket.isInputShutdown || socket.isOutputShutdown
    }

    /**
     * Sends the initial 64-byte MTProto relay handshake with a hard timeout and immediately
     * closes the underlying socket on timeout, error, or cancellation (upstream v1.12.0 parity).
     */
    suspend fun sendInit(data: ByteArray, timeoutMs: Long = SEND_INIT_TIMEOUT_MS) {
        try {
            val completed = withTimeoutOrNull(timeoutMs) {
                val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) {
                        closeQuietly()
                    }
                }
                try {
                    send(data)
                    true
                } finally {
                    cancelHandle?.dispose()
                }
            }
            if (completed == null) {
                closeQuietly()
                throw SocketTimeoutException("WS init send timed out after ${timeoutMs}ms")
            }
        } catch (t: Throwable) {
            closeQuietly()
            throw t
        }
    }

    suspend fun send(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        if (len < 0) return
        if (isClosed) throw IOException("WebSocket closed")
        val frame = buildFrame(OP_BINARY, data, off, len, mask = true)
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                if (isClosed) throw IOException("WebSocket closed")
                output.write(frame)
                output.flush()
            }
        }
    }

    suspend fun sendBatch(parts: List<ByteArray>) {
        if (parts.isEmpty()) return
        if (parts.size == 1) {
            send(parts[0])
            return
        }
        if (isClosed) throw IOException("WebSocket closed")
        // Coalesce all frames into a single buffer so they are flushed in one syscall / TLS record.
        var totalSize = 0
        for (p in parts) {
            totalSize += frameSize(p.size, mask = true)
        }
        val combined = ByteArray(totalSize)
        var pos = 0
        for (p in parts) {
            pos = writeFrameInto(combined, pos, OP_BINARY, p, 0, p.size, mask = true)
        }
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                if (isClosed) throw IOException("WebSocket closed")
                output.write(combined)
                output.flush()
            }
        }
    }

    suspend fun ping(payload: ByteArray = ByteArray(0)) {
        if (isClosed) throw IOException("WebSocket closed")
        val frame = buildFrame(OP_PING, payload, 0, payload.size, mask = true)
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                if (isClosed) throw IOException("WebSocket closed")
                output.write(frame)
                output.flush()
            }
        }
    }

    suspend fun recv(): ByteArray? = withContext(Dispatchers.IO) {
        while (!isClosed) {
            val (opcode, payload, fin) = readFrame()
            lastRecvMs = ProxyClock.monotonicMs()
            when (opcode) {
                OP_CLOSE -> {
                    isClosed = true
                    val (code, reason) = parseClose(payload)
                    lastCloseCode = code
                    ProxyLogger.d("WS OP_CLOSE from upstream: code=$code reason=$reason")
                    try {
                        val closePayload = if (payload.size >= 2) payload.copyOfRange(0, 2) else ByteArray(0)
                        val frame = buildFrame(OP_CLOSE, closePayload, 0, closePayload.size, mask = true)
                        writeMutex.withLock {
                            output.write(frame)
                            output.flush()
                        }
                    } catch (_: Exception) {
                    }
                    return@withContext null
                }
                OP_PING -> {
                    try {
                        val pong = buildFrame(OP_PONG, payload, 0, payload.size, mask = true)
                        writeMutex.withLock {
                            if (!isClosed) {
                                output.write(pong)
                                output.flush()
                            }
                        }
                    } catch (_: Exception) {
                    }
                    continue
                }
                OP_PONG -> {
                    lastPongMs = lastRecvMs
                    continue
                }
                OP_CONT, OP_TEXT, OP_BINARY -> {
                    if (fin && fragBuffer.size() == 0) {
                        return@withContext payload
                    }
                    if (fragBuffer.size() + payload.size > MAX_MESSAGE_LEN) {
                        throw IOException("WS message too large: ${fragBuffer.size() + payload.size} bytes")
                    }
                    fragBuffer.write(payload)
                    if (!fin) continue
                    val message = fragBuffer.toByteArray()
                    fragBuffer.reset()
                    return@withContext message
                }
                else -> continue
            }
        }
        null
    }

    suspend fun close(timeoutMs: Long = CLOSE_TIMEOUT_MS) {
        val alreadyClosed = isClosed
        isClosed = true
        try {
            if (!alreadyClosed) {
                withTimeoutOrNull(timeoutMs) {
                    val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                        if (cause != null) {
                            closeQuietly()
                        }
                    }
                    try {
                        val frame = buildFrame(OP_CLOSE, ByteArray(0), 0, 0, mask = true)
                        writeMutex.withLock {
                            withContext(Dispatchers.IO) {
                                output.write(frame)
                                output.flush()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    } finally {
                        cancelHandle?.dispose()
                    }
                }
            }
        } finally {
            closeQuietly()
        }
    }

    fun closeQuietly() {
        isClosed = true
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun readFrame(): Triple<Int, ByteArray, Boolean> {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) {
            length = input.readUnsignedShort().toLong()
        } else if (length == 127L) {
            length = input.readLong()
        }
        if (length < 0 || length > MAX_MESSAGE_LEN) {
            throw IOException("WS frame too large: $length bytes")
        }
        val len = length.toInt()
        val maskKey = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(len)
        if (len > 0) {
            input.readFully(payload)
        }
        if (maskKey != null && len > 0) {
            xorMaskInPlace(payload, maskKey)
        }
        return Triple(opcode, payload, fin)
    }

    companion object {
        const val OP_CONT = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
        const val MAX_MESSAGE_LEN = 16 * 1024 * 1024
        const val SEND_INIT_TIMEOUT_MS = 10_000L
        const val CLOSE_TIMEOUT_MS = 2_000L

        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val sharedRandom = SecureRandom()

        private val WS_CLOSE_REASONS = mapOf(
            1000 to "normal",
            1001 to "going_away",
            1002 to "protocol_error",
            1003 to "unsupported_data",
            1006 to "abnormal",
            1007 to "bad_data",
            1008 to "policy_violation",
            1009 to "too_big",
            1010 to "missing_extension",
            1011 to "internal_error",
        )

        fun computeWebSocketAccept(wsKey: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1")
                .digest((wsKey + WS_GUID).toByteArray(Charsets.US_ASCII))
            return Base64.getEncoder().encodeToString(sha1)
        }

        private fun frameSize(len: Int, mask: Boolean): Int {
            return 2 + when {
                len < 126 -> 0
                len < 65536 -> 2
                else -> 8
            } + (if (mask) 4 else 0) + len
        }

        private fun buildFrame(opcode: Int, data: ByteArray, off: Int, len: Int, mask: Boolean): ByteArray {
            val out = ByteArray(frameSize(len, mask))
            writeFrameInto(out, 0, opcode, data, off, len, mask)
            return out
        }

        private fun writeFrameInto(
            out: ByteArray,
            startPos: Int,
            opcode: Int,
            data: ByteArray,
            off: Int,
            len: Int,
            mask: Boolean,
        ): Int {
            var pos = startPos
            out[pos++] = (0x80 or opcode).toByte()
            val maskBit = if (mask) 0x80 else 0
            when {
                len < 126 -> {
                    out[pos++] = (maskBit or len).toByte()
                }
                len < 65536 -> {
                    out[pos++] = (maskBit or 126).toByte()
                    out[pos++] = ((len ushr 8) and 0xFF).toByte()
                    out[pos++] = (len and 0xFF).toByte()
                }
                else -> {
                    out[pos++] = (maskBit or 127).toByte()
                    val l = len.toLong()
                    for (shift in 56 downTo 0 step 8) {
                        out[pos++] = ((l ushr shift) and 0xFF).toByte()
                    }
                }
            }
            if (mask) {
                val maskInt = sharedRandom.nextInt()
                val m0 = ((maskInt ushr 24) and 0xFF).toByte()
                val m1 = ((maskInt ushr 16) and 0xFF).toByte()
                val m2 = ((maskInt ushr 8) and 0xFF).toByte()
                val m3 = (maskInt and 0xFF).toByte()
                out[pos++] = m0
                out[pos++] = m1
                out[pos++] = m2
                out[pos++] = m3

                val m0i = m0.toInt()
                val m1i = m1.toInt()
                val m2i = m2.toInt()
                val m3i = m3.toInt()
                var i = 0
                val limit4 = len and -4
                while (i < limit4) {
                    val s = off + i
                    val d = pos + i
                    out[d] = (data[s].toInt() xor m0i).toByte()
                    out[d + 1] = (data[s + 1].toInt() xor m1i).toByte()
                    out[d + 2] = (data[s + 2].toInt() xor m2i).toByte()
                    out[d + 3] = (data[s + 3].toInt() xor m3i).toByte()
                    i += 4
                }
                while (i < len) {
                    val m = when (i and 3) {
                        0 -> m0i
                        1 -> m1i
                        2 -> m2i
                        else -> m3i
                    }
                    out[pos + i] = (data[off + i].toInt() xor m).toByte()
                    i++
                }
                pos += len
            } else if (len > 0) {
                System.arraycopy(data, off, out, pos, len)
                pos += len
            }
            return pos
        }

        private fun xorMaskInPlace(data: ByteArray, maskKey: ByteArray) {
            val m0 = maskKey[0].toInt()
            val m1 = maskKey[1].toInt()
            val m2 = maskKey[2].toInt()
            val m3 = maskKey[3].toInt()
            val len = data.size
            var i = 0
            val limit4 = len and -4
            while (i < limit4) {
                data[i] = (data[i].toInt() xor m0).toByte()
                data[i + 1] = (data[i + 1].toInt() xor m1).toByte()
                data[i + 2] = (data[i + 2].toInt() xor m2).toByte()
                data[i + 3] = (data[i + 3].toInt() xor m3).toByte()
                i += 4
            }
            while (i < len) {
                data[i] = (data[i].toInt() xor maskKey[i and 3].toInt()).toByte()
                i++
            }
        }

        private fun parseClose(payload: ByteArray): Pair<Int?, String> {
            if (payload.size < 2) return null to ""
            val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            val text = if (payload.size > 2) {
                String(payload, 2, payload.size - 2, Charsets.UTF_8)
            } else ""
            val name = WS_CLOSE_REASONS[code]
            val desc = if (name != null) "$text ($name)" else text
            return code to desc
        }

        suspend fun connect(
            host: String,
            domain: String,
            timeoutMs: Int = 10_000,
            path: String = WS_PATH,
            sni: String? = null,
            secure: Boolean = true,
            bufferSize: Int = 256 * 1024,
            dohProvider: String = DnsResolver.DOH_SYSTEM,
            ipPreference: String = DnsResolver.IP_PREF_AUTO,
            cleanIpOverride: String = "",
            portOverride: Int? = null,
        ): RawWebSocket = withContext(Dispatchers.IO) {
            val effectiveTimeout = timeoutMs.coerceIn(200, 10_000)
            val port = portOverride ?: if (secure) 443 else 80
            val deadlineMs = ProxyClock.monotonicMs() + effectiveTimeout

            fun remainingTimeoutMs(): Int {
                val rem = (deadlineMs - ProxyClock.monotonicMs()).toInt()
                if (rem <= 0) {
                    throw SocketTimeoutException("WS connect timed out after ${effectiveTimeout}ms")
                }
                return rem
            }

            fun logConnectFailure(stage: String, sock: Socket?, err: Throwable) {
                if (err is CancellationException || !ProxyLogger.isDebugEnabled) return
                val local = runCatching { sock?.localSocketAddress }.getOrNull()
                val remote = runCatching { sock?.remoteSocketAddress }.getOrNull()
                ProxyLogger.d(
                    "WS setup failed: host=$host port=$port stage=$stage local=$local remote=$remote " +
                        "error=${err.javaClass.simpleName}: ${err.message}"
                )
            }

            val addresses = try {
                DnsResolver.resolve(
                    host = host,
                    dohProvider = dohProvider,
                    ipPreference = ipPreference,
                    cleanIpOverride = cleanIpOverride,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logConnectFailure("dns", null, t)
                throw TcpConnectFailedException("DNS resolve failed for $host: ${t.message}", t)
            }

            val rawSocket = try {
                connectTcpHappyEyeballs(
                    addresses = addresses,
                    port = port,
                    timeoutMs = remainingTimeoutMs(),
                    bufferSize = bufferSize,
                )
            } catch (t: Throwable) {
                logConnectFailure("tcp", null, t)
                throw t
            }
            val activeSocketRef = AtomicReference<Socket>(rawSocket)
            var stage = if (secure) "tls" else "ws_upgrade"

            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    try { activeSocketRef.get().close() } catch (_: Exception) {}
                    try { rawSocket.close() } catch (_: Exception) {}
                }
            }

            try {
                rawSocket.soTimeout = remainingTimeoutMs()

                if (secure) {
                    stage = "tls"
                    val effectiveSni = sni ?: domain
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val sslSocket = factory.createSocket(rawSocket, effectiveSni, port, true) as SSLSocket
                    activeSocketRef.set(sslSocket)
                    val params = sslSocket.sslParameters
                    params.serverNames = listOf(SNIHostName(effectiveSni))
                    // API 29+ ALPN hint for HTTP/1.1 WebSocket upgrade; safe no-op on Android 8.0-9
                    runCatching { params.applicationProtocols = arrayOf("http/1.1") }
                    sslSocket.sslParameters = params
                    sslSocket.enabledProtocols = sslSocket.supportedProtocols
                        .filter { it == "TLSv1.3" || it == "TLSv1.2" }
                        .ifEmpty { sslSocket.enabledProtocols.toList() }
                        .toTypedArray()
                    sslSocket.soTimeout = remainingTimeoutMs()
                    sslSocket.startHandshake()

                    // Fronting explicitly disables hostname checking; regular TLS verifies `domain`.
                    if (sni == null) {
                        val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
                        if (!verifier.verify(domain, sslSocket.session)) {
                            throw SSLPeerUnverifiedException("Hostname mismatch for $domain")
                        }
                    }
                }

                stage = "ws_upgrade"
                val activeSocket = activeSocketRef.get()
                activeSocket.soTimeout = remainingTimeoutMs()

                val keyBytes = ByteArray(16).also { sharedRandom.nextBytes(it) }
                val wsKey = Base64.getEncoder().encodeToString(keyBytes)
                val expectedAccept = computeWebSocketAccept(wsKey)
                val effectivePath = path.ifEmpty { WS_PATH }
                val req = buildString(256) {
                    append("GET ").append(effectivePath).append(" HTTP/1.1\r\n")
                    append("Host: ").append(domain).append("\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Origin: https://web.telegram.org\r\n")
                    append("User-Agent: ").append(BROWSER_USER_AGENT).append("\r\n")
                    append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("Sec-WebSocket-Protocol: binary\r\n\r\n")
                }

                val out = activeSocket.getOutputStream()
                out.write(req.toByteArray(Charsets.US_ASCII))
                out.flush()

                val bufferedIn = BufferedInputStream(activeSocket.getInputStream(), 8192)
                val responseLines = ArrayList<String>(12)
                while (responseLines.size < 64) {
                    activeSocket.soTimeout = remainingTimeoutMs()
                    val line = readHttpLine(bufferedIn) ?: break
                    if (line.isEmpty()) break
                    responseLines.add(line)
                }

                if (responseLines.isEmpty()) {
                    throw WsHandshakeError(0, "empty response")
                }

                val firstLine = responseLines[0]
                val parts = firstLine.split(' ', limit = 3)
                val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0

                val headers = LinkedHashMap<String, String>(responseLines.size)
                for (i in 1 until responseLines.size) {
                    val hl = responseLines[i]
                    val idx = hl.indexOf(':')
                    if (idx > 0) {
                        val k = hl.substring(0, idx).trim().lowercase()
                        val v = hl.substring(idx + 1).trim()
                        headers[k] = v
                    }
                }

                if (statusCode == 101) {
                    val serverAccept = headers["sec-websocket-accept"]
                    if (serverAccept != null && serverAccept != expectedAccept) {
                        throw WsHandshakeError(101, "Invalid Sec-WebSocket-Accept", headers)
                    }
                    // Long-poll Telegram sessions stay idle; disable short handshake read timeout.
                    activeSocket.soTimeout = 0
                    return@withContext RawWebSocket(
                        socket = activeSocket,
                        input = DataInputStream(bufferedIn),
                        output = out,
                    )
                }

                throw WsHandshakeError(statusCode, firstLine, headers, headers["location"])
            } catch (t: Throwable) {
                logConnectFailure(stage, activeSocketRef.get(), t)
                try { activeSocketRef.get().close() } catch (_: Exception) {}
                try { rawSocket.close() } catch (_: Exception) {}
                throw t
            } finally {
                cancelHandle?.dispose()
            }
        }

        private suspend fun connectTcpHappyEyeballs(
            addresses: List<InetAddress>,
            port: Int,
            timeoutMs: Int,
            bufferSize: Int,
        ): Socket {
            if (addresses.size == 1) {
                return connectSingleTcp(addresses[0], port, timeoutMs, bufferSize)
            }
            val candidates = addresses.take(3)
            val winner = AtomicReference<Socket?>(null)
            val firstError = AtomicReference<Throwable?>(null)
            val done = CompletableDeferred<Socket?>()
            val remaining = AtomicInteger(candidates.size)
            val failedSignal = Channel<Unit>(Channel.CONFLATED)

            val resultSock = coroutineScope {
                val launcher = launch(Dispatchers.IO) {
                    for ((idx, addr) in candidates.withIndex()) {
                        if (winner.get() != null || !isActive) break
                        if (idx > 0) {
                            withTimeoutOrNull(300L) { failedSignal.receive() }
                            if (winner.get() != null || !isActive) break
                        }
                        launch {
                            try {
                                val s = connectSingleTcp(addr, port, timeoutMs, bufferSize)
                                if (winner.compareAndSet(null, s)) {
                                    if (!done.complete(s)) {
                                        try { s.close() } catch (_: Exception) {}
                                    }
                                } else {
                                    try { s.close() } catch (_: Exception) {}
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                firstError.compareAndSet(null, t)
                                failedSignal.trySend(Unit)
                                if (remaining.decrementAndGet() == 0) {
                                    done.complete(null)
                                }
                            }
                        }
                    }
                }

                val sock = try {
                    done.await()
                } catch (e: CancellationException) {
                    done.cancel()
                    launcher.cancel()
                    failedSignal.close()
                    winner.getAndSet(null)?.let { try { it.close() } catch (_: Exception) {} }
                    throw e
                }
                launcher.cancel()
                failedSignal.close()
                sock
            }
            if (resultSock != null) return resultSock
            val err = firstError.get()
            throw TcpConnectFailedException(err?.message ?: "TCP connect failed", err)
        }

        private suspend fun connectSingleTcp(
            addr: InetAddress,
            port: Int,
            timeoutMs: Int,
            bufferSize: Int,
        ): Socket = withContext(Dispatchers.IO) {
            val sock = Socket()
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    try { sock.close() } catch (_: Exception) {}
                }
            }
            try {
                sock.tcpNoDelay = true
                runCatching { sock.keepAlive = true }
                runCatching {
                    sock.receiveBufferSize = bufferSize
                    sock.sendBufferSize = bufferSize
                }
                sock.connect(InetSocketAddress(addr, port), timeoutMs)
                sock
            } catch (e: CancellationException) {
                try { sock.close() } catch (_: Exception) {}
                throw e
            } catch (t: Throwable) {
                try { sock.close() } catch (_: Exception) {}
                throw TcpConnectFailedException("TCP connect to ${addr.hostAddress}:$port failed: ${t.message}", t)
            } finally {
                cancelHandle?.dispose()
            }
        }

        internal fun readHttpLine(input: InputStream, maxLineBytes: Int = 8192): String? {
            val buf = ByteArrayOutputStream(128)
            var sawAny = false
            while (buf.size() < maxLineBytes) {
                val b = input.read()
                if (b == -1) {
                    return if (sawAny) buf.toString(Charsets.UTF_8.name()).trim() else null
                }
                sawAny = true
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
            }
            return buf.toString(Charsets.UTF_8.name()).trim()
        }
    }
}
