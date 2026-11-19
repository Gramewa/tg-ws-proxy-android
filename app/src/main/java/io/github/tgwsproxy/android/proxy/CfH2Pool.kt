package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException

class MtProtoTransportError(
    val code: Int,
    source: String,
) : IOException("$code ($source)")

class CfH2Pool(
    private val balancer: DomainBalancer,
    private val configProvider: () -> ProxyConfig = { ProxyConfig() },
) {
    companion object {
        const val MAX_PACKET = 4 * 1024 * 1024
        const val MAX_PLAIN_PADDING = 256
        const val MAX_CHANNEL_REQUESTS = 8
        const val MAX_CHANNEL_BYTES = 8 * 1024 * 1024
        const val MAX_LANE_REQUESTS = 64
        const val MAX_LANE_BYTES = 32 * 1024 * 1024
        const val DOMAIN_COOLDOWN_MS = 30_000L
        const val REQUEST_TIMEOUT_MS = 65_000L
        const val SETUP_TIMEOUT_MS = 8_000L
        const val RACE_STAGGER_MS = 1_200L
        const val REPLAY_REQUEST_MS = 3_000L
        const val REPLAY_CHECK_MS = 250L
        const val REPLAY_MAX_AGE_MS = 30_000L
        const val REPLAY_HISTORY_BYTES = 64 * 1024
        const val REPLAY_HISTORY_PACKETS = 16
        const val REPLAY_RETRY_MS = 2_000L
        const val REPLAY_MAX_ATTEMPTS = 3

        private val OCTET_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private val random = SecureRandom()

        fun readMtProtoPacket(stream: ClientStream, cltDec: AesCtr, protoTag: ByteArray): Pair<ByteArray, Boolean> {
            fun plaintext(count: Int): ByteArray {
                val buf = stream.readExactly(count)
                cltDec.updateInPlace(buf)
                return buf
            }

            val quick: Boolean
            val length: Int
            if (protoTag.contentEquals(HandshakeCrypto.PROTO_TAG_ABRIDGED)) {
                val first = plaintext(1)[0].toInt() and 0xFF
                quick = (first and 0x80) != 0
                var words = first and 0x7F
                if (words == 0x7F) {
                    val b = plaintext(3)
                    words = (b[0].toInt() and 0xFF) or
                        ((b[1].toInt() and 0xFF) shl 8) or
                        ((b[2].toInt() and 0xFF) shl 16)
                }
                length = words * 4
            } else {
                val b = plaintext(4)
                val value = (b[0].toInt() and 0xFF) or
                    ((b[1].toInt() and 0xFF) shl 8) or
                    ((b[2].toInt() and 0xFF) shl 16) or
                    ((b[3].toInt() and 0xFF) shl 24)
                quick = (value and 0x80000000.toInt()) != 0
                length = value and 0x7FFFFFFF
            }

            val maxAllowed = MAX_PACKET + if (protoTag.contentEquals(HandshakeCrypto.PROTO_TAG_SECURE)) MAX_PLAIN_PADDING else 0
            if (length !in 24..maxAllowed) {
                throw IOException("native packet length outside supported range: $length")
            }

            var body = plaintext(length)
            if (protoTag.contentEquals(HandshakeCrypto.PROTO_TAG_SECURE)) {
                val isZeroPrefix = (0 until 8).all { body[it] == 0.toByte() }
                val packetLength: Int
                val maxPadding: Int
                if (isZeroPrefix) {
                    val innerLen = (body[16].toInt() and 0xFF) or
                        ((body[17].toInt() and 0xFF) shl 8) or
                        ((body[18].toInt() and 0xFF) shl 16) or
                        ((body[19].toInt() and 0xFF) shl 24)
                    packetLength = 20 + innerLen
                    maxPadding = MAX_PLAIN_PADDING
                } else {
                    packetLength = 24 + ((length - 24) / 16) * 16
                    maxPadding = 15
                }
                val padding = length - packetLength
                if (packetLength !in 24..length || padding > maxPadding) {
                    throw IOException("invalid padded native packet: frame=$length packet=$packetLength padding=$padding")
                }
                body = body.copyOfRange(0, packetLength)
            }

            if (body.size % 4 != 0 || body.size > MAX_PACKET) {
                throw IOException("invalid native packet alignment or size: ${body.size}")
            }
            return body to quick
        }

        fun encodeMtProtoReply(body: ByteArray, protoTag: ByteArray): ByteArray {
            if (body.isEmpty() || body.size % 4 != 0 || body.size > MAX_PACKET) {
                throw IOException("invalid HTTP MTProto response length: ${body.size}")
            }
            if (protoTag.contentEquals(HandshakeCrypto.PROTO_TAG_ABRIDGED)) {
                val words = body.size / 4
                val out = ByteArrayOutputStream(body.size + 4)
                if (words < 0x7F) {
                    out.write(words)
                } else {
                    out.write(0x7F)
                    out.write(words and 0xFF)
                    out.write((words ushr 8) and 0xFF)
                    out.write((words ushr 16) and 0xFF)
                }
                out.write(body)
                return out.toByteArray()
            }

            val padLen = if (protoTag.contentEquals(HandshakeCrypto.PROTO_TAG_SECURE)) {
                random.nextInt(4)
            } else {
                0
            }
            val totalLen = body.size + padLen
            val out = ByteArrayOutputStream(4 + totalLen)
            out.write(totalLen and 0xFF)
            out.write((totalLen ushr 8) and 0xFF)
            out.write((totalLen ushr 16) and 0xFF)
            out.write((totalLen ushr 24) and 0xFF)
            out.write(body)
            if (padLen > 0) {
                val pad = ByteArray(padLen).also { random.nextBytes(it) }
                out.write(pad)
            }
            return out.toByteArray()
        }

        private suspend fun awaitResponse(call: Call): Response = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                runCatching { call.cancel() }
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(response))
                    } else {
                        runCatching { response.close() }
                    }
                }
            })
        }
    }

    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 128
        maxRequestsPerHost = 64
    }

    private val dynamicDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val cfg = configProvider()
            return try {
                DnsResolver.resolve(
                    host = hostname,
                    dohProvider = cfg.dohProvider,
                    ipPreference = cfg.ipPreference,
                    cleanIpOverride = cfg.effectiveCfCleanIp,
                )
            } catch (e: UnknownHostException) {
                throw e
            } catch (e: Exception) {
                throw UnknownHostException("H2 DNS lookup failed for $hostname: ${e.message}").apply { initCause(e) }
            }
        }
    }

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(sharedDispatcher)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(65, TimeUnit.SECONDS)
        .dns(dynamicDns)
        .proxy(Proxy.NO_PROXY)
        .build()

    private sealed class ReplyItem {
        data class Data(val bytes: ByteArray) : ReplyItem()
        data class Failure(val error: Throwable) : ReplyItem()
    }

    private data class ReplayPacket(
        val sentAtMs: Long,
        val body: ByteArray,
        val sequence: Long,
        var attempts: Int = 0,
        var lastAttemptAtMs: Long = 0L,
        var pendingJob: Job? = null,
        val originalJobs: MutableSet<Job> = HashSet(),
        var replyId: ByteArray? = null,
    ) {
        val replayed: Boolean get() = lastAttemptAtMs > 0L || attempts > 0
    }

    inner class HttpLane(
        val host: String,
        val baseDomain: String,
        val laneId: Int,
    ) {
        @Volatile var failedUntilMs: Long = 0L
        @Volatile var closed: Boolean = false
        val tcpConnections = AtomicLong(0)
        val requests = AtomicLong(0)
        val responses = AtomicLong(0)
        val errors = AtomicLong(0)
        val inflight = AtomicInteger(0)
        val queuedBytes = AtomicInteger(0)
        val channels: MutableSet<HttpChannel> = ConcurrentHashMap.newKeySet()

        private val streamPermits = Semaphore(MAX_LANE_REQUESTS)
        private val reserveLock = Any()

        // Shares baseClient's Dispatcher, ConnectionPool, and thread pool with a per-lane EventListener.
        val client: OkHttpClient = baseClient.newBuilder()
            .eventListener(object : EventListener() {
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    tcpConnections.incrementAndGet()
                    ProxyStats.h2TcpConnections.incrementAndGet()
                    ProxyLogger.d("H2 lane=$laneId TCP_OPEN connection=${tcpConnections.get()} host=$host channels=${channels.size}")
                }
            })
            .build()

        suspend fun preflight(): Long {
            val started = System.currentTimeMillis()
            val req = Request.Builder()
                .url("https://$host/api")
                .head()
                .build()
            val call = client.newCall(req)
            val response = awaitResponse(call)
            return response.use { resp ->
                if (resp.protocol != Protocol.HTTP_2) {
                    throw IOException("CF /api preflight negotiated ${resp.protocol}")
                }
                val code = resp.code
                if (code !in 200..299 && code != 405 && code != 501) {
                    val ray = resp.header("cf-ray") ?: "-"
                    throw IOException("CF /api preflight HTTP $code cf_ray=$ray")
                }
                val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                ProxyLogger.d("H2 lane=$laneId ready host=$host status=$code ms=$elapsed")
                elapsed
            }
        }

        suspend fun reserve(length: Int, wait: Boolean): Boolean {
            if (!wait) {
                if (!streamPermits.tryAcquire()) return false
                val ok = synchronized(reserveLock) {
                    if (closed || queuedBytes.get() + length > MAX_LANE_BYTES) {
                        false
                    } else {
                        queuedBytes.addAndGet(length)
                        inflight.incrementAndGet()
                        true
                    }
                }
                if (!ok) streamPermits.release()
                return ok
            }

            streamPermits.acquire()
            val deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS
            while (true) {
                synchronized(reserveLock) {
                    if (closed) {
                        streamPermits.release()
                        throw IOException("H2 lane closed")
                    }
                    if (queuedBytes.get() + length <= MAX_LANE_BYTES) {
                        queuedBytes.addAndGet(length)
                        inflight.incrementAndGet()
                        return true
                    }
                }
                if (System.currentTimeMillis() >= deadline) {
                    streamPermits.release()
                    throw IOException("H2 lane capacity wait timed out")
                }
                delay(20)
            }
        }

        fun release(length: Int) {
            synchronized(reserveLock) {
                queuedBytes.addAndGet(-length)
                inflight.decrementAndGet()
            }
            streamPermits.release()
        }

        suspend fun post(body: ByteArray, channelId: Int, replay: Boolean): ByteArray {
            val reqId = requests.incrementAndGet()
            ProxyStats.h2Requests.incrementAndGet()
            val req = Request.Builder()
                .url("https://$host/api")
                .post(body.toRequestBody(OCTET_MEDIA_TYPE))
                .header("Accept-Encoding", "identity")
                .build()
            val call = client.newCall(req)
            try {
                val response = awaitResponse(call)
                return withContext(Dispatchers.IO) {
                    response.use { resp ->
                        val ray = resp.header("cf-ray") ?: "-"
                        if (resp.protocol != Protocol.HTTP_2) {
                            throw IOException("CF /api downgraded to ${resp.protocol}")
                        }
                        val code = resp.code
                        if (code in setOf(403, 404, 429, 444)) {
                            throw MtProtoTransportError(-code, "HTTP $code cf_ray=$ray")
                        }
                        if (code != 200) {
                            throw IOException("CF /api HTTP $code cf_ray=$ray")
                        }
                        val ctype = resp.header("Content-Type")?.lowercase().orEmpty()
                        if ("text/html" in ctype) {
                            throw IOException("CF /api returned HTML instead of MTProto")
                        }
                        val respBody = resp.body ?: throw IOException("Empty H2 response body")
                        val declared = respBody.contentLength()
                        if (declared > MAX_PACKET) {
                            throw IOException("H2 response Content-Length exceeds native packet bound")
                        }
                        val bytes = respBody.bytes()
                        if (bytes.size > MAX_PACKET) {
                            throw IOException("H2 response exceeds native packet bound")
                        }
                        if (bytes.size % 4 != 0) {
                            throw IOException("CF /api response is not an aligned MTProto packet")
                        }
                        if (bytes.size == 4) {
                            val errCode = (bytes[0].toInt() and 0xFF) or
                                ((bytes[1].toInt() and 0xFF) shl 8) or
                                ((bytes[2].toInt() and 0xFF) shl 16) or
                                ((bytes[3].toInt() and 0xFF) shl 24)
                            if (errCode < 0) {
                                throw MtProtoTransportError(errCode, "HTTP 200 payload")
                            }
                        }
                        responses.incrementAndGet()
                        bytes
                    }
                }
            } catch (e: CancellationException) {
                call.cancel()
                throw e
            } catch (e: MtProtoTransportError) {
                errors.incrementAndGet()
                throw e
            } catch (e: Exception) {
                errors.incrementAndGet()
                failedUntilMs = System.currentTimeMillis() + DOMAIN_COOLDOWN_MS
                balancer.reportFailure(baseDomain, tlsSpecific = false)
                ProxyLogger.d("H2 lane=$laneId ch=$channelId req=$reqId replay=$replay failed: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }

        fun summary(): String =
            "lane=$laneId host=$host channels=${channels.size} tcp=${tcpConnections.get()} " +
                "req=${requests.get()} replies=${responses.get()} err=${errors.get()}"

        fun close() {
            closed = true
            channels.toList().forEach { it.closeQuietly() }
            channels.clear()
        }
    }

    inner class HttpChannel(
        val lane: HttpLane,
        val channelId: Int,
        val label: String,
    ) {
        @Volatile var closed = false
        @Volatile var transportError: Int? = null
        @Volatile var lastProgressMs = System.currentTimeMillis()
        @Volatile var delivering = false

        var downBytes = 0L
        var upBytes = 0L
        var requestSeq = 0L

        private val stateLock = Any()
        private val sendMutex = Mutex()
        private val pending = HashMap<Job, Int>()
        private val sentSinceMs = HashMap<Job, Long>()
        private val replayHistory = ArrayList<ReplayPacket>()
        private var replayKey: ByteArray? = null
        private var pendingBytes = 0
        private var replyBytes = 0

        private val replyQueue = Channel<ReplyItem>(16)
        private val recoverWake = Channel<Unit>(Channel.CONFLATED)

        init {
            lane.channels.add(this)
        }

        private fun hasCapacity(length: Int): Boolean {
            return pending.size < MAX_CHANNEL_REQUESTS && pendingBytes + length <= MAX_CHANNEL_BYTES
        }

        private fun fail(err: Throwable) {
            if (closed) return
            if (err is MtProtoTransportError) {
                transportError = err.code
            }
            closed = true
            while (replyQueue.tryReceive().isSuccess) {
                // drain
            }
            synchronized(stateLock) {
                replyBytes = 0
            }
            replyQueue.trySend(ReplyItem.Failure(err))
            recoverWake.trySend(Unit)
        }

        suspend fun send(scope: CoroutineScope, body: ByteArray, replay: Boolean): Boolean {
            if (replay && sendMutex.isLocked) return false
            return sendMutex.withLock {
                sendLocked(scope, body, replay)
            }
        }

        private suspend fun sendLocked(scope: CoroutineScope, body: ByteArray, replay: Boolean): Boolean {
            val deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS
            while (true) {
                if (closed) throw IOException("H2 channel closed")
                val ok = synchronized(stateLock) { hasCapacity(body.size) }
                if (ok) break
                if (replay) return false
                if (System.currentTimeMillis() >= deadline) {
                    throw IOException("H2 channel capacity wait timed out")
                }
                delay(20)
            }

            if (!lane.reserve(body.size, wait = !replay)) return false
            if (closed) {
                lane.release(body.size)
                throw IOException("H2 channel closed")
            }

            val now = System.currentTimeMillis()
            val replayPacket = if (replay) synchronized(stateLock) { findPacketLocked(body) } else null
            if (replay && (replayPacket == null || replayPacket.pendingJob != null || !isReplayDue(replayPacket, now))) {
                lane.release(body.size)
                return false
            }

            val packet: ReplayPacket?
            lateinit var job: Job
            synchronized(stateLock) {
                pendingBytes += body.size
                requestSeq++
                upBytes += body.size
                ProxyStats.bytesUp.addAndGet(body.size.toLong())
                val nativePacket = if (!replay) rememberPacketLocked(body, now) else null
                packet = replayPacket ?: nativePacket
            }

            job = scope.launch(start = CoroutineStart.LAZY) {
                runRequest(body, replay, packet, job)
            }

            synchronized(stateLock) {
                pending[job] = body.size
                sentSinceMs[job] = now
                if (!replay && packet != null) {
                    packet.originalJobs.add(job)
                    trimHistoryLocked()
                } else if (replay && replayPacket != null) {
                    replayPacket.attempts++
                    replayPacket.lastAttemptAtMs = now
                    replayPacket.pendingJob = job
                }
            }
            job.start()
            recoverWake.trySend(Unit)
            return true
        }

        private suspend fun runRequest(
            body: ByteArray,
            replay: Boolean,
            packet: ReplayPacket?,
            selfJob: Job,
        ) {
            try {
                val reply = lane.post(body, channelId, replay)
                lastProgressMs = System.currentTimeMillis()
                if (reply.isNotEmpty() && !closed) {
                    if (packet != null) {
                        val prefix = reply.copyOfRange(0, minOf(24, reply.size))
                        val prev = packet.replyId
                        if (prev == null || !prev.contentEquals(prefix)) {
                            packet.replyId = prefix
                            packet.attempts = 0
                        }
                    }
                    synchronized(stateLock) {
                        if (replyBytes + reply.size > MAX_CHANNEL_BYTES) {
                            throw IOException("H2 client response queue exceeded bound")
                        }
                        replyBytes += reply.size
                    }
                    replyQueue.send(ReplyItem.Data(reply))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: MtProtoTransportError) {
                ProxyStats.h2Errors.incrementAndGet()
                if (!closed) {
                    ProxyLogger.w("[$label] H2 lane=${lane.laneId} ch=$channelId host=${lane.host} MTProto error=${exc.message}")
                }
                fail(exc)
            } catch (exc: Exception) {
                ProxyStats.h2Errors.incrementAndGet()
                if (!closed) {
                    ProxyLogger.w("[$label] H2 lane=${lane.laneId} ch=$channelId request failed: ${exc.javaClass.simpleName}: ${exc.message}")
                }
                fail(exc)
            } finally {
                synchronized(stateLock) {
                    val len = pending.remove(selfJob)
                    if (len != null) {
                        pendingBytes -= len
                        sentSinceMs.remove(selfJob)
                        if (packet != null) {
                            packet.originalJobs.remove(selfJob)
                            if (packet.pendingJob === selfJob) {
                                packet.pendingJob = null
                            }
                        }
                        lane.release(len)
                    }
                }
                recoverWake.trySend(Unit)
            }
        }

        suspend fun receive(): ByteArray {
            when (val item = replyQueue.receive()) {
                is ReplyItem.Data -> {
                    synchronized(stateLock) {
                        replyBytes -= item.bytes.size
                    }
                    recoverWake.trySend(Unit)
                    return item.bytes
                }
                is ReplyItem.Failure -> throw item.error
            }
        }

        suspend fun recoverLoop(scope: CoroutineScope) {
            while (!closed && scope.isActive) {
                val now = System.currentTimeMillis()
                val nextAction = synchronized(stateLock) {
                    if (closed) {
                        -2L to null
                    } else if (delivering || replyBytes > 0) {
                        -1L to null
                    } else {
                        val due = replayHistory.firstOrNull { isReplayDue(it, now) && it.pendingJob == null }
                        if (due != null) {
                            0L to due
                        } else {
                            computeNextReplayWaitMsLocked(now) to null
                        }
                    }
                }
                val waitMs = nextAction.first
                val candidate = nextAction.second
                if (waitMs == -2L) break
                if (candidate != null) {
                    send(scope, candidate.body, replay = true)
                    delay(REPLAY_CHECK_MS)
                } else if (waitMs < 0L) {
                    // Nothing eligible right now; sleep cleanly without polling until state changes.
                    val sig = recoverWake.receiveCatching()
                    if (sig.isClosed) break
                } else {
                    val sig = withTimeoutOrNull(waitMs.coerceAtLeast(REPLAY_CHECK_MS)) {
                        recoverWake.receiveCatching()
                    }
                    if (sig != null && sig.isClosed) break
                }
            }
        }

        private fun computeNextReplayWaitMsLocked(nowMs: Long): Long {
            var earliest = Long.MAX_VALUE
            val last = replayHistory.lastOrNull()
            for (entry in replayHistory) {
                val age = nowMs - entry.sentAtMs
                if (age !in 0..REPLAY_MAX_AGE_MS) continue
                val isLast = entry === last
                val limit = if (entry.originalJobs.isNotEmpty() || isLast) REPLAY_MAX_ATTEMPTS else 0
                if (entry.attempts >= limit || entry.pendingJob != null) continue

                if (entry.originalJobs.isNotEmpty()) {
                    for (job in entry.originalJobs) {
                        val sent = sentSinceMs[job] ?: continue
                        val at = sent + REPLAY_REQUEST_MS
                        if (at < earliest) earliest = at
                    }
                } else if (isLast && pending.isEmpty()) {
                    val retryAt = if (entry.replayed) {
                        maxOf(entry.lastAttemptAtMs + REPLAY_RETRY_MS, lastProgressMs + 1000L)
                    } else {
                        nowMs + REPLAY_CHECK_MS
                    }
                    if (retryAt < earliest) earliest = retryAt
                }
            }
            return if (earliest == Long.MAX_VALUE) -1L else (earliest - nowMs).coerceAtLeast(REPLAY_CHECK_MS)
        }

        private fun isReplayDue(entry: ReplayPacket, nowMs: Long): Boolean {
            val age = nowMs - entry.sentAtMs
            if (age !in 0..REPLAY_MAX_AGE_MS) return false
            val isLast = replayHistory.lastOrNull() === entry
            val limit = if (entry.originalJobs.isNotEmpty() || isLast) REPLAY_MAX_ATTEMPTS else 0
            if (entry.attempts >= limit) return false
            if (entry.replayed && nowMs - entry.lastAttemptAtMs < REPLAY_RETRY_MS) return false
            val overdue = entry.originalJobs.any { job ->
                val sent = sentSinceMs[job]
                sent != null && nowMs - sent >= REPLAY_REQUEST_MS
            }
            if (overdue) return true
            if (!isLast || pending.any { it.key !== entry.pendingJob }) return false
            if (entry.replayed && nowMs - lastProgressMs < 1000L) return false
            return true
        }

        private fun findPacketLocked(body: ByteArray): ReplayPacket? =
            replayHistory.firstOrNull { it.body.contentEquals(body) }

        private fun rememberPacketLocked(body: ByteArray, nowMs: Long): ReplayPacket? {
            if (body.size < 8 || (0 until 8).all { body[it] == 0.toByte() }) return null
            val key = body.copyOfRange(0, 8)
            val prevKey = replayKey
            if (prevKey == null || !prevKey.contentEquals(key)) {
                replayHistory.clear()
                replayKey = key
            }
            if (body.size > REPLAY_HISTORY_BYTES) return null
            val existing = findPacketLocked(body)
            if (existing != null) return existing
            return ReplayPacket(nowMs, body, requestSeq).also { replayHistory.add(it) }
        }

        private fun trimHistoryLocked() {
            var totalBytes = replayHistory.sumOf { it.body.size }
            while (replayHistory.size > REPLAY_HISTORY_PACKETS || totalBytes > REPLAY_HISTORY_BYTES) {
                val victim = replayHistory.firstOrNull { it.originalJobs.isEmpty() } ?: replayHistory.first()
                replayHistory.remove(victim)
                totalBytes -= victim.body.size
            }
        }

        fun closeQuietly() {
            if (closed) return
            fail(IOException("H2 channel closed"))
            val toCancel = synchronized(stateLock) {
                val list = pending.keys.toList()
                pending.clear()
                sentSinceMs.clear()
                replayHistory.clear()
                pendingBytes = 0
                list
            }
            toCancel.forEach { it.cancel() }
            lane.channels.remove(this)
            replyQueue.close()
            recoverWake.close()
        }
    }

    private val lock = Any()
    private val lanes = HashMap<String, HttpLane>()
    private val failedUntilMs = HashMap<String, Long>()
    private val setupRetryAfterMs = HashMap<Int, Long>()
    private var nextLaneId = 0
    private var nextChannelId = 0
    @Volatile private var closed = false

    suspend fun open(dc: Int, label: String): HttpChannel? {
        if (closed) return null
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now < (setupRetryAfterMs[dc] ?: 0L)) return null
        }
        return try {
            withTimeout(SETUP_TIMEOUT_MS) {
                openInternal(dc, label)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            synchronized(lock) {
                setupRetryAfterMs[dc] = System.currentTimeMillis() + DOMAIN_COOLDOWN_MS
            }
            ProxyStats.h2Errors.incrementAndGet()
            ProxyLogger.w("[$label] H2 DC$dc setup timed out; using WS for ${DOMAIN_COOLDOWN_MS / 1000}s")
            null
        }
    }

    private suspend fun openInternal(dc: Int, label: String): HttpChannel? {
        if (closed || dc !in DC_DEFAULT_IPS) return null
        val domains = balancer.getDomainsForDc(dc)
        if (domains.isEmpty()) return null

        val now = System.currentTimeMillis()

        // Fast path: reuse an already-established healthy lane for any top domain.
        val reusable = synchronized(lock) {
            var found: Pair<HttpLane, String>? = null
            for (baseDomain in domains) {
                val host = "kws$dc.$baseDomain"
                val existing = lanes[host] ?: continue
                if (existing.closed) continue
                val blockedUntil = maxOf(failedUntilMs[host] ?: 0L, existing.failedUntilMs)
                if (now >= blockedUntil) {
                    found = existing to baseDomain
                    break
                }
            }
            found
        }
        if (reusable != null) {
            return attachChannel(dc, reusable.first, reusable.second, label)
        }

        // Collect up to 3 unblocked candidates and race preflights with 1.2s stagger (no global lock held!).
        val candidates = ArrayList<Pair<String, String>>(3)
        synchronized(lock) {
            for (baseDomain in domains) {
                val host = "kws$dc.$baseDomain"
                val existing = lanes[host]
                val blockedUntil = maxOf(failedUntilMs[host] ?: 0L, existing?.failedUntilMs ?: 0L)
                if (now < blockedUntil) continue
                candidates.add(baseDomain to host)
                if (candidates.size >= 3) break
            }
        }
        if (candidates.isEmpty()) return null

        val winner = AtomicReference<Triple<HttpLane, String, Long>?>(null)
        val done = CompletableDeferred<Triple<HttpLane, String, Long>?>()
        val remaining = AtomicInteger(candidates.size)
        val failedSignal = Channel<Unit>(Channel.CONFLATED)

        val won = coroutineScope {
            val launcher = launch(Dispatchers.IO) {
                for ((idx, pair) in candidates.withIndex()) {
                    if (closed || winner.get() != null || !isActive) break
                    if (idx > 0) {
                        withTimeoutOrNull(RACE_STAGGER_MS) { failedSignal.receive() }
                        if (closed || winner.get() != null || !isActive) break
                    }
                    val (baseDomain, host) = pair
                    launch {
                        val laneId = synchronized(lock) { ++nextLaneId }
                        val candidate = HttpLane(host, baseDomain, laneId)
                        try {
                            val rtt = candidate.preflight()
                            val triple = Triple(candidate, baseDomain, rtt)
                            if (!closed && winner.compareAndSet(null, triple)) {
                                if (!done.complete(triple)) {
                                    candidate.close()
                                }
                            } else {
                                candidate.close()
                            }
                        } catch (e: CancellationException) {
                            candidate.close()
                            throw e
                        } catch (exc: Exception) {
                            candidate.close()
                            ProxyStats.h2Errors.incrementAndGet()
                            synchronized(lock) {
                                failedUntilMs[host] = System.currentTimeMillis() + DOMAIN_COOLDOWN_MS
                            }
                            balancer.reportFailure(baseDomain, tlsSpecific = false)
                            ProxyLogger.w("[$label] H2 lane=$laneId preflight failed host=$host: ${exc.javaClass.simpleName}: ${exc.message}")
                            failedSignal.trySend(Unit)
                            if (remaining.decrementAndGet() == 0) {
                                done.complete(null)
                            }
                        }
                    }
                }
            }

            val res = try {
                done.await()
            } catch (e: CancellationException) {
                done.cancel()
                launcher.cancel()
                failedSignal.close()
                winner.getAndSet(null)?.first?.close()
                throw e
            }
            launcher.cancel()
            failedSignal.close()
            res
        } ?: return null

        val (candidateLane, baseDomain, rtt) = won
        if (closed) {
            candidateLane.close()
            return null
        }

        val adoptedLane = synchronized(lock) {
            val existing = lanes[candidateLane.host]
            if (existing != null && !existing.closed && System.currentTimeMillis() >= existing.failedUntilMs) {
                existing
            } else {
                lanes[candidateLane.host] = candidateLane
                candidateLane
            }
        }
        if (adoptedLane !== candidateLane) {
            candidateLane.close()
        }

        balancer.reportSuccess(dc, baseDomain, rtt, usedPort80 = false)
        return attachChannel(dc, adoptedLane, baseDomain, label)
    }

    private fun attachChannel(dc: Int, lane: HttpLane, baseDomain: String, label: String): HttpChannel {
        val channelId = synchronized(lock) { ++nextChannelId }
        val channel = HttpChannel(lane, channelId, label)
        balancer.updateDomainForDc(dc, baseDomain)
        ProxyLogger.i(
            "[$label] ROUTE DC$dc media -> H2 lane=${lane.laneId} ch=$channelId host=${lane.host} " +
                "channels=${lane.channels.size} tcp=${lane.tcpConnections.get()}"
        )
        return channel
    }

    suspend fun bridgeH2(
        stream: ClientStream,
        channel: HttpChannel,
        ctx: CryptoCtx,
        protoTag: ByteArray,
    ) = withContext(Dispatchers.IO) {
        fun writeNative(data: ByteArray) {
            ctx.cltEnc.updateInPlace(data)
            stream.write(data)
        }

        try {
            coroutineScope {
                val recoverJob = launch {
                    channel.recoverLoop(this)
                }
                val uploadJob = launch {
                    try {
                        while (isActive) {
                            val (body, _quick) = readMtProtoPacket(stream, ctx.cltDec, protoTag)
                            channel.send(this@coroutineScope, body, replay = false)
                        }
                    } catch (_: EOFException) {
                        ProxyLogger.d("[${channel.label}] H2 ch=${channel.channelId} native EOF")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (exc: Exception) {
                        if (!channel.closed) {
                            ProxyLogger.d("[${channel.label}] H2 ch=${channel.channelId} upload ended: ${exc.javaClass.simpleName}: ${exc.message}")
                        }
                    } finally {
                        channel.closeQuietly()
                    }
                }
                val downloadJob = launch {
                    try {
                        while (isActive) {
                            val body = channel.receive()
                            if (channel.closed) break
                            channel.delivering = true
                            try {
                                writeNative(encodeMtProtoReply(body, protoTag))
                            } finally {
                                channel.delivering = false
                            }
                            channel.downBytes += body.size
                            ProxyStats.bytesDown.addAndGet(body.size.toLong())
                            channel.lastProgressMs = System.currentTimeMillis()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (exc: Exception) {
                        val errCode = channel.transportError
                        if (errCode != null) {
                            val codeBytes = byteArrayOf(
                                (errCode and 0xFF).toByte(),
                                ((errCode ushr 8) and 0xFF).toByte(),
                                ((errCode ushr 16) and 0xFF).toByte(),
                                ((errCode ushr 24) and 0xFF).toByte(),
                            )
                            runCatching {
                                writeNative(encodeMtProtoReply(codeBytes, protoTag))
                                channel.downBytes += 4
                                ProxyStats.bytesDown.addAndGet(4)
                            }
                        } else if (!channel.closed) {
                            ProxyLogger.w("[${channel.label}] H2 ch=${channel.channelId} download ended: ${exc.javaClass.simpleName}: ${exc.message}")
                        }
                    } finally {
                        stream.close()
                        recoverJob.cancel()
                    }
                }

                uploadJob.join()
                downloadJob.join()
                recoverJob.cancel()
            }
        } finally {
            channel.closeQuietly()
            stream.close()
        }
    }

    fun logStats() {
        val snapshot = synchronized(lock) { lanes.values.toList() }
        for (lane in snapshot) {
            if (lane.channels.isNotEmpty()) {
                ProxyLogger.d("H2 STATS ${lane.summary()}")
            }
        }
    }

    fun onConfigUpdated() {
        synchronized(lock) {
            failedUntilMs.clear()
            setupRetryAfterMs.clear()
            for (lane in lanes.values) {
                lane.failedUntilMs = 0L
            }
        }
        runCatching { baseClient.connectionPool.evictAll() }
    }

    fun onNetworkChanged() {
        val snapshot = synchronized(lock) {
            failedUntilMs.clear()
            setupRetryAfterMs.clear()
            val list = lanes.values.toList()
            lanes.clear()
            list
        }
        for (lane in snapshot) {
            lane.close()
        }
        runCatching { baseClient.dispatcher.cancelAll() }
        runCatching { baseClient.connectionPool.evictAll() }
    }

    fun close() {
        closed = true
        val toClose = synchronized(lock) {
            val list = lanes.values.toList()
            lanes.clear()
            list
        }
        toClose.forEach { it.close() }
        runCatching { baseClient.dispatcher.cancelAll() }
        runCatching { baseClient.connectionPool.evictAll() }
        runCatching { baseClient.dispatcher.executorService.shutdown() }
    }
}
