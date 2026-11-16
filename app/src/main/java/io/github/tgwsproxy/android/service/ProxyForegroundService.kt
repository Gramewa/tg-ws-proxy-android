package io.github.tgwsproxy.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.tgwsproxy.android.MainActivity
import io.github.tgwsproxy.android.R
import io.github.tgwsproxy.android.proxy.buildTelegramProxyLink
import io.github.tgwsproxy.android.proxy.humanBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProxyForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "tg_ws_proxy_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "io.github.tgwsproxy.android.ACTION_START"
        const val ACTION_STOP = "io.github.tgwsproxy.android.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        ProxyController.init(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                ProxyController.stopProxy()
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        startForegroundWithNotification(buildNotification())

        serviceScope.launch {
            val res = ProxyController.startProxy()
            if (res.isFailure) {
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            updateLocks(ProxyController.settings.value.keepWakeLock)
            startNotificationUpdater()
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startNotificationUpdater() {
        if (notificationJob?.isActive == true) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationJob = serviceScope.launch {
            while (isActive) {
                if (!ProxyController.isRunning.value && !ProxyController.isTransitioning.value) {
                    releaseLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
                updateLocks(ProxyController.settings.value.keepWakeLock)
                manager.notify(NOTIFICATION_ID, buildNotification())
                delay(2000L)
            }
        }
    }

    private fun buildNotification(): Notification {
        val cfg = ProxyController.currentConfig.value ?: ProxyController.settings.value.toProxyConfig()
        val stats = ProxyController.statsSnapshot.value
        val isRu = ProxyController.settings.value.language == "ru"

        val title = if (cfg.disableSecure) {
            if (isRu) "TG WS Proxy • HTTP :80" else "TG WS Proxy • HTTP :80"
        } else {
            "TG WS Proxy • ${cfg.host}:${cfg.port}"
        }
        val content = if (isRu) {
            "↑ ${humanBytes(stats.bytesUp)}  ↓ ${humanBytes(stats.bytesDown)}  • Активных: ${stats.connectionsActive}"
        } else {
            "↑ ${humanBytes(stats.bytesUp)}  ↓ ${humanBytes(stats.bytesDown)}  • Active: ${stats.connectionsActive}"
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val tgUri = Uri.parse(buildTelegramProxyLink(cfg))
        val tgIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(Intent.ACTION_VIEW, tgUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(
                R.drawable.ic_notification,
                if (isRu) "В Telegram" else "Telegram",
                tgIntent,
            )
            .addAction(
                R.drawable.ic_stop,
                if (isRu) "Стоп" else "Stop",
                stopIntent,
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun updateLocks(enabled: Boolean) {
        if (enabled) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWsProxy::ProxyWakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "TgWsProxy::WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } else {
            releaseLocks()
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
