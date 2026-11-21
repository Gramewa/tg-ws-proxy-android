package io.github.tgwsproxy.android.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.tgwsproxy.android.proxy.ActiveRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ProxyTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        ProxyController.init(applicationContext)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        stateJob = scope?.launch {
            combine(
                ProxyController.isRunning,
                ProxyController.isTransitioning,
                ProxyController.currentConfig,
                ProxyController.statsSnapshot.map { it.activeRoute }.distinctUntilChanged(),
            ) { running, transitioning, _, route ->
                Triple(running, transitioning, route)
            }.collect { (running, transitioning, route) ->
                updateTile(running = running, transitioning = transitioning, activeRoute = route)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        ProxyController.init(applicationContext)
        if (ProxyController.isTransitioning.value) return
        if (ProxyController.isRunning.value) {
            ProxyForegroundService.stop(applicationContext)
            updateTile(running = false, transitioning = true)
        } else {
            ProxyForegroundService.start(applicationContext)
            updateTile(running = true, transitioning = true)
        }
    }

    private fun updateTile(
        running: Boolean,
        transitioning: Boolean,
        activeRoute: ActiveRoute = ProxyController.statsSnapshot.value.activeRoute,
    ) {
        val tile = qsTile ?: return
        tile.state = when {
            transitioning -> Tile.STATE_UNAVAILABLE
            running -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        val cfg = ProxyController.settings.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val isRu = cfg.language == "ru"
            tile.subtitle = when {
                transitioning -> if (isRu) "Подождите…" else "Starting…"
                running -> when {
                    cfg.disableSecure && cfg.cfproxyEnabled -> ":${cfg.port} · HTTP :80"
                    activeRoute == ActiveRoute.CF_HTTP80_AUTO ->
                        if (isRu) ":${cfg.port} · Авто :80" else ":${cfg.port} · Auto :80"
                    else -> ":${cfg.port}"
                }
                else -> null
            }
        }
        tile.updateTile()
    }
}
