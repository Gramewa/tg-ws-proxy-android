package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngineResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object FakeTls {
    const val TLS_RECORD_HANDSHAKE = 0x16
    const val TLS_RECORD_CCS = 0x14
    const val TLS_RECORD_APPDATA = 0x17

    val TLS_VERSION_12 = byteArrayOf(0x03, 0x03)
    const val CLIENT_RANDOM_OFFSET = 11
    const val CLIENT_RANDOM_LEN = 32
    const val SESSION_ID_OFFSET = 44
    const val SESSION_ID_LEN = 32
    const val TIMESTAMP_TOLERANCE_SEC = 120L
    const val TLS_APPDATA_MAX = 16384

    const val EXT_SUPPORTED_VERSIONS = 0x002b
    const val EXT_KEY_SHARE = 0x0033

    const val GROUP_X25519 = 0x001d
    const val GROUP_X25519MLKEM768 = 0x11ec

    const val X25519_KEY_LEN = 32
    const val X25519MLKEM768_CLIENT_SHARE_LEN = 1216
    const val MLKEM768_CIPHERTEXT_LEN = 1088

    val CIPHER_PREFERENCE = intArrayOf(0x1301, 0x1303, 0x1302)
    const val APPDATA_MIN = 64
    const val FALLBACK_APPDATA_MIN = 64
    const val FALLBACK_APPDATA_MAX = 160

    const val SERVER_HELLO_DELAY_MIN_MS = 8L
    const val SERVER_HELLO_DELAY_MAX_MS = 24L
    const val PROFILE_PROBE_TIMEOUT_MS = 5000

    private val CCS_FRAME = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)
    private const val SH_RANDOM_OFF = 11

    const val REPLAY_WINDOW_MS = (2 * TIMESTAMP_TOLERANCE_SEC + 1) * 1000L
    const val REPLAY_CACHE_SIZE = 65536

    private val random = SecureRandom()

    @Volatile
    private var profiledAppdataLen: Int? = null

    data class ClientHelloVerified(
        val clientRandom: ByteArray,
        val sessionId: ByteArray,
        val timestamp: Long,
    )

    data class ClientHelloInfo(
        val cipherSuites: Set<Int> = emptySet(),
        val keyShares: Map<Int, Int> = emptyMap(),
    )

    class ReplayCache(
        private val windowMs: Long = REPLAY_WINDOW_MS,
        private val maxSize: Int = REPLAY_CACHE_SIZE,
    ) {
        private class ByteKey(val bytes: ByteArray) {
            override fun equals(other: Any?): Boolean = other is ByteKey && bytes.contentEquals(other.bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
        }

        private val lock = Any()
        private val expiry = LinkedHashMap<ByteKey, Long>(128, 0.75f, false)

        fun firstSeen(clientRandom: ByteArray, nowMs: Long = ProxyClock.monotonicMs()): Boolean {
            val key = ByteKey(clientRandom.copyOf())
            synchronized(lock) {
                val it = expiry.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    if (entry.value <= nowMs) {
                        it.remove()
                    } else {
                        break
                    }
                }
                if (expiry.containsKey(key)) return false
                if (expiry.size >= maxSize) {
                    val oldest = expiry.entries.iterator()
                    if (oldest.hasNext()) {
                        oldest.next()
                        oldest.remove()
                    }
                }
                expiry[key] = nowMs + windowMs
                return true
            }
        }

        fun clear() {
            synchronized(lock) { expiry.clear() }
        }
    }

    val replayCache = ReplayCache()

    fun setProfiledAppdataLen(length: Int?) {
        profiledAppdataLen = length?.let { max(APPDATA_MIN, min(it, TLS_APPDATA_MAX)) }
    }

    fun getProfiledAppdataLen(): Int? = profiledAppdataLen

    fun verifyClientHello(
        data: ByteArray,
        secret: ByteArray,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): ClientHelloVerified? {
        val n = data.size
        if (n < 43) return null
        if ((data[0].toInt() and 0xFF) != TLS_RECORD_HANDSHAKE) return null
        if ((data[5].toInt() and 0xFF) != 0x01) return null

        val clientRandom = data.copyOfRange(CLIENT_RANDOM_OFFSET, CLIENT_RANDOM_OFFSET + CLIENT_RANDOM_LEN)
        val zeroed = data.copyOf()
        zeroed.fill(0, CLIENT_RANDOM_OFFSET, CLIENT_RANDOM_OFFSET + CLIENT_RANDOM_LEN)

        val expected = hmacSha256(secret, zeroed)
        if (!MessageDigest.isEqual(expected.copyOfRange(0, 28), clientRandom.copyOfRange(0, 28))) {
            return null
        }

        val b0 = (clientRandom[28].toInt() xor expected[28].toInt()) and 0xFF
        val b1 = (clientRandom[29].toInt() xor expected[29].toInt()) and 0xFF
        val b2 = (clientRandom[30].toInt() xor expected[30].toInt()) and 0xFF
        val b3 = (clientRandom[31].toInt() xor expected[31].toInt()) and 0xFF
        val timestamp = (b0.toLong() or (b1.toLong() shl 8) or (b2.toLong() shl 16) or (b3.toLong() shl 24))

        if (abs(nowSec - timestamp) > TIMESTAMP_TOLERANCE_SEC) {
            return null
        }

        val sessionId = if (n >= SESSION_ID_OFFSET + SESSION_ID_LEN && (data[43].toInt() and 0xFF) == 0x20) {
            data.copyOfRange(SESSION_ID_OFFSET, SESSION_ID_OFFSET + SESSION_ID_LEN)
        } else {
            ByteArray(SESSION_ID_LEN)
        }

        return ClientHelloVerified(clientRandom, sessionId, timestamp)
    }

    fun parseClientHello(data: ByteArray): ClientHelloInfo {
        return try {
            var pos = 5 + 4 + 2 + CLIENT_RANDOM_LEN
            val sessLen = data[pos].toInt() and 0xFF
            pos += 1 + sessLen

            val suitesLen = readU16BE(data, pos)
            pos += 2
            val suitesEnd = pos + suitesLen
            if (suitesEnd > data.size) return ClientHelloInfo()
            val suites = HashSet<Int>()
            var off = pos
            while (off + 2 <= suitesEnd) {
                suites.add(readU16BE(data, off))
                off += 2
            }
            pos = suitesEnd

            val compLen = data[pos].toInt() and 0xFF
            pos += 1 + compLen

            val extTotal = readU16BE(data, pos)
            pos += 2
            val extEnd = min(pos + extTotal, data.size)
            val keyShares = LinkedHashMap<Int, Int>()
            while (pos + 4 <= extEnd) {
                val extType = readU16BE(data, pos)
                val extLen = readU16BE(data, pos + 2)
                pos += 4
                if (extType == EXT_KEY_SHARE && pos + 2 <= extEnd) {
                    val sharesEnd = min(pos + 2 + readU16BE(data, pos), extEnd)
                    var sOff = pos + 2
                    while (sOff + 4 <= sharesEnd) {
                        val group = readU16BE(data, sOff)
                        val keyLen = readU16BE(data, sOff + 2)
                        sOff += 4 + keyLen
                        if (sOff <= sharesEnd && !keyShares.containsKey(group)) {
                            keyShares[group] = keyLen
                        }
                    }
                }
                pos += extLen
            }
            ClientHelloInfo(suites, keyShares)
        } catch (_: Exception) {
            ClientHelloInfo()
        }
    }

    private fun selectCipherSuite(info: ClientHelloInfo): Int {
        for (suite in CIPHER_PREFERENCE) {
            if (suite in info.cipherSuites) return suite
        }
        return CIPHER_PREFERENCE[0]
    }

    private fun generateX25519PublicKey(): ByteArray {
        try {
            val kpg = KeyPairGenerator.getInstance("X25519")
            val encoded = kpg.generateKeyPair().public.encoded
            if (encoded.size >= X25519_KEY_LEN) {
                return encoded.copyOfRange(encoded.size - X25519_KEY_LEN, encoded.size)
            }
        } catch (_: Exception) {
        }
        val key = ByteArray(X25519_KEY_LEN)
        random.nextBytes(key)
        key[key.lastIndex] = (key[key.lastIndex].toInt() and 0x7F).toByte()
        return key
    }

    private fun serverKeyShare(info: ClientHelloInfo): Pair<Int, ByteArray> {
        if (info.keyShares[GROUP_X25519MLKEM768] == X25519MLKEM768_CLIENT_SHARE_LEN) {
            val ct = ByteArray(MLKEM768_CIPHERTEXT_LEN).also { random.nextBytes(it) }
            val pk = generateX25519PublicKey()
            return GROUP_X25519MLKEM768 to (ct + pk)
        }
        return GROUP_X25519 to generateX25519PublicKey()
    }

    private fun appdataLen(): Int {
        profiledAppdataLen?.let { return it }
        return FALLBACK_APPDATA_MIN + random.nextInt(FALLBACK_APPDATA_MAX - FALLBACK_APPDATA_MIN + 1)
    }

    fun buildServerHello(
        secret: ByteArray,
        clientRandom: ByteArray,
        sessionId: ByteArray,
        info: ClientHelloInfo = ClientHelloInfo(),
    ): ByteArray {
        val (group, keyExchange) = serverKeyShare(info)

        val extOut = ByteArrayOutputStream()
        writeU16BE(extOut, EXT_SUPPORTED_VERSIONS)
        writeU16BE(extOut, 2)
        writeU16BE(extOut, 0x0304)
        writeU16BE(extOut, EXT_KEY_SHARE)
        writeU16BE(extOut, 4 + keyExchange.size)
        writeU16BE(extOut, group)
        writeU16BE(extOut, keyExchange.size)
        extOut.write(keyExchange)
        val extensions = extOut.toByteArray()

        val bodyOut = ByteArrayOutputStream()
        bodyOut.write(TLS_VERSION_12)
        bodyOut.write(ByteArray(32)) // placeholder server_random
        bodyOut.write(sessionId.size)
        bodyOut.write(sessionId)
        writeU16BE(bodyOut, selectCipherSuite(info))
        bodyOut.write(0x00)
        writeU16BE(bodyOut, extensions.size)
        bodyOut.write(extensions)
        val body = bodyOut.toByteArray()

        val msgOut = ByteArrayOutputStream()
        msgOut.write(0x02)
        msgOut.write((body.size ushr 16) and 0xFF)
        msgOut.write((body.size ushr 8) and 0xFF)
        msgOut.write(body.size and 0xFF)
        msgOut.write(body)
        val message = msgOut.toByteArray()

        val shOut = ByteArrayOutputStream()
        shOut.write(TLS_RECORD_HANDSHAKE)
        shOut.write(TLS_VERSION_12)
        writeU16BE(shOut, message.size)
        shOut.write(message)
        val serverHello = shOut.toByteArray()

        val appLen = appdataLen()
        val appPayload = ByteArray(appLen).also { random.nextBytes(it) }
        val appOut = ByteArrayOutputStream(5 + appLen)
        appOut.write(TLS_RECORD_APPDATA)
        appOut.write(TLS_VERSION_12)
        writeU16BE(appOut, appLen)
        appOut.write(appPayload)
        val appRecord = appOut.toByteArray()

        val response = serverHello + CCS_FRAME + appRecord
        val hmacInput = clientRandom + response
        val serverRandom = hmacSha256(secret, hmacInput)

        val finalBytes = response.copyOf()
        System.arraycopy(serverRandom, 0, finalBytes, SH_RANDOM_OFF, 32)
        return finalBytes
    }

    fun firstAppdataRecordLen(data: ByteArray): Int? {
        var pos = 0
        while (pos + 5 <= data.size) {
            val rtype = data[pos].toInt() and 0xFF
            val recLen = readU16BE(data, pos + 3)
            if (rtype == TLS_RECORD_APPDATA) return recLen
            if (rtype != TLS_RECORD_HANDSHAKE && rtype != TLS_RECORD_CCS) return 0
            pos += 5 + recLen
        }
        return null
    }

    fun isPublicUnicastAddress(addr: InetAddress): Boolean {
        return !addr.isAnyLocalAddress &&
            !addr.isLoopbackAddress &&
            !addr.isLinkLocalAddress &&
            !addr.isSiteLocalAddress &&
            !addr.isMulticastAddress
    }

    private fun resolveSafePublicAddress(domain: String): InetAddress? {
        if (!isValidDomain(domain)) return null
        val addrs = try {
            InetAddress.getAllByName(domain)
        } catch (_: Exception) {
            return null
        }
        if (addrs.isEmpty() || addrs.any { !isPublicUnicastAddress(it) }) {
            return null
        }
        return addrs.firstOrNull()
    }

    suspend fun probeAppdataLen(
        domain: String,
        port: Int = 443,
        timeoutMs: Int = PROFILE_PROBE_TIMEOUT_MS,
    ): Int? = withContext(Dispatchers.IO) {
        var sock: Socket? = null
        try {
            val sslCtx = SSLContext.getDefault()
            val engine = sslCtx.createSSLEngine(domain, port).apply {
                useClientMode = true
                enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" }.toTypedArray()
                val params = sslParameters
                params.serverNames = listOf(SNIHostName(domain))
                runCatching { params.applicationProtocols = arrayOf("h2", "http/1.1") }
                sslParameters = params
            }
            engine.beginHandshake()
            val netOut = ByteBuffer.allocate(engine.session.packetBufferSize.coerceAtLeast(16384))
            val appEmpty = ByteBuffer.allocate(0)
            var clientHello: ByteArray? = null
            for (i in 0 until 6) {
                netOut.clear()
                val res = engine.wrap(appEmpty, netOut)
                netOut.flip()
                if (netOut.hasRemaining()) {
                    clientHello = ByteArray(netOut.remaining()).also { netOut.get(it) }
                    break
                }
                if (res.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    var task = engine.delegatedTask
                    while (task != null) {
                        task.run()
                        task = engine.delegatedTask
                    }
                }
            }
            if (clientHello == null || clientHello.isEmpty()) return@withContext null

            val safeAddr = resolveSafePublicAddress(domain) ?: run {
                ProxyLogger.w("Fake TLS profile: blocked or unresolvable domain $domain")
                return@withContext null
            }

            val deadline = ProxyClock.monotonicMs() + timeoutMs
            sock = Socket()
            sock.connect(InetSocketAddress(safeAddr, port), timeoutMs)
            sock.soTimeout = timeoutMs
            sock.getOutputStream().apply {
                write(clientHello)
                flush()
            }

            val input = sock.getInputStream()
            val flight = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            var result: Int? = null
            while (true) {
                val rem = (deadline - ProxyClock.monotonicMs()).toInt()
                if (rem <= 0) break
                sock.soTimeout = rem
                val n = input.read(buf)
                if (n <= 0) break
                flight.write(buf, 0, n)
                val len = firstAppdataRecordLen(flight.toByteArray())
                if (len != null) {
                    result = if (len == 0) null else len
                    break
                }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (exc: Exception) {
            ProxyLogger.w("Fake TLS profile: cannot probe $domain:$port: ${exc.javaClass.simpleName}: ${exc.message}")
            null
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    fun wrapTlsRecord(data: ByteArray, off: Int = 0, len: Int = data.size - off): ByteArray {
        if (len <= 0) return ByteArray(0)
        val numRecords = (len + TLS_APPDATA_MAX - 1) / TLS_APPDATA_MAX
        val out = ByteArray(len + numRecords * 5)
        var srcPos = off
        var dstPos = 0
        var remaining = len
        while (remaining > 0) {
            val chunkLen = min(TLS_APPDATA_MAX, remaining)
            out[dstPos++] = TLS_RECORD_APPDATA.toByte()
            out[dstPos++] = 0x03
            out[dstPos++] = 0x03
            out[dstPos++] = ((chunkLen ushr 8) and 0xFF).toByte()
            out[dstPos++] = (chunkLen and 0xFF).toByte()
            System.arraycopy(data, srcPos, out, dstPos, chunkLen)
            srcPos += chunkLen
            dstPos += chunkLen
            remaining -= chunkLen
        }
        return out
    }

    suspend fun proxyToMaskingDomain(
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        initialData: ByteArray,
        domain: String,
        label: String,
    ) = withContext(Dispatchers.IO) {
        val safeAddr = resolveSafePublicAddress(domain)
        if (safeAddr == null) {
            ProxyLogger.w("[$label] masking: blocked or unresolvable domain $domain")
            try { clientSocket.close() } catch (_: Exception) {}
            return@withContext
        }
        val upstream = Socket()
        try {
            upstream.connect(InetSocketAddress(safeAddr, 443), 10_000)
            upstream.soTimeout = 30_000
            clientSocket.soTimeout = 30_000
        } catch (e: CancellationException) {
            throw e
        } catch (exc: Exception) {
            ProxyLogger.w("[$label] masking: cannot connect to $domain:443: ${exc.javaClass.simpleName}: ${exc.message}")
            try { upstream.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
            return@withContext
        }

        ProxyLogger.d("[$label] masking -> $domain:443")
        ProxyStats.connectionsMasked.incrementAndGet()

        try {
            val upOut = upstream.getOutputStream()
            val upIn = upstream.getInputStream()
            if (initialData.isNotEmpty()) {
                upOut.write(initialData)
                upOut.flush()
            }

            coroutineScope {
                val lastActivityMs = AtomicLong(ProxyClock.monotonicMs())
                val idleWatchdog = launch {
                    while (isActive) {
                        val idleMs = ProxyClock.monotonicMs() - lastActivityMs.get()
                        if (idleMs >= 30_000L) {
                            try { upstream.close() } catch (_: Exception) {}
                            try { clientSocket.close() } catch (_: Exception) {}
                            break
                        }
                        delay((30_000L - idleMs).coerceAtLeast(250L))
                    }
                }
                val t1 = launch {
                    val buf = ByteArray(16384)
                    try {
                        while (true) {
                            val n = clientInput.read(buf)
                            if (n <= 0) break
                            lastActivityMs.set(ProxyClock.monotonicMs())
                            upOut.write(buf, 0, n)
                            upOut.flush()
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { upstream.shutdownOutput() } catch (_: Exception) {}
                    }
                }
                val t2 = launch {
                    val buf = ByteArray(16384)
                    try {
                        while (true) {
                            val n = upIn.read(buf)
                            if (n <= 0) break
                            lastActivityMs.set(ProxyClock.monotonicMs())
                            clientOutput.write(buf, 0, n)
                            clientOutput.flush()
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { clientSocket.shutdownOutput() } catch (_: Exception) {}
                    }
                }
                t1.join()
                t2.join()
                idleWatchdog.cancel()
            }
        } catch (_: Exception) {
        } finally {
            try { upstream.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun readU16BE(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun writeU16BE(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}

class FakeTlsStream(
    private val rawInput: DataInputStream,
    private val rawOutput: OutputStream,
    private val rawSocket: Socket,
) {
    private val readLock = Any()
    private val writeLock = Any()
    private val hdrBuf = ByteArray(5)
    private var readLeft = 0

    fun readExactly(n: Int): ByteArray = synchronized(readLock) {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = readInto(out, off, n - off)
            if (r <= 0) {
                throw EOFException("Incomplete FakeTLS read: wanted $n, got $off")
            }
            off += r
        }
        out
    }

    fun read(dst: ByteArray): Int = synchronized(readLock) {
        readInto(dst, 0, dst.size)
    }

    private fun readInto(dst: ByteArray, dstOff: Int, dstLen: Int): Int {
        if (dstLen <= 0) return 0
        if (readLeft > 0) {
            val n = rawInput.read(dst, dstOff, min(dstLen, readLeft))
            if (n <= 0) return -1
            readLeft -= n
            return n
        }

        while (true) {
            try {
                rawInput.readFully(hdrBuf)
            } catch (_: EOFException) {
                return -1
            }
            val rtype = hdrBuf[0].toInt() and 0xFF
            val recLen = ((hdrBuf[3].toInt() and 0xFF) shl 8) or (hdrBuf[4].toInt() and 0xFF)

            if (rtype == FakeTls.TLS_RECORD_CCS) {
                if (recLen > 64) {
                    return -1
                }
                if (recLen > 0) {
                    var rem = recLen
                    while (rem > 0) {
                        val skipped = rawInput.skipBytes(rem)
                        if (skipped <= 0) {
                            // fallback if stream doesn't support skip
                            rawInput.readUnsignedByte()
                            rem--
                        } else {
                            rem -= skipped
                        }
                    }
                }
                continue
            }

            if (rtype != FakeTls.TLS_RECORD_APPDATA || recLen > FakeTls.TLS_APPDATA_MAX + 2048) {
                return -1
            }

            if (recLen == 0) continue
            val n = rawInput.read(dst, dstOff, min(dstLen, recLen))
            if (n <= 0) return -1
            readLeft = recLen - n
            return n
        }
    }

    fun write(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        if (len <= 0) return
        val wrapped = FakeTls.wrapTlsRecord(data, off, len)
        synchronized(writeLock) {
            rawOutput.write(wrapped)
            rawOutput.flush()
        }
    }

    fun close() {
        try { rawSocket.close() } catch (_: Exception) {}
    }
}
