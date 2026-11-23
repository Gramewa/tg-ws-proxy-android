package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val message: String,
    val timeText: String = TIME_FORMATTER.format(Instant.ofEpochMilli(timestampMs)),
) {
    fun formatLine(): String {
        val tag = when (level) {
            LogLevel.DEBUG -> "DEBUG"
            LogLevel.INFO -> "INFO "
            LogLevel.WARN -> "WARN "
            LogLevel.ERROR -> "ERROR"
        }
        return "$timeText  $tag  $message"
    }

    companion object {
        private val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
                .withZone(ZoneId.systemDefault())
    }
}

object DomainCensor {
    private val secretParamPattern = Regex("""(?i)(secret=)[0-9a-f]{16,}""")
    private val domainPattern = Regex(
        """(?<![\w-])(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}(?![\w-])"""
    )

    fun censor(message: String): String {
        val hasSecret = message.contains("secret=", ignoreCase = true)
        val hasDot = '.' in message
        if (!hasSecret && !hasDot) return message

        val withoutSecret = if (hasSecret) {
            secretParamPattern.replace(message, "$1***")
        } else {
            message
        }
        if (!hasDot) return withoutSecret

        return domainPattern.replace(withoutSecret) { match ->
            val domain = match.value
            val normalized = domain.lowercase().trimEnd('.')
            if (normalized == "telegram.org" ||
                normalized.endsWith(".telegram.org") ||
                normalized.endsWith(".log")
            ) {
                domain
            } else {
                val parts = domain.split('.')
                if (parts.size < 2) {
                    domain
                } else {
                    parts.mapIndexed { idx, part ->
                        if (idx == parts.lastIndex) {
                            part
                        } else {
                            val keep = part.length / 2
                            part.substring(0, keep) + "*".repeat(part.length - keep)
                        }
                    }.joinToString(".")
                }
            }
        }
    }
}

object ProxyLogger {
    private const val TAG = "TgWsProxy"
    private const val MAX_ENTRIES = 1000
    private const val UI_EMIT_COALESCE_MS = 150L

    val verboseEnabled = AtomicBoolean(false)

    private val lock = Any()
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var nextId = 1L
    private var lastUiEmitMs = 0L
    private var uiDirty = false
    private var pendingFlushJob: Job? = null
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    val isDebugEnabled: Boolean
        get() = verboseEnabled.get()

    fun setVerbose(enabled: Boolean) {
        verboseEnabled.set(enabled)
    }

    fun d(message: String) {
        if (!verboseEnabled.get()) return
        log(LogLevel.DEBUG, message)
    }

    fun i(message: String) = log(LogLevel.INFO, message)

    fun w(message: String) = log(LogLevel.WARN, message)

    fun e(message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) {
            "$message: ${throwable.javaClass.simpleName}: ${throwable.message}"
        } else {
            message
        }
        log(LogLevel.ERROR, full)
    }

    fun flushToUi() {
        val snapshot = synchronized(lock) {
            pendingFlushJob?.cancel()
            pendingFlushJob = null
            uiDirty = false
            lastUiEmitMs = ProxyClock.monotonicMs()
            ArrayList(buffer)
        }
        _entries.value = snapshot
    }

    fun clear() {
        synchronized(lock) {
            pendingFlushJob?.cancel()
            pendingFlushJob = null
            uiDirty = false
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    fun exportText(): String {
        val snapshot = synchronized(lock) { buffer.toList() }
        return snapshot.joinToString("\n") { it.formatLine() }
    }

    private fun log(level: LogLevel, rawMessage: String) {
        val censored = DomainCensor.censor(rawMessage)
        val wallNow = ProxyClock.wallClockMs()
        val monoNow = ProxyClock.monotonicMs()
        val hasObservers = _entries.subscriptionCount.value > 0
        var immediateSnapshot: List<LogEntry>? = null

        synchronized(lock) {
            val entry = LogEntry(
                id = nextId++,
                timestampMs = wallNow,
                level = level,
                message = censored,
            )
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            if (hasObservers) {
                if (lastUiEmitMs == 0L || monoNow - lastUiEmitMs >= UI_EMIT_COALESCE_MS) {
                    lastUiEmitMs = monoNow
                    uiDirty = false
                    pendingFlushJob?.cancel()
                    pendingFlushJob = null
                    immediateSnapshot = ArrayList(buffer)
                } else {
                    uiDirty = true
                    if (pendingFlushJob?.isActive != true) {
                        pendingFlushJob = flushScope.launch {
                            delay(UI_EMIT_COALESCE_MS)
                            val deferredSnapshot = synchronized(lock) {
                                if (!uiDirty || _entries.subscriptionCount.value == 0) {
                                    null
                                } else {
                                    uiDirty = false
                                    lastUiEmitMs = ProxyClock.monotonicMs()
                                    ArrayList(buffer)
                                }
                            }
                            if (deferredSnapshot != null) {
                                _entries.value = deferredSnapshot
                            }
                        }
                    }
                }
            } else {
                uiDirty = true
            }
        }

        if (immediateSnapshot != null) {
            _entries.value = immediateSnapshot!!
        }

        val verbose = verboseEnabled.get()
        try {
            when (level) {
                LogLevel.DEBUG -> if (verbose) android.util.Log.d(TAG, censored)
                LogLevel.INFO -> if (verbose) android.util.Log.i(TAG, censored)
                LogLevel.WARN -> android.util.Log.w(TAG, censored)
                LogLevel.ERROR -> android.util.Log.e(TAG, censored)
            }
        } catch (_: Throwable) {
            // Safe fallback in pure JVM unit tests
        }
    }
}
