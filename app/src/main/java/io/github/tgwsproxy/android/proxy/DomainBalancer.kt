package io.github.tgwsproxy.android.proxy

import java.security.SecureRandom
import kotlin.math.min

class DomainBalancer {
    companion object {
        private const val COOLDOWN_INITIAL_MS = 30_000L
        private const val COOLDOWN_MAX_MS = 300_000L
        private const val TLS_FAIL_PREFER_P80_MS = 180_000L
        private const val CF_TLS_BLOCKED_WINDOW_MS = 10 * 60_000L
        private const val EWMA_ALPHA = 0.35
        private val KNOWN_DCS = listOf(1, 2, 3, 4, 5, 203)
    }

    private data class DomainHealth(
        var ewmaLatencyMs: Double = -1.0,
        var consecutiveFailures: Int = 0,
        var cooldownUntilMs: Long = 0L,
        var tlsFailedUntilMs: Long = 0L,
        var lastSuccessMs: Long = 0L,
    )

    private val lock = Any()
    private val random = SecureRandom()
    private var domains: List<String> = emptyList()
    private val dcToDomain = HashMap<Int, String>()
    private val health = HashMap<String, DomainHealth>()
    private var cfTlsBlockedUntilMs: Long = 0L

    fun updateDomainsList(domainsList: List<String>) {
        if (domainsList.isEmpty()) return
        synchronized(lock) {
            if (sameMultiset(domains, domainsList)) return
            domains = domainsList.toList()
            val validSet = domains.toHashSet()
            health.keys.retainAll(validSet)
            dcToDomain.entries.removeAll { it.value !in validSet }
            for (dcId in KNOWN_DCS) {
                if (dcId !in dcToDomain) {
                    dcToDomain[dcId] = domains[random.nextInt(domains.size)]
                }
            }
        }
    }

    fun getDomainsSnapshot(): List<String> = synchronized(lock) { domains.toList() }

    fun updateDomainForDc(dcId: Int, domain: String): Boolean {
        synchronized(lock) {
            if (dcToDomain[dcId] == domain) return false
            dcToDomain[dcId] = domain
            return true
        }
    }

    fun reportSuccess(
        dcId: Int,
        domain: String,
        latencyMs: Long,
        usedPort80: Boolean = false,
        nowMs: Long = ProxyClock.monotonicMs(),
    ): Boolean {
        synchronized(lock) {
            val h = health.getOrPut(domain) { DomainHealth() }
            val clampedRtt = latencyMs.coerceIn(1L, 15_000L).toDouble()
            h.ewmaLatencyMs = if (h.ewmaLatencyMs < 0.0) {
                clampedRtt
            } else {
                EWMA_ALPHA * clampedRtt + (1.0 - EWMA_ALPHA) * h.ewmaLatencyMs
            }
            h.consecutiveFailures = 0
            h.cooldownUntilMs = 0L
            if (!usedPort80) {
                h.tlsFailedUntilMs = 0L
                cfTlsBlockedUntilMs = 0L
            }
            h.lastSuccessMs = nowMs
            val changed = dcToDomain[dcId] != domain
            dcToDomain[dcId] = domain
            return changed
        }
    }

    fun reportFailure(
        domain: String,
        tlsSpecific: Boolean = false,
        nowMs: Long = ProxyClock.monotonicMs(),
    ) {
        synchronized(lock) {
            val h = health.getOrPut(domain) { DomainHealth() }
            if (tlsSpecific) {
                h.tlsFailedUntilMs = nowMs + TLS_FAIL_PREFER_P80_MS
                cfTlsBlockedUntilMs = maxOf(cfTlsBlockedUntilMs, nowMs + CF_TLS_BLOCKED_WINDOW_MS)
            } else {
                h.consecutiveFailures++
                val exp = min(h.consecutiveFailures - 1, 4)
                val cooldown = min(COOLDOWN_INITIAL_MS shl exp, COOLDOWN_MAX_MS)
                h.cooldownUntilMs = nowMs + cooldown
                dcToDomain.entries.removeAll { it.value == domain }
            }
        }
    }

