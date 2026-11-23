package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Buffered InputStream that also accepts FIFO pre-read chunks collected by [ClientDisconnectWatcher]
 * while the underlying [SocketChannel] was temporarily monitored in non-blocking mode during
 * upstream connection setup.
 */
class BufferedClientInputStream(
    private val raw: InputStream,
    bufferSize: Int = 8192,
) : InputStream() {
    private val readBuf = ByteArray(bufferSize.coerceAtLeast(1024))
    private var readPos = 0
    private var readLimit = 0

    private val extraLock = Any()
    private val extraChunks = ArrayDeque<ByteArray>()
    private var extraOffset = 0
    private var extraBytesTotal = 0

    fun preReadBytes(): Int = synchronized(extraLock) { extraBytesTotal }

    internal fun appendPreRead(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(extraLock) {
            extraChunks.addLast(bytes)
            extraBytesTotal += bytes.size
        }
    }

    override fun read(): Int {
        if (readPos < readLimit) {
            return readBuf[readPos++].toInt() and 0xFF
        }
        val extraByte = readByteFromExtra()
        if (extraByte >= 0) return extraByte

        val n = raw.read(readBuf, 0, readBuf.size)
        if (n <= 0) return -1
        readPos = 1
        readLimit = n
        return readBuf[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (off < 0 || len < 0 || off > b.size - len) {
            throw IndexOutOfBoundsException()
        }
        if (readPos < readLimit) {
            val avail = readLimit - readPos
            val take = minOf(len, avail)
            System.arraycopy(readBuf, readPos, b, off, take)
            readPos += take
            return take
        }
        val fromExtra = readSliceFromExtra(b, off, len)
        if (fromExtra > 0) return fromExtra

        if (len >= readBuf.size) {
            return raw.read(b, off, len)
        }
        val n = raw.read(readBuf, 0, readBuf.size)
        if (n <= 0) return -1
        val take = minOf(len, n)
        System.arraycopy(readBuf, 0, b, off, take)
        readPos = take
        readLimit = n
        return take
    }

    override fun available(): Int {
        val buffered = (readLimit - readPos) + preReadBytes()
        if (buffered > 0) return buffered
        return raw.available()
    }

    override fun close() {
        raw.close()
    }

    private fun readByteFromExtra(): Int = synchronized(extraLock) {
        val head = extraChunks.peekFirst() ?: return -1
        val value = head[extraOffset++].toInt() and 0xFF
        extraBytesTotal--
        if (extraOffset >= head.size) {
            extraChunks.removeFirst()
            extraOffset = 0
        }
        value
    }

    private fun readSliceFromExtra(dst: ByteArray, off: Int, len: Int): Int = synchronized(extraLock) {
        if (extraChunks.isEmpty()) return 0
        var copied = 0
        while (copied < len && extraChunks.isNotEmpty()) {
            val head = extraChunks.peekFirst()!!
            val avail = head.size - extraOffset
            val take = minOf(len - copied, avail)
            System.arraycopy(head, extraOffset, dst, off + copied, take)
            copied += take
            extraOffset += take
            extraBytesTotal -= take
            if (extraOffset >= head.size) {
                extraChunks.removeFirst()
                extraOffset = 0
            }
        }
        copied
    }
}

interface ClientSetupGuard {
    suspend fun <T> guard(block: suspend () -> T): T

    companion object {
        val NOOP: ClientSetupGuard = object : ClientSetupGuard {
            override suspend fun <T> guard(block: suspend () -> T): T = block()
        }
    }
}

/**
 * Watches client [SocketChannel]s with a single shared NIO [Selector] while upstream dials
 * (CF proxy / CF worker / H2 preflight / TCP fallback) are in flight.
 *
 * If the client closes its socket before the upstream connects, the in-flight dial coroutine
 * is cancelled immediately, avoiding wasted sockets and threads (upstream Flowseal v1.12.0
 * `client_setup` parity). Any early payload bytes sent by the client during dial are safely
 * buffered into [BufferedClientInputStream] in exact FIFO order.
 */
class ClientDisconnectWatcher {
    companion object {
        internal const val MAX_PRE_READ_BYTES = 64 * 1024
        private const val SELECT_TIMEOUT_MS = 250L
        private const val OP_WAIT_TIMEOUT_MS = 1_000L
    }

    private data class WatchEntry(
        val channel: SocketChannel,
        val preReadStream: BufferedClientInputStream,
        val onEof: () -> Unit,
    )

    private val running = AtomicBoolean(false)
    private val pendingOps = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile private var selector: Selector? = null
    @Volatile private var watcherThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val sel = try {
            Selector.open()
        } catch (exc: Exception) {
            running.set(false)
            ProxyLogger.w("ClientDisconnectWatcher failed to open Selector: ${exc.message}")
            return
        }
        selector = sel
        val thread = Thread({ selectLoop(sel) }, "tg-client-watcher").apply {
            isDaemon = true
        }
        watcherThread = thread
        thread.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val sel = selector
        val thread = watcherThread
        sel?.wakeup()
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(500L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        watcherThread = null
        drainPendingOps()
    }

    fun createGuard(
        channel: SocketChannel,
        preReadStream: BufferedClientInputStream,
    ): ClientSetupGuard = object : ClientSetupGuard {
        override suspend fun <T> guard(block: suspend () -> T): T {
            return guardInternal(channel, preReadStream, block)
        }
    }

    private suspend fun <T> guardInternal(
        channel: SocketChannel,
        preReadStream: BufferedClientInputStream,
        block: suspend () -> T,
    ): T {
        if (!channel.isOpen) {
            throw CancellationException("Client socket already closed")
        }
        val sel = selector
        if (sel == null || !running.get()) {
            return block()
        }

        return coroutineScope {
            val dialJobRef = AtomicReference<Job?>(null)
            val clientGone = AtomicBoolean(false)
            val keyRef = AtomicReference<SelectionKey?>(null)
            val registered = AtomicBoolean(false)
            val immediateEof = AtomicBoolean(false)
            val regLatch = CountDownLatch(1)

            val watchEntry = WatchEntry(
                channel = channel,
                preReadStream = preReadStream,
                onEof = {
                    clientGone.set(true)
                    dialJobRef.get()?.cancel(CancellationException("Client disconnected during upstream setup"))
                },
            )

            enqueueOp(sel) {
                try {
                    if (!running.get() || !sel.isOpen) {
                        // Watcher stopped concurrently; fall through without watching.
                    } else if (!channel.isOpen) {
                        immediateEof.set(true)
                    } else {
                        channel.configureBlocking(false)
                        if (drainOrDetectEof(channel, preReadStream)) {
                            immediateEof.set(true)
                            runCatching { channel.configureBlocking(true) }
                        } else {
                            val ops = if (preReadStream.preReadBytes() < MAX_PRE_READ_BYTES) {
                                SelectionKey.OP_READ
                            } else {
                                0
                            }
                            val key = channel.register(sel, ops, watchEntry)
                            keyRef.set(key)
                            registered.set(true)
                        }
                    }
                } catch (_: Exception) {
                    if (!channel.isOpen) {
                        immediateEof.set(true)
                    } else {
                        runCatching { channel.configureBlocking(true) }
                    }
                } finally {
                    regLatch.countDown()
                }
            }
            awaitQuietly(regLatch)

            if (immediateEof.get() || clientGone.get()) {
                throw CancellationException("Client disconnected before upstream setup")
            }

            try {
                val dialJob = coroutineContext[Job]
                dialJobRef.set(dialJob)
                if (clientGone.get()) {
                    throw CancellationException("Client disconnected during upstream setup")
                }
                block()
            } finally {
                if (registered.get()) {
                    val activeSel = selector
                    if (activeSel != null && running.get() && activeSel.isOpen) {
                        val unregLatch = CountDownLatch(1)
                        enqueueOp(activeSel) {
                            try {
                                keyRef.getAndSet(null)?.cancel()
                                // Flush cancelled key from the selector so configureBlocking(true) succeeds immediately.
                                runCatching { activeSel.selectNow() }
                                if (channel.isOpen) {
                                    runCatching { channel.configureBlocking(true) }
                                }
                            } finally {
                                unregLatch.countDown()
                            }
                        }
                        awaitQuietly(unregLatch)
                    } else {
                        runCatching { keyRef.getAndSet(null)?.cancel() }
                        if (channel.isOpen) {
                            runCatching { channel.configureBlocking(true) }
                        }
                    }
                }
            }
        }
    }

    private fun enqueueOp(sel: Selector, op: () -> Unit) {
        pendingOps.add(op)
        sel.wakeup()
        val t = watcherThread
        if ((!running.get() || !sel.isOpen) && (t == null || !t.isAlive)) {
            drainPendingOps()
        }
    }

    private fun awaitQuietly(latch: CountDownLatch) {
        try {
            latch.await(OP_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun drainPendingOps() {
        while (true) {
            val op = pendingOps.poll() ?: break
            try {
                op()
            } catch (_: Throwable) {
            }
        }
    }

    private fun selectLoop(sel: Selector) {
        try {
            while (running.get() && sel.isOpen) {
                drainPendingOps()
                val ready = try {
                    sel.select(SELECT_TIMEOUT_MS)
                } catch (_: ClosedSelectorException) {
                    break
                } catch (_: IOException) {
                    break
                }
                drainPendingOps()
                val selected = sel.selectedKeys()
                if (selected.isEmpty() && ready == 0) continue

                val it = selected.iterator()
                while (it.hasNext()) {
                    val key = it.next()
                    it.remove()
                    if (!key.isValid) continue
                    val entry = key.attachment() as? WatchEntry ?: continue
                    if (key.isReadable) {
                        val eof = drainOrDetectEof(entry.channel, entry.preReadStream)
                        if (eof) {
                            key.cancel()
                            entry.onEof()
                        } else if (entry.preReadStream.preReadBytes() >= MAX_PRE_READ_BYTES) {
                            runCatching { key.interestOps(0) }
                        }
                    }
                }
            }
        } finally {
            running.set(false)
            selector = null
            runCatching {
                for (key in sel.keys()) {
                    runCatching { key.cancel() }
                }
            }
            runCatching { sel.close() }
            drainPendingOps()
        }
    }

    /**
     * Reads any currently available bytes from a non-blocking [channel] into [preReadStream].
     * Returns `true` if EOF or socket error was detected, `false` if the peer is still open.
     */
    private fun drainOrDetectEof(
        channel: SocketChannel,
        preReadStream: BufferedClientInputStream,
    ): Boolean {
        val tmp = ByteBuffer.allocate(4096)
        while (preReadStream.preReadBytes() < MAX_PRE_READ_BYTES) {
            tmp.clear()
            val maxRead = (MAX_PRE_READ_BYTES - preReadStream.preReadBytes()).coerceAtMost(tmp.capacity())
            tmp.limit(maxRead)
            val n = try {
                channel.read(tmp)
            } catch (_: IOException) {
                return true
            }
            when {
                n < 0 -> return true
                n == 0 -> return false
                else -> {
                    val chunk = ByteArray(n)
                    tmp.flip()
                    tmp.get(chunk)
                    preReadStream.appendPreRead(chunk)
                }
            }
        }
        return false
    }
}
