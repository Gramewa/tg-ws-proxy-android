package io.github.tgwsproxy.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.tgwsproxy.android.MainActivity
import io.github.tgwsproxy.android.R
import io.github.tgwsproxy.android.proxy.ProxyLogger
import io.github.tgwsproxy.android.proxy.ProxyStats
import io.github.tgwsproxy.android.proxy.buildTelegramProxyLink
import io.github.tgwsproxy.android.proxy.humanBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { exc ->
                ProxyLogger.w("Cannot start foreground service: ${exc.javaClass.simpleName}: ${exc.message}")
            }
        }

        fun stop(context: Context) {
            ProxyController.stopAsync()
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var lifecycleWatchJob: Job? = null
    private var lockWatchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: BroadcastReceiver? = null

    private val screenInteractive = MutableStateFlow(true)

    @Volatile private var activeNetworkHandle: Long? = null
    @Volatile private var activeVpnTransport: Boolean? = null
    @Volatile private var activeValidated: Boolean = true
    @Volatile private var activeLinkSignature: String? = null
    @Volatile private var networkLost: Boolean = false

    override fun onCreate() {
        super.onCreate()
        ProxyController.init(applicationContext)
        createNotificationChannel()
        registerScreenWatcher()
        registerNetworkWatcher()
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

        startForegroundWithNotification(buildNotificationPayload().second)

        serviceScope.launch {
            val res = ProxyController.startProxy()
            if (res.isFailure) {
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            startReactiveWatchers()
            startNotificationUpdater()
        }
        return START_STICKY
    }

    private fun registerScreenWatcher() {
        if (screenReceiver != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        screenInteractive.value = pm?.isInteractive ?: true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> screenInteractive.value = true
                    Intent.ACTION_SCREEN_OFF -> screenInteractive.value = false
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver = receiver
        }
    }

    private fun unregisterScreenWatcher() {
        val r = screenReceiver ?: return
        screenReceiver = null
        runCatching { unregisterReceiver(r) }
    }

    private fun registerNetworkWatcher() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val initialCaps = cm.activeNetwork?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        if (initialCaps != null) {
            val metered = !initialCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            ProxyController.setNetworkMetered(metered)
            activeVpnTransport = initialCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            activeValidated = initialCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val handle = network.networkHandle
                val previous = activeNetworkHandle
                val wasLost = networkLost
                activeNetworkHandle = handle
                networkLost = false
                if (wasLost || (previous != null && previous != handle)) {
                    activeLinkSignature = null
                    ProxyController.onNetworkChanged()
                }
            }

            override fun onLost(network: Network) {
                if (network.networkHandle == activeNetworkHandle) {
                    networkLost = true
                    ProxyController.onNetworkLost()
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val metered = !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                ProxyController.setNetworkMetered(metered)

                val vpn = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val prevVpn = activeVpnTransport
                val prevValidated = activeValidated
                activeVpnTransport = vpn
                activeValidated = validated

                if ((prevVpn != null && prevVpn != vpn) || (!prevValidated && validated && networkLost)) {
                    networkLost = false
                    ProxyController.onNetworkChanged()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network.networkHandle != activeNetworkHandle) return
                val addrs = linkProperties.linkAddresses
                    .mapNotNull { it.address?.hostAddress }
                    .sorted()
                    .joinToString(",")
                val sig = "${linkProperties.interfaceName.orEmpty()}:$addrs"
                val prev = activeLinkSignature
                activeLinkSignature = sig
                if (prev != null && prev != sig) {
                    ProxyController.onNetworkChanged()
                }
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure { exc ->
            ProxyLogger.w("NetworkCallback registration skipped: ${exc.message}")
        }
    }

    private fun unregisterNetworkWatcher() {
        val cb = networkCallback ?: return
        networkCallback = null
        activeNetworkHandle = null
        activeVpnTransport = null
        activeLinkSignature = null
        networkLost = false
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
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

    private fun startReactiveWatchers() {
        if (lifecycleWatchJob?.isActive != true) {
            lifecycleWatchJob = serviceScope.launch {
                combine(
                    ProxyController.isRunning,
                    ProxyController.isTransitioning,
                ) { running, transitioning -> running || transitioning }
                    .collect { active ->
                        if (!active) {
                            releaseLocks()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
            }
        }
        if (lockWatchJob?.isActive != true) {
            lockWatchJob = serviceScope.launch {
                ProxyController.settings
                    .map { it.keepWakeLock }
                    .distinctUntilChanged()
                    .collect { keep ->
                        updateLocks(keep)
                    }
            }
        }
    }

    private fun startNotificationUpdater() {
        if (notificationJob?.isActive == true) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationJob = serviceScope.launch {
            var lastDigest = ""
            screenInteractive.collectLatest { interactive ->
                if (!interactive) {
                    // Screen off / Doze: zero periodic notification wakeups.
                    return@collectLatest
                }
                while (isActive) {
                    val (digest, notification) = buildNotificationPayload()
                    if (digest != lastDigest) {
                        lastDigest = digest
                        manager.notify(NOTIFICATION_ID, notification)
                    }
                    delay(2500L)
                }
            }
        }
    }

    private fun buildNotificationPayload(): Pair<String, Notification> {
        val cfg = ProxyController.currentConfig.value ?: ProxyController.settings.value.toProxyConfig()
        val stats = ProxyStats.snapshot()
        val isRu = ProxyController.settings.value.language == "ru"

        val title = if (cfg.disableSecure) {
            "TG WS Proxy · HTTP :80"
        } else {
            "TG WS Proxy · ${cfg.host}:${cfg.port}"
        }
        val content = if (isRu) {
            "↓ ${humanBytes(stats.bytesDown)}  ↑ ${humanBytes(stats.bytesUp)} · Активных: ${stats.connectionsActive}"
        } else {
            "↓ ${humanBytes(stats.bytesDown)}  ↑ ${humanBytes(stats.bytesUp)} · Active: ${stats.connectionsActive}"
        }
        val digest = "$isRu|$title|$content"

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

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
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

        return digest to notification
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
        lifecycleWatchJob?.cancel()
        lockWatchJob?.cancel()
        unregisterScreenWatcher()
        unregisterNetworkWatcher()
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