    fun isCfTlsBlocked(nowMs: Long = ProxyClock.monotonicMs()): Boolean {
        synchronized(lock) {
            return cfTlsBlockedUntilMs > nowMs
        }
    }

    fun markCfTlsBlocked(nowMs: Long = ProxyClock.monotonicMs()) {
        synchronized(lock) {
            cfTlsBlockedUntilMs = maxOf(cfTlsBlockedUntilMs, nowMs + CF_TLS_BLOCKED_WINDOW_MS)
        }
    }

    fun clearCfTlsBlocked() {
        synchronized(lock) {
            cfTlsBlockedUntilMs = 0L
            for (h in health.values) {
                h.tlsFailedUntilMs = 0L
            }
        }
    }

    fun shouldPreferPort80(domain: String, nowMs: Long = ProxyClock.monotonicMs()): Boolean {
        synchronized(lock) {
            if (cfTlsBlockedUntilMs > nowMs) return true
            val until = health[domain]?.tlsFailedUntilMs ?: 0L
            return until > nowMs
        }
    }

    fun isDomainInCooldown(domain: String, nowMs: Long = ProxyClock.monotonicMs()): Boolean {
        synchronized(lock) {
            return (health[domain]?.cooldownUntilMs ?: 0L) > nowMs
        }
    }

    fun applyProbeWinner(domain: String, latencyMs: Long, nowMs: Long = ProxyClock.monotonicMs()) {
        val norm = domain.trim().lowercase()
        if (norm.isEmpty()) return
        synchronized(lock) {
            if (norm !in domains) {
                domains = listOf(norm) + domains
            }
            val h = health.getOrPut(norm) { DomainHealth() }
            h.ewmaLatencyMs = latencyMs.coerceIn(1L, 15_000L).toDouble()
            h.consecutiveFailures = 0
            h.cooldownUntilMs = 0L
            h.lastSuccessMs = nowMs
            for (dcId in KNOWN_DCS) {
                dcToDomain[dcId] = norm
            }
        }
    }

    fun onNetworkChanged() {
        synchronized(lock) {
            cfTlsBlockedUntilMs = 0L
            for (h in health.values) {
                h.consecutiveFailures = 0
                h.cooldownUntilMs = 0L
                h.tlsFailedUntilMs = 0L
            }
        }
    }

    fun getDomainsForDc(dcId: Int, nowMs: Long = ProxyClock.monotonicMs()): List<String> {
        synchronized(lock) {
            if (domains.isEmpty()) return emptyList()
            val currentDomain = dcToDomain[dcId]

            val healthyMeasured = ArrayList<Pair<String, Double>>()
            val healthyUntested = ArrayList<String>()
            val cooling = ArrayList<Pair<String, Long>>()

            for (domain in domains) {
                val h = health[domain]
                val cd = h?.cooldownUntilMs ?: 0L
                if (cd > nowMs) {
                    cooling.add(domain to cd)
                } else if (domain != currentDomain) {
                    val rtt = h?.ewmaLatencyMs ?: -1.0
                    if (rtt >= 0.0) {
                        healthyMeasured.add(domain to rtt)
                    } else {
                        healthyUntested.add(domain)
                    }
                }
            }

            healthyMeasured.sortBy { it.second }
            healthyUntested.shuffle(random)
            cooling.sortBy { it.second }

            val ordered = ArrayList<String>(domains.size)
            if (currentDomain != null && (health[currentDomain]?.cooldownUntilMs ?: 0L) <= nowMs) {
                ordered.add(currentDomain)
            }
            for ((d, _) in healthyMeasured) ordered.add(d)
            for (d in healthyUntested) ordered.add(d)
            for ((d, _) in cooling) ordered.add(d)
            return ordered
        }
    }

    private fun sameMultiset(a: List<String>, b: List<String>): Boolean {
        if (a.size != b.size) return false
        return a.groupingBy { it }.eachCount() == b.groupingBy { it }.eachCount()
    }
}
