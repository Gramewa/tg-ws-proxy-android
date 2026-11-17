package io.github.tgwsproxy.android.data

import io.github.tgwsproxy.android.proxy.DcParseResult
import io.github.tgwsproxy.android.proxy.ProxyConfig
import io.github.tgwsproxy.android.proxy.coerceDomainList
import io.github.tgwsproxy.android.proxy.generateSecretHex
import io.github.tgwsproxy.android.proxy.isValidDomain
import io.github.tgwsproxy.android.proxy.isValidSecretHex
import io.github.tgwsproxy.android.proxy.normalizeDomainPool
import io.github.tgwsproxy.android.proxy.parseDcIpRules

data class AppSettings(
    val host: String = "127.0.0.1",
    val port: Int = 1443,
    val secret: String = "",
    val dcIpRules: String = DEFAULT_DC_RULES,
    val cfproxyEnabled: Boolean = true,
    val cfproxyH2Media: Boolean = true,
    val cfproxyCustomDomainsEnabled: Boolean = false,
    val cfproxyCustomDomains: String = "",
    val cfworkerDomains: String = "",
    val disableSecure: Boolean = false,
    val fakeTlsDomain: String = "",
    val bufferKb: Int = 256,
    val poolSize: Int = 4,
    val verboseLogging: Boolean = false,
    val proxyProtocol: Boolean = false,
    val forceTestDc: Boolean = false,
    val autoStartOnBoot: Boolean = false,
    val keepWakeLock: Boolean = false,
    val themeMode: String = "auto",
    val colorPalette: String = "obsidian",
    val dynamicColors: Boolean = false,
    val language: String = "ru",
    val simpleMode: Boolean = true,
    val checkUpdatesOnStartup: Boolean = true,
    val postponedUpdateVersion: String = "",
    val postponedUpdateUntilMs: Long = 0L,
) {
    companion object {
        const val DEFAULT_DC_RULES = "2:149.154.167.220\n4:149.154.167.220"
    }

    fun toProxyConfig(): ProxyConfig {
        val dcMap = when (val parsed = parseDcIpRules(dcIpRules)) {
            is DcParseResult.Success -> parsed.map
            is DcParseResult.Error -> mapOf(
                2 to "149.154.167.220",
                4 to "149.154.167.220",
            )
        }
        val cleanSecret = if (isValidSecretHex(secret.trim())) {
            secret.trim().lowercase()
        } else {
            generateSecretHex()
        }
        return ProxyConfig(
            host = host.trim().ifEmpty { "127.0.0.1" },
            port = port.coerceIn(1, 65535),
            secret = cleanSecret,
            dcRedirects = dcMap,
            bufferSize = bufferKb.coerceIn(4, 4096) * 1024,
            poolSize = poolSize.coerceIn(0, 16),
            fallbackCfproxy = cfproxyEnabled,
            cfproxyUserDomains = if (cfproxyCustomDomainsEnabled) {
                normalizeDomainPool(coerceDomainList(cfproxyCustomDomains))
            } else {
                emptyList()
            },
            cfproxyWorkerDomains = normalizeDomainPool(coerceDomainList(cfworkerDomains)),
            cfproxyH2Media = cfproxyH2Media,
            disableSecure = disableSecure,
            fakeTlsDomain = fakeTlsDomain.trim().lowercase().let { if (isValidDomain(it)) it else "" },
            proxyProtocol = proxyProtocol,
            forceTestDc = forceTestDc,
            verbose = verboseLogging,
        )
    }
}
