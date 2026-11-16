package io.github.tgwsproxy.android.proxy

import java.security.SecureRandom

class DomainBalancer {
    private val lock = Any()
    private val random = SecureRandom()
    private var domains: List<String> = emptyList()
    private val dcToDomain = HashMap<Int, String>()

    fun updateDomainsList(domainsList: List<String>) {
        if (domainsList.isEmpty()) return
        synchronized(lock) {
            if (sameMultiset(domains, domainsList)) return
            domains = domainsList.toList()
            dcToDomain.clear()
            for (dcId in listOf(1, 2, 3, 4, 5, 203)) {
                dcToDomain[dcId] = domains[random.nextInt(domains.size)]
            }
        }
    }

    fun updateDomainForDc(dcId: Int, domain: String): Boolean {
        synchronized(lock) {
            if (dcToDomain[dcId] == domain) return false
            dcToDomain[dcId] = domain
            return true
        }
    }

    fun getDomainsForDc(dcId: Int): List<String> {
        val (currentDomain, snapshot) = synchronized(lock) {
            dcToDomain[dcId] to domains.toMutableList()
        }
        if (snapshot.isEmpty()) return emptyList()
        snapshot.shuffle(random)
        val ordered = ArrayList<String>(snapshot.size)
        if (currentDomain != null) {
            ordered.add(currentDomain)
        }
        for (domain in snapshot) {
            if (domain != currentDomain) {
                ordered.add(domain)
            }
        }
        return ordered
    }

    private fun sameMultiset(a: List<String>, b: List<String>): Boolean {
        if (a.size != b.size) return false
        return a.groupingBy { it }.eachCount() == b.groupingBy { it }.eachCount()
    }
}
