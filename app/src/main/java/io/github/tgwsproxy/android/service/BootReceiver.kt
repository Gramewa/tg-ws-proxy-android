package io.github.tgwsproxy.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.tgwsproxy.android.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        ProxyController.init(appContext)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(appContext).ensureInitialized()
                if (settings.autoStartOnBoot) {
                    ProxyForegroundService.start(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
