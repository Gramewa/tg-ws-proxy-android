package io.github.tgwsproxy.android.proxy

import android.os.SystemClock

/**
 * Monotonic clock for intervals, timeouts, TTLs, and cooldowns.
 * Immune to NTP / timezone wall-clock jumps backwards.
 * Uses Android's [SystemClock.elapsedRealtime] (which advances in Doze) with a strictly
 * positive [System.nanoTime] fallback for pure JVM unit tests.
 */
object ProxyClock {
    private val originNanos: Long = System.nanoTime()
    private const val JVM_MONOTONIC_BASE_MS = 1_000_000L

    private val useElapsedRealtime: Boolean = try {
        SystemClock.elapsedRealtime() > 0L
    } catch (_: Throwable) {
        false
    }

    fun monotonicMs(): Long {
        return if (useElapsedRealtime) {
            SystemClock.elapsedRealtime()
        } else {
            JVM_MONOTONIC_BASE_MS + ((System.nanoTime() - originNanos) / 1_000_000L)
        }
    }

    fun wallClockMs(): Long = System.currentTimeMillis()
}
