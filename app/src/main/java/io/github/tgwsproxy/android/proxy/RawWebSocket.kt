package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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

    private val writeMutex = Mutex()
    private val fragBuffer = ByteArrayOutputStream()
    private val random = SecureRandom()

    fun isStale(): Boolean {
        return isClosed || socket.isClosed || socket.isInputShutdown || socket.isOutputShutdown
    }

    suspend fun send(data: ByteArray) {
        if (isClosed) throw IOException("WebSocket closed")
        val frame = buildFrame(OP_BINARY, data, mask = true)
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
        if (isClosed) throw IOException("WebSocket closed")
        val frames = parts.map { buildFrame(OP_BINARY, it, mask = true) }
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                if (isClosed) throw IOException("WebSocket closed")
                for (frame in frames) {
                    output.write(frame)
                }
                output.flush()
            }
        }
    }

    suspend fun recv(): ByteArray? = withContext(Dispatchers.IO) {
        while (!isClosed) {
            val (opcode, payload, fin) = readFrame()
            when (opcode) {
                OP_CLOSE -> {
                    isClosed = true
                    val (code, reason) = parseClose(payload)
                    ProxyLogger.d("WS OP_CLOSE from upstream: code=$code reason=$reason")
                    try {
                        val closePayload = if (payload.size >= 2) payload.copyOfRange(0, 2) else ByteArray(0)
                        val frame = buildFrame(OP_CLOSE, closePayload, mask = true)
                        output.write(frame)
                        output.flush()
                    } catch (_: Exception) {
                    }
                    return@withContext null
                }
                OP_PING -> {
                    try {
                        val pong = buildFrame(OP_PONG, payload, mask = true)
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
                OP_PONG -> continue
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

    suspend fun close() {
        val alreadyClosed = isClosed
        isClosed = true
        withContext(Dispatchers.IO) {
            if (!alreadyClosed) {
                try {
                    val frame = buildFrame(OP_CLOSE, ByteArray(0), mask = true)
                    output.write(frame)
                    output.flush()
                } catch (_: Exception) {
                }
            }
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

    private fun buildFrame(opcode: Int, data: ByteArray, mask: Boolean): ByteArray {
        val len = data.size
        val fb = (0x80 or opcode).toByte()
        val headerSize = 2 + (when {
            len < 126 -> 0
            len < 65536 -> 2
            else -> 8
        }) + (if (mask) 4 else 0)

        val out = ByteArray(headerSize + len)
        var pos = 0
        out[pos++] = fb
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
            val maskKey = ByteArray(4)
            random.nextBytes(maskKey)
            System.arraycopy(maskKey, 0, out, pos, 4)
            pos += 4
            for (i in 0 until len) {
                out[pos + i] = (data[i].toInt() xor maskKey[i and 3].toInt()).toByte()
            }
        } else if (len > 0) {
            System.arraycopy(data, 0, out, pos, len)
        }
        return out
    }

    private fun xorMaskInPlace(data: ByteArray, maskKey: ByteArray) {
        for (i in data.indices) {
            data[i] = (data[i].toInt() xor maskKey[i and 3].toInt()).toByte()
        }
    }

    companion object {
        const val OP_CONT = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
        const val MAX_MESSAGE_LEN = 16 * 1024 * 1024

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
        ): RawWebSocket = withContext(Dispatchers.IO) {
            val effectiveTimeout = timeoutMs.coerceIn(1000, 10_000)
            val port = if (secure) 443 else 80
            val rawSocket = Socket()
            var activeSocket: Socket = rawSocket

            try {
                rawSocket.tcpNoDelay = true
                runCatching {
                    rawSocket.receiveBufferSize = bufferSize
                    rawSocket.sendBufferSize = bufferSize
                }
                rawSocket.connect(InetSocketAddress(host, port), effectiveTimeout)
                rawSocket.soTimeout = timeoutMs.coerceAtLeast(1000)

                if (secure) {
                    val effectiveSni = sni ?: domain
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val sslSocket = factory.createSocket(rawSocket, effectiveSni, 443, true) as SSLSocket
                    activeSocket = sslSocket
                    val params = sslSocket.sslParameters
                    params.serverNames = listOf(SNIHostName(effectiveSni))
                    sslSocket.sslParameters = params
                    sslSocket.enabledProtocols = sslSocket.supportedProtocols
                        .filter { it == "TLSv1.3" || it == "TLSv1.2" }
                        .ifEmpty { sslSocket.enabledProtocols.toList() }
                        .toTypedArray()
                    sslSocket.startHandshake()

                    // Fronting explicitly disables hostname checking; regular TLS verifies `domain`.
                    if (sni == null) {
                        val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
                        if (!verifier.verify(domain, sslSocket.session)) {
                            throw SSLPeerUnverifiedException("Hostname mismatch for $domain")
                        }
                    }
                }

                val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val wsKey = Base64.getEncoder().encodeToString(keyBytes)
                val effectivePath = path.ifEmpty { WS_PATH }
                val req = buildString {
                    append("GET ").append(effectivePath).append(" HTTP/1.1\r\n")
                    append("Host: ").append(domain).append("\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("Sec-WebSocket-Protocol: binary\r\n\r\n")
                }

                val out = activeSocket.getOutputStream()
                out.write(req.toByteArray(Charsets.US_ASCII))
                out.flush()

                val bufferedIn = BufferedInputStream(activeSocket.getInputStream(), 8192)
                val responseLines = ArrayList<String>()
                while (true) {
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

                if (statusCode == 101) {
                    // Long-poll Telegram sessions stay idle; disable short handshake read timeout.
                    activeSocket.soTimeout = 0
                    return@withContext RawWebSocket(
                        socket = activeSocket,
                        input = DataInputStream(bufferedIn),
                        output = out,
                    )
                }

                val headers = LinkedHashMap<String, String>()
                for (i in 1 until responseLines.size) {
                    val hl = responseLines[i]
                    val idx = hl.indexOf(':')
                    if (idx > 0) {
                        val k = hl.substring(0, idx).trim().lowercase()
                        val v = hl.substring(idx + 1).trim()
                        headers[k] = v
                    }
                }
                throw WsHandshakeError(statusCode, firstLine, headers, headers["location"])
            } catch (t: Throwable) {
                try { activeSocket.close() } catch (_: Exception) {}
                try { rawSocket.close() } catch (_: Exception) {}
                throw t
            }
        }

        private fun readHttpLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream(128)
            var sawAny = false
            while (true) {
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
