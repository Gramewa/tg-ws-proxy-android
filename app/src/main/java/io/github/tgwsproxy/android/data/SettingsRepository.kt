package io.github.tgwsproxy.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.tgwsproxy.android.proxy.DEFAULT_FRONTING_SNI
import io.github.tgwsproxy.android.proxy.DnsResolver
import io.github.tgwsproxy.android.proxy.generateSecretHex
import io.github.tgwsproxy.android.proxy.isValidSecretHex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "proxy_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val SECRET = stringPreferencesKey("secret")
        val DC_IP_RULES = stringPreferencesKey("dc_ip_rules")
        val CFPROXY_ENABLED = booleanPreferencesKey("cfproxy_enabled")
        val CFPROXY_H2_MEDIA = booleanPreferencesKey("cfproxy_h2_media")
        val CFPROXY_CUSTOM_ENABLED = booleanPreferencesKey("cfproxy_custom_enabled")
        val CFPROXY_CUSTOM_DOMAINS = stringPreferencesKey("cfproxy_custom_domains")
        val CFWORKER_DOMAINS = stringPreferencesKey("cfworker_domains")
        val DISABLE_SECURE = booleanPreferencesKey("disable_secure")
        val AUTO_PORT80_FALLBACK = booleanPreferencesKey("auto_port80_fallback")
        val FRONTING_SNI = stringPreferencesKey("fronting_sni")
        val DOH_PROVIDER = stringPreferencesKey("doh_provider")
        val CF_CLEAN_IP = stringPreferencesKey("cf_clean_ip")
        val IP_PREFERENCE = stringPreferencesKey("ip_preference")
        val CACHED_CF_DOMAINS = stringPreferencesKey("cached_cf_domains")
        val FAKE_TLS_DOMAIN = stringPreferencesKey("fake_tls_domain")
        val BUFFER_KB = intPreferencesKey("buffer_kb")
        val POOL_SIZE = intPreferencesKey("pool_size")
        val VERBOSE_LOGGING = booleanPreferencesKey("verbose_logging")
        val PROXY_PROTOCOL = booleanPreferencesKey("proxy_protocol")
        val FORCE_TEST_DC = booleanPreferencesKey("force_test_dc")
        val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val KEEP_WAKE_LOCK = booleanPreferencesKey("keep_wake_lock")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors")
        val LANGUAGE = stringPreferencesKey("language")
        val SIMPLE_MODE = booleanPreferencesKey("simple_mode")
        val CHECK_UPDATES_ON_STARTUP = booleanPreferencesKey("check_updates_on_startup")
        val POSTPONED_UPDATE_VERSION = stringPreferencesKey("postponed_update_version")
        val POSTPONED_UPDATE_UNTIL_MS = longPreferencesKey("postponed_update_until_ms")
        val WAS_RUNNING = booleanPreferencesKey("was_running")
    }

    private fun defaultLanguage(): String {
        val lang = Locale.getDefault().language.lowercase()
        return if (lang == "ru" || lang == "be" || lang == "uk" || lang == "kk") "ru" else "en"
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            host = prefs[Keys.HOST] ?: "127.0.0.1",
            port = prefs[Keys.PORT] ?: 1443,
            secret = prefs[Keys.SECRET] ?: "",
            dcIpRules = prefs[Keys.DC_IP_RULES] ?: AppSettings.DEFAULT_DC_RULES,
            cfproxyEnabled = prefs[Keys.CFPROXY_ENABLED] ?: true,
            cfproxyH2Media = prefs[Keys.CFPROXY_H2_MEDIA] ?: true,
            cfproxyCustomDomainsEnabled = prefs[Keys.CFPROXY_CUSTOM_ENABLED] ?: false,
            cfproxyCustomDomains = prefs[Keys.CFPROXY_CUSTOM_DOMAINS] ?: "",
            cfworkerDomains = prefs[Keys.CFWORKER_DOMAINS] ?: "",
            disableSecure = prefs[Keys.DISABLE_SECURE] ?: false,
            autoPort80Fallback = prefs[Keys.AUTO_PORT80_FALLBACK] ?: true,
            frontingSni = prefs[Keys.FRONTING_SNI] ?: DEFAULT_FRONTING_SNI,
            dohProvider = prefs[Keys.DOH_PROVIDER] ?: DnsResolver.DOH_SYSTEM,
            cfCleanIp = prefs[Keys.CF_CLEAN_IP] ?: "",
            ipPreference = prefs[Keys.IP_PREFERENCE] ?: DnsResolver.IP_PREF_AUTO,
            cachedCfDomains = prefs[Keys.CACHED_CF_DOMAINS] ?: "",
            fakeTlsDomain = prefs[Keys.FAKE_TLS_DOMAIN] ?: "",
            bufferKb = prefs[Keys.BUFFER_KB] ?: 256,
            poolSize = prefs[Keys.POOL_SIZE] ?: 4,
            verboseLogging = prefs[Keys.VERBOSE_LOGGING] ?: false,
            proxyProtocol = prefs[Keys.PROXY_PROTOCOL] ?: false,
            forceTestDc = prefs[Keys.FORCE_TEST_DC] ?: false,
            autoStartOnBoot = prefs[Keys.AUTO_START_ON_BOOT] ?: false,
            keepWakeLock = prefs[Keys.KEEP_WAKE_LOCK] ?: false,
            themeMode = prefs[Keys.THEME_MODE] ?: "auto",
            dynamicColors = prefs[Keys.DYNAMIC_COLORS] ?: false,
            language = prefs[Keys.LANGUAGE] ?: defaultLanguage(),
            simpleMode = prefs[Keys.SIMPLE_MODE] ?: true,
            checkUpdatesOnStartup = prefs[Keys.CHECK_UPDATES_ON_STARTUP] ?: true,
            postponedUpdateVersion = prefs[Keys.POSTPONED_UPDATE_VERSION] ?: "",
            postponedUpdateUntilMs = prefs[Keys.POSTPONED_UPDATE_UNTIL_MS] ?: 0L,
            wasRunning = prefs[Keys.WAS_RUNNING] ?: false,
        )
    }

    suspend fun ensureInitialized(): AppSettings {
        context.dataStore.edit { prefs ->
            val currentSecret = prefs[Keys.SECRET].orEmpty()
            if (!isValidSecretHex(currentSecret)) {
                prefs[Keys.SECRET] = generateSecretHex()
            }
            if (prefs[Keys.LANGUAGE] == null) {
                prefs[Keys.LANGUAGE] = defaultLanguage()
            }
        }
        return settingsFlow.first()
    }

    suspend fun setWasRunning(running: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WAS_RUNNING] = running
        }
    }

    suspend fun saveCachedCfDomains(domains: List<String>) {
        val joined = domains.joinToString("\n")
        context.dataStore.edit { prefs ->
            prefs[Keys.CACHED_CF_DOMAINS] = joined
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val current = ensureInitialized()
        val updated = transform(current)
        save(updated)
        return updated
    }

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HOST] = settings.host
            prefs[Keys.PORT] = settings.port
            prefs[Keys.SECRET] = if (isValidSecretHex(settings.secret)) {
                settings.secret.lowercase()
            } else {
                generateSecretHex()
            }
            prefs[Keys.DC_IP_RULES] = settings.dcIpRules
            prefs[Keys.CFPROXY_ENABLED] = settings.cfproxyEnabled
            prefs[Keys.CFPROXY_H2_MEDIA] = settings.cfproxyH2Media
            prefs[Keys.CFPROXY_CUSTOM_ENABLED] = settings.cfproxyCustomDomainsEnabled
            prefs[Keys.CFPROXY_CUSTOM_DOMAINS] = settings.cfproxyCustomDomains
            prefs[Keys.CFWORKER_DOMAINS] = settings.cfworkerDomains
            prefs[Keys.DISABLE_SECURE] = settings.disableSecure
            prefs[Keys.AUTO_PORT80_FALLBACK] = settings.autoPort80Fallback
            prefs[Keys.FRONTING_SNI] = settings.frontingSni
            prefs[Keys.DOH_PROVIDER] = settings.dohProvider
            prefs[Keys.CF_CLEAN_IP] = settings.cfCleanIp
            prefs[Keys.IP_PREFERENCE] = settings.ipPreference
            if (settings.cachedCfDomains.isNotEmpty()) {
                prefs[Keys.CACHED_CF_DOMAINS] = settings.cachedCfDomains
            }
            prefs[Keys.FAKE_TLS_DOMAIN] = settings.fakeTlsDomain
            prefs[Keys.BUFFER_KB] = settings.bufferKb
            prefs[Keys.POOL_SIZE] = settings.poolSize
            prefs[Keys.VERBOSE_LOGGING] = settings.verboseLogging
            prefs[Keys.PROXY_PROTOCOL] = settings.proxyProtocol
            prefs[Keys.FORCE_TEST_DC] = settings.forceTestDc
            prefs[Keys.AUTO_START_ON_BOOT] = settings.autoStartOnBoot
            prefs[Keys.KEEP_WAKE_LOCK] = settings.keepWakeLock
            prefs[Keys.THEME_MODE] = settings.themeMode
            prefs[Keys.DYNAMIC_COLORS] = settings.dynamicColors
            prefs[Keys.LANGUAGE] = settings.language
            prefs[Keys.SIMPLE_MODE] = settings.simpleMode
            prefs[Keys.CHECK_UPDATES_ON_STARTUP] = settings.checkUpdatesOnStartup
            prefs[Keys.POSTPONED_UPDATE_VERSION] = settings.postponedUpdateVersion
            prefs[Keys.POSTPONED_UPDATE_UNTIL_MS] = settings.postponedUpdateUntilMs
            prefs[Keys.WAS_RUNNING] = settings.wasRunning
        }
    }
}
