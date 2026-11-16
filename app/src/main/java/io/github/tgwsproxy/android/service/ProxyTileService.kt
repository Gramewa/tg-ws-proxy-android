package io.github.tgwsproxy.android.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProxyTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        ProxyController.init(applicationContext)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        stateJob = scope?.launch {
            ProxyController.isRunning.collect { running ->
                updateTile(running)
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
        if (ProxyController.isRunning.value) {
            ProxyForegroundService.stop(applicationContext)
            updateTile(false)
        } else {
            ProxyForegroundService.start(applicationContext)
            updateTile(true)
        }
    }

    private fun updateTile(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val cfg = ProxyController.settings.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) {
                if (cfg.disableSecure) ":${cfg.port} • HTTP 80" else ":${cfg.port}"
            } else {
                null
            }
        }
        tile.updateTile()
    }
}
