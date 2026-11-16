package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntry(
    val timestampMs: Long,
    val level: LogLevel,
    val message: String,
) {
    fun formatLine(): String {
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))
        val tag = when (level) {
            LogLevel.DEBUG -> "DEBUG"
            LogLevel.INFO -> "INFO "
            LogLevel.WARN -> "WARN "
            LogLevel.ERROR -> "ERROR"
        }
        return "$timeFmt  $tag  $message"
    }
}

object DomainCensor {
    private val domainPattern = Regex(
        """(?<![\w-])(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}(?![\w-])"""
    )

    fun censor(message: String): String {
        return domainPattern.replace(message) { match ->
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

    val verboseEnabled = AtomicBoolean(false)

    private val lock = Any()
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

    fun clear() {
        synchronized(lock) {
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
        val entry = LogEntry(System.currentTimeMillis(), level, censored)
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
        try {
            when (level) {
                LogLevel.DEBUG -> android.util.Log.d(TAG, censored)
                LogLevel.INFO -> android.util.Log.i(TAG, censored)
                LogLevel.WARN -> android.util.Log.w(TAG, censored)
                LogLevel.ERROR -> android.util.Log.e(TAG, censored)
            }
        } catch (_: Throwable) {
            // Safe fallback in pure JVM unit tests
        }
    }
}
