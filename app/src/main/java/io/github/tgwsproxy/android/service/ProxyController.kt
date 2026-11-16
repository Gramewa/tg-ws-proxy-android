package io.github.tgwsproxy.android.service

import android.content.Context
import io.github.tgwsproxy.android.data.AppSettings
import io.github.tgwsproxy.android.data.SettingsRepository
import io.github.tgwsproxy.android.proxy.ProxyConfig
import io.github.tgwsproxy.android.proxy.ProxyLogger
import io.github.tgwsproxy.android.proxy.ProxyStats
import io.github.tgwsproxy.android.proxy.StatsSnapshot
import io.github.tgwsproxy.android.proxy.TgWsProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object ProxyController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opMutex = Mutex()
    private val server = TgWsProxyServer()

    @Volatile
    private lateinit var repository: SettingsRepository

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _statsSnapshot = MutableStateFlow(StatsSnapshot())
    val statsSnapshot: StateFlow<StatsSnapshot> = _statsSnapshot.asStateFlow()

    private val _uptimeSeconds = MutableStateFlow(0L)
    val uptimeSeconds: StateFlow<Long> = _uptimeSeconds.asStateFlow()

    private val _currentConfig = MutableStateFlow<ProxyConfig?>(null)
    val currentConfig: StateFlow<ProxyConfig?> = _currentConfig.asStateFlow()

    private var tickerJob: Job? = null
    private var startedAtMs: Long = 0L

    fun init(context: Context) {
        if (::repository.isInitialized) return
        repository = SettingsRepository(context.applicationContext)
        scope.launch {
            val initial = repository.ensureInitialized()
            _settings.value = initial
            ProxyLogger.setVerbose(initial.verboseLogging)
            repository.settingsFlow.collect { latest ->
                _settings.value = latest
                ProxyLogger.setVerbose(latest.verboseLogging)
            }
        }
    }

    suspend fun startProxy(): Result<Unit> = opMutex.withLock {
        if (_isRunning.value) return Result.success(Unit)
        _isTransitioning.value = true
        _lastError.value = null
        try {
            val current = repository.ensureInitialized()
            _settings.value = current
            val cfg = current.toProxyConfig()
            withContext(Dispatchers.IO) {
                server.start(cfg)
            }
            _currentConfig.value = cfg
            _isRunning.value = true
            startedAtMs = System.currentTimeMillis()
            startTicker()
            Result.success(Unit)
        } catch (exc: Exception) {
            val msg = exc.message ?: exc.javaClass.simpleName
            _lastError.value = msg
            ProxyLogger.e("Failed to start proxy", exc)
            _isRunning.value = false
            _currentConfig.value = null
            Result.failure(exc)
        } finally {
            _isTransitioning.value = false
        }
    }

    suspend fun stopProxy() = opMutex.withLock {
        stopInternal()
    }

    suspend fun restartProxy(): Result<Unit> = opMutex.withLock {
        _isTransitioning.value = true
        _lastError.value = null
        try {
            stopInternal()
            val current = repository.ensureInitialized()
            _settings.value = current
            val cfg = current.toProxyConfig()
            withContext(Dispatchers.IO) {
                server.start(cfg)
            }
            _currentConfig.value = cfg
            _isRunning.value = true
            startedAtMs = System.currentTimeMillis()
            startTicker()
            Result.success(Unit)
        } catch (exc: Exception) {
            val msg = exc.message ?: exc.javaClass.simpleName
            _lastError.value = msg
            ProxyLogger.e("Failed to restart proxy", exc)
            _isRunning.value = false
            _currentConfig.value = null
            Result.failure(exc)
        } finally {
            _isTransitioning.value = false
        }
    }

    suspend fun updateSettings(
        restartIfRunning: Boolean = true,
        transform: (AppSettings) -> AppSettings,
    ) {
        val updated = repository.update(transform)
        _settings.value = updated
        ProxyLogger.setVerbose(updated.verboseLogging)
        if (restartIfRunning && _isRunning.value) {
            restartProxy()
        }
    }

    suspend fun saveSettings(settings: AppSettings, restartIfRunning: Boolean = true) {
        repository.save(settings)
        _settings.value = settings
        ProxyLogger.setVerbose(settings.verboseLogging)
        if (restartIfRunning && _isRunning.value) {
            restartProxy()
        }
    }

    fun setDisableSecureAsync(enabled: Boolean) {
        scope.launch {
            updateSettings(restartIfRunning = true) { it.copy(disableSecure = enabled) }
        }
    }

    fun setCfProxyEnabledAsync(enabled: Boolean) {
        scope.launch {
            updateSettings(restartIfRunning = true) { it.copy(cfproxyEnabled = enabled) }
        }
    }

    fun setH2MediaEnabledAsync(enabled: Boolean) {
        scope.launch {
            updateSettings(restartIfRunning = true) { it.copy(cfproxyH2Media = enabled) }
        }
    }

    fun setSimpleModeAsync(simple: Boolean) {
        scope.launch {
            updateSettings(restartIfRunning = false) { it.copy(simpleMode = simple) }
        }
    }

    fun cyclePaletteAsync() {
        val order = listOf("ocean", "emerald", "amethyst", "sunset")
        scope.launch {
            updateSettings(restartIfRunning = false) { cur ->
                val idx = order.indexOf(cur.colorPalette)
                val next = order[(idx + 1) % order.size]
                cur.copy(colorPalette = next, dynamicColors = false)
            }
        }
    }

    fun cycleThemeAsync() {
        val order = listOf("auto", "dark", "light")
        scope.launch {
            updateSettings(restartIfRunning = false) { cur ->
                val idx = order.indexOf(cur.themeMode)
                val next = order[(idx + 1) % order.size]
                cur.copy(themeMode = next)
            }
        }
    }

    fun toggleLanguageAsync() {
        scope.launch {
            updateSettings(restartIfRunning = false) { cur ->
                cur.copy(language = if (cur.language == "ru") "en" else "ru")
            }
        }
    }

    fun postponeUpdateAsync(version: String, untilMs: Long) {
        scope.launch {
            updateSettings(restartIfRunning = false) { cur ->
                cur.copy(
                    postponedUpdateVersion = version,
                    postponedUpdateUntilMs = untilMs,
                )
            }
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    private suspend fun stopInternal() {
        tickerJob?.cancel()
        tickerJob = null
        withContext(Dispatchers.IO) {
            server.stop()
        }
        _isRunning.value = false
        _currentConfig.value = null
        _uptimeSeconds.value = 0L
        _statsSnapshot.value = ProxyStats.snapshot()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && _isRunning.value) {
                _statsSnapshot.value = ProxyStats.snapshot()
                _uptimeSeconds.value = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L)
                delay(1000L)
            }
        }
    }
}
