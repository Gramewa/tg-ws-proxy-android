package io.github.tgwsproxy.android.proxy

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class ActiveRoute {
    NONE,
    DIRECT_WS,
    CF_H2,
    CF_TLS,
    CF_HTTP80,
    CF_HTTP80_AUTO,
    CF_WORKER,
    TCP_FALLBACK,
}

data class StatsSnapshot(
    val connectionsTotal: Long = 0,
    val connectionsActive: Long = 0,
    val connectionsWs: Long = 0,
    val connectionsH2: Long = 0,
    val h2TcpConnections: Long = 0,
    val h2Requests: Long = 0,
    val h2Errors: Long = 0,
    val connectionsTcpFallback: Long = 0,
    val connectionsCfproxy: Long = 0,
    val connectionsFronting: Long = 0,
    val connectionsBad: Long = 0,
    val connectionsMasked: Long = 0,
    val wsErrors: Long = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val poolHits: Long = 0,
    val poolMisses: Long = 0,
    val cfPoolHits: Long = 0,
    val cfPoolMisses: Long = 0,
    val activeRoute: ActiveRoute = ActiveRoute.NONE,
) {
    fun summary(): String {
        val poolTotal = poolHits + poolMisses
        val poolStr = if (poolTotal > 0) "$poolHits/$poolTotal" else "n/a"
        val cfPoolTotal = cfPoolHits + cfPoolMisses
        val cfPoolStr = if (cfPoolTotal > 0) "$cfPoolHits/$cfPoolTotal" else "n/a"
        return "total=$connectionsTotal " +
            "active=$connectionsActive " +
            "ws=$connectionsWs " +
            "h2=$connectionsH2 " +
            "h2_tcp=$h2TcpConnections " +
            "h2_req=$h2Requests " +
            "h2_err=$h2Errors " +
            "tcp_fb=$connectionsTcpFallback " +
            "cf=$connectionsCfproxy " +
            "front=$connectionsFronting " +
            "bad=$connectionsBad " +
            "masked=$connectionsMasked " +
            "err=$wsErrors " +
            "pool=$poolStr " +
            "cf_pool=$cfPoolStr " +
            "route=${activeRoute.name.lowercase(Locale.US)} " +
            "up=${humanBytes(bytesUp)} " +
            "down=${humanBytes(bytesDown)}"
    }
}

fun humanBytes(n: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = n.toDouble()
    for (i in units.indices) {
        if (kotlin.math.abs(value) < 1024.0 || i == units.lastIndex) {
            return String.format(Locale.US, "%.1f%s", value, units[i])
        }
        value /= 1024.0
    }
    return String.format(Locale.US, "%.1fTB", value)
}

object ProxyStats {
    val connectionsTotal = AtomicLong(0)
    val connectionsActive = AtomicLong(0)
    val connectionsWs = AtomicLong(0)
    val connectionsH2 = AtomicLong(0)
    val h2TcpConnections = AtomicLong(0)
    val h2Requests = AtomicLong(0)
    val h2Errors = AtomicLong(0)
    val connectionsTcpFallback = AtomicLong(0)
    val connectionsCfproxy = AtomicLong(0)
    val connectionsFronting = AtomicLong(0)
    val connectionsBad = AtomicLong(0)
    val connectionsMasked = AtomicLong(0)
    val wsErrors = AtomicLong(0)
    val bytesUp = AtomicLong(0)
    val bytesDown = AtomicLong(0)
    val poolHits = AtomicLong(0)
    val poolMisses = AtomicLong(0)
    val cfPoolHits = AtomicLong(0)
    val cfPoolMisses = AtomicLong(0)
    val activeRoute = AtomicReference(ActiveRoute.NONE)

    @Volatile
    var onActiveRouteChanged: ((ActiveRoute) -> Unit)? = null

    fun setActiveRoute(route: ActiveRoute) {
        if (activeRoute.getAndSet(route) != route) {
            onActiveRouteChanged?.invoke(route)
        }
    }

    fun clearActiveRoute() {
        setActiveRoute(ActiveRoute.NONE)
    }

    fun snapshot(): StatsSnapshot = StatsSnapshot(
        connectionsTotal = connectionsTotal.get(),
        connectionsActive = connectionsActive.get(),
        connectionsWs = connectionsWs.get(),
        connectionsH2 = connectionsH2.get(),
        h2TcpConnections = h2TcpConnections.get(),
        h2Requests = h2Requests.get(),
        h2Errors = h2Errors.get(),
        connectionsTcpFallback = connectionsTcpFallback.get(),
        connectionsCfproxy = connectionsCfproxy.get(),
        connectionsFronting = connectionsFronting.get(),
        connectionsBad = connectionsBad.get(),
        connectionsMasked = connectionsMasked.get(),
        wsErrors = wsErrors.get(),
        bytesUp = bytesUp.get(),
        bytesDown = bytesDown.get(),
        poolHits = poolHits.get(),
        poolMisses = poolMisses.get(),
        cfPoolHits = cfPoolHits.get(),
        cfPoolMisses = cfPoolMisses.get(),
        activeRoute = activeRoute.get(),
    )

    fun summary(): String = snapshot().summary()

    fun reset() {
        connectionsTotal.set(0)
        connectionsActive.set(0)
        connectionsWs.set(0)
        connectionsH2.set(0)
        h2TcpConnections.set(0)
        h2Requests.set(0)
        h2Errors.set(0)
        connectionsTcpFallback.set(0)
        connectionsCfproxy.set(0)
        connectionsFronting.set(0)
        connectionsBad.set(0)
        connectionsMasked.set(0)
        wsErrors.set(0)
        bytesUp.set(0)
        bytesDown.set(0)
        poolHits.set(0)
        poolMisses.set(0)
        cfPoolHits.set(0)
        cfPoolMisses.set(0)
        clearActiveRoute()
    }
}
