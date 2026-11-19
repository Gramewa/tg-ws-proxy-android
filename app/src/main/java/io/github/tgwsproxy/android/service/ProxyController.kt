package io.github.tgwsproxy.android.service

import android.content.Context
import androidx.compose.runtime.Immutable
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Immutable
data class ThroughputTelemetry(
    val downBps: Long = 0L,
    val upBps: Long = 0L,
    val downHistory: List<Float> = EmptyWaveHistory,
    val upHistory: List<Float> = EmptyWaveHistory,
) {
    companion object {
        const val HISTORY_POINTS = 28
        val EmptyWaveHistory: List<Float> = List(HISTORY_POINTS) { 0f }
    }
}

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

    private val _telemetry = MutableStateFlow(ThroughputTelemetry())
    val telemetry: StateFlow<ThroughputTelemetry> = _telemetry.asStateFlow()

    private val _uptimeSeconds = MutableStateFlow(0L)
    val uptimeSeconds: StateFlow<Long> = _uptimeSeconds.asStateFlow()

    private val _currentConfig = MutableStateFlow<ProxyConfig?>(null)
    val currentConfig: StateFlow<ProxyConfig?> = _currentConfig.asStateFlow()

    private var tickerJob: Job? = null
    private var startedAtMs: Long = 0L

    @Volatile
    private var lastNetworkChangeMs: Long = 0L

    private val persistDomainsCallback: (List<String>) -> Unit = { domains ->
        scope.launch {
            runCatching {
                if (::repository.isInitialized) {
                    repository.saveCachedCfDomains(domains)
                }
            }
        }
    }

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
                server.start(cfg, persistDomainsCallback)
            }
            repository.setWasRunning(true)
            _settings.value = current.copy(wasRunning = true)
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
        stopInternal(persistStopped = true)
    }

    fun stopAsync() {
        scope.launch { stopProxy() }
    }

    suspend fun restartProxy(): Result<Unit> = opMutex.withLock {
        _isTransitioning.value = true
        _lastError.value = null
        try {
            stopInternal(persistStopped = false)
            val current = repository.ensureInitialized()
            _settings.value = current
            val cfg = current.toProxyConfig()
            withContext(Dispatchers.IO) {
                server.start(cfg, persistDomainsCallback)
            }
            repository.setWasRunning(true)
            _settings.value = current.copy(wasRunning = true)
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

    fun onNetworkChanged() {
        if (!_isRunning.value) return
        val now = System.currentTimeMillis()
        if (now - lastNetworkChangeMs < 1500L) return
        lastNetworkChangeMs = now
        scope.launch(Dispatchers.IO) {
            runCatching {
                server.onNetworkChanged()
            }
        }
    }

    fun onNetworkLost() {
        if (!_isRunning.value) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                server.onNetworkLost()
            }
        }
    }

    fun setNetworkMetered(metered: Boolean) {
        server.setNetworkMetered(metered)
    }

    fun applyDiagnosticWinner(domain: String?, latencyMs: Long?) {
        if (domain.isNullOrBlank()) return
        server.applyDiagnosticWinner(domain, latencyMs ?: 100L)
    }

    suspend fun updateSettings(
        restartIfRunning: Boolean = true,
        transform: (AppSettings) -> AppSettings,
    ) {
        val previous = _settings.value
        val updated = repository.update(transform)
        _settings.value = updated
        ProxyLogger.setVerbose(updated.verboseLogging)
        if (restartIfRunning && _isRunning.value) {
            applyLiveOrRestart(previous, updated)
        }
    }

    /**
     * Saves settings and applies them if the proxy is running.
     * Returns true if a full restart was performed, false if hot-reloaded or proxy was stopped.
     */
    suspend fun saveSettings(settings: AppSettings, restartIfRunning: Boolean = true): Boolean {
        val previous = _settings.value
        val toSave = settings.copy(wasRunning = _isRunning.value)
        repository.save(toSave)
        _settings.value = toSave
        ProxyLogger.setVerbose(toSave.verboseLogging)
        if (restartIfRunning && _isRunning.value) {
            return applyLiveOrRestart(previous, toSave)
        }
        return false
    }

    private suspend fun applyLiveOrRestart(previous: AppSettings, updated: AppSettings): Boolean {
        if (updated.hasSameBindConfigAs(previous)) {
            opMutex.withLock {
                if (!_isRunning.value) return false
                val cfg = updated.toProxyConfig()
                withContext(Dispatchers.IO) {
                    server.updateConfig(cfg, persistDomainsCallback)
                }
                _currentConfig.value = cfg
            }
            return false
        }
        restartProxy()
        return true
    }

    fun setDisableSecureAsync(enabled: Boolean) {
        scope.launch {
            updateSettings(restartIfRunning = true) { it.copy(disableSecure = enabled) }
        }
    }

    fun setAutoPort80FallbackAsync(enabled: Boolean) {
        scope.launch {
            updateSettings(restartIfRunning = true) { it.copy(autoPort80Fallback = enabled) }
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

    fun setThemeModeAsync(mode: String) {
        scope.launch {
            updateSettings(restartIfRunning = false) { it.copy(themeMode = mode) }
        }
    }

    fun setDynamicColorsAsync(enabled: Boolean) {
        scope.launch {
            updateSettings(restartIfRunning = false) { it.copy(dynamicColors = enabled) }
        }
    }

    fun setLanguageAsync(lang: String) {
        scope.launch {
            updateSettings(restartIfRunning = false) { it.copy(language = lang) }
        }
    }

    fun setAutoStartAsync(enabled: Boolean) {
        scope.launch {
            updateSettings(restartIfRunning = false) { it.copy(autoStartOnBoot = enabled) }
        }
    }

    fun setKeepWakeLockAsync(enabled: Boolean) {
        scope.launch {
            updateSettings(restartIfRunning = false) { it.copy(keepWakeLock = enabled) }
        }
    }

    fun setCheckUpdatesOnStartupAsync(enabled: Boolean) {
        scope.launch {
            updateSettings(restartIfRunning = false) { it.copy(checkUpdatesOnStartup = enabled) }
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

    private suspend fun stopInternal(persistStopped: Boolean) {
        tickerJob?.cancel()
        tickerJob = null
        withContext(Dispatchers.IO) {
            server.stop()
        }
        if (persistStopped && ::repository.isInitialized) {
            runCatching { repository.setWasRunning(false) }
            _settings.value = _settings.value.copy(wasRunning = false)
        }
        _isRunning.value = false
        _currentConfig.value = null
        _uptimeSeconds.value = 0L
        _statsSnapshot.value = ProxyStats.snapshot()
        _telemetry.value = ThroughputTelemetry()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            val initialSnap = ProxyStats.snapshot()
            var prevDown = initialSnap.bytesDown
            var prevUp = initialSnap.bytesUp
            var prevTimeMs = System.currentTimeMillis()
            val downBuf = ArrayDeque<Float>(ThroughputTelemetry.HISTORY_POINTS).apply {
                repeat(ThroughputTelemetry.HISTORY_POINTS) { addLast(0f) }
            }
            val upBuf = ArrayDeque<Float>(ThroughputTelemetry.HISTORY_POINTS).apply {
                repeat(ThroughputTelemetry.HISTORY_POINTS) { addLast(0f) }
            }

            while (isActive && _isRunning.value) {
                val hasSubscribers = _statsSnapshot.subscriptionCount.value > 0 ||
                    _telemetry.subscriptionCount.value > 0 ||
                    _uptimeSeconds.subscriptionCount.value > 0

                if (!hasSubscribers) {
                    // Sleep with zero CPU wakeups while the UI is in the background.
                    combine(
                        _statsSnapshot.subscriptionCount,
                        _telemetry.subscriptionCount,
                        _uptimeSeconds.subscriptionCount,
                    ) { a, b, c -> a + b + c > 0 }.first { it }

                    if (!isActive || !_isRunning.value) break
                    val resetSnap = ProxyStats.snapshot()
                    prevDown = resetSnap.bytesDown
                    prevUp = resetSnap.bytesUp
                    prevTimeMs = System.currentTimeMillis()
                }

                val nowMs = System.currentTimeMillis()
                val snap = ProxyStats.snapshot()
                _statsSnapshot.value = snap
                _uptimeSeconds.value = ((nowMs - startedAtMs) / 1000L).coerceAtLeast(0L)

                val dtSec = ((nowMs - prevTimeMs).coerceAtLeast(250L)) / 1000f
                val downDelta = (snap.bytesDown - prevDown).coerceAtLeast(0L)
                val upDelta = (snap.bytesUp - prevUp).coerceAtLeast(0L)
                val downRate = (downDelta / dtSec).toLong()
                val upRate = (upDelta / dtSec).toLong()

                prevDown = snap.bytesDown
                prevUp = snap.bytesUp
                prevTimeMs = nowMs

                if (downBuf.size >= ThroughputTelemetry.HISTORY_POINTS) downBuf.removeFirst()
                downBuf.addLast(downRate.toFloat())
                if (upBuf.size >= ThroughputTelemetry.HISTORY_POINTS) upBuf.removeFirst()
                upBuf.addLast(upRate.toFloat())

                _telemetry.value = ThroughputTelemetry(
                    downBps = downRate,
                    upBps = upRate,
                    downHistory = downBuf.toList(),
                    upHistory = upBuf.toList(),
                )
                delay(1000L)
            }
        }
    }
}
