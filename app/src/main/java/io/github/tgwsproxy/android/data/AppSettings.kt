package io.github.tgwsproxy.android.data

import io.github.tgwsproxy.android.proxy.DcParseResult
import io.github.tgwsproxy.android.proxy.ProxyConfig
import io.github.tgwsproxy.android.proxy.coerceDomainList
import io.github.tgwsproxy.android.proxy.generateSecretHex
import io.github.tgwsproxy.android.proxy.isValidDomain
import io.github.tgwsproxy.android.proxy.isValidIpv4
import io.github.tgwsproxy.android.proxy.isValidSecretHex
import io.github.tgwsproxy.android.proxy.normalizeDomainPool
import io.github.tgwsproxy.android.proxy.parseDcIpRules
import org.json.JSONObject

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
    val dynamicColors: Boolean = false,
    val language: String = "ru",
    val simpleMode: Boolean = true,
    val checkUpdatesOnStartup: Boolean = true,
    val postponedUpdateVersion: String = "",
    val postponedUpdateUntilMs: Long = 0L,
    val wasRunning: Boolean = false,
) {
    companion object {
        const val DEFAULT_DC_RULES = "2:149.154.167.220\n4:149.154.167.220"
    }

    fun hasSameNetworkConfigAs(other: AppSettings): Boolean {
        return host.trim() == other.host.trim() &&
            port == other.port &&
            secret.trim().lowercase() == other.secret.trim().lowercase() &&
            dcIpRules.trim() == other.dcIpRules.trim() &&
            cfproxyEnabled == other.cfproxyEnabled &&
            cfproxyH2Media == other.cfproxyH2Media &&
            cfproxyCustomDomainsEnabled == other.cfproxyCustomDomainsEnabled &&
            cfproxyCustomDomains.trim() == other.cfproxyCustomDomains.trim() &&
            cfworkerDomains.trim() == other.cfworkerDomains.trim() &&
            disableSecure == other.disableSecure &&
            fakeTlsDomain.trim().lowercase() == other.fakeTlsDomain.trim().lowercase() &&
            bufferKb == other.bufferKb &&
            poolSize == other.poolSize &&
            verboseLogging == other.verboseLogging &&
            proxyProtocol == other.proxyProtocol &&
            forceTestDc == other.forceTestDc
    }

    fun hasSameClientLinkAs(other: AppSettings): Boolean {
        return host.trim() == other.host.trim() &&
            port == other.port &&
            secret.trim().lowercase() == other.secret.trim().lowercase() &&
            fakeTlsDomain.trim().lowercase() == other.fakeTlsDomain.trim().lowercase()
    }

    fun toExportJson(): String {
        val obj = JSONObject()
        obj.put("v", 1)
        obj.put("host", host.trim())
        obj.put("port", port)
        obj.put("secret", secret.trim().lowercase())
        obj.put("fakeTlsDomain", fakeTlsDomain.trim().lowercase())
        obj.put("dcIpRules", dcIpRules)
        obj.put("cfproxyEnabled", cfproxyEnabled)
        obj.put("cfproxyH2Media", cfproxyH2Media)
        obj.put("cfproxyCustomDomainsEnabled", cfproxyCustomDomainsEnabled)
        obj.put("cfproxyCustomDomains", cfproxyCustomDomains)
        obj.put("cfworkerDomains", cfworkerDomains)
        obj.put("disableSecure", disableSecure)
        obj.put("bufferKb", bufferKb)
        obj.put("poolSize", poolSize)
        obj.put("proxyProtocol", proxyProtocol)
        obj.put("forceTestDc", forceTestDc)
        return obj.toString()
    }

    fun applyImportedJson(rawJson: String): AppSettings? {
        return try {
            val obj = JSONObject(rawJson.trim())
            val newHost = obj.optString("host", host).trim()
            if (!isValidIpv4(newHost)) return null

            val newPort = obj.optInt("port", port)
            if (newPort !in 1..65535) return null

            val newSecret = obj.optString("secret", secret).trim().lowercase()
            if (newSecret.isNotEmpty() && !isValidSecretHex(newSecret)) return null

            val newFtls = obj.optString("fakeTlsDomain", fakeTlsDomain).trim().lowercase()
            if (newFtls.isNotEmpty() && !isValidDomain(newFtls)) return null

            val newDcRules = obj.optString("dcIpRules", dcIpRules)
            if (parseDcIpRules(newDcRules) is DcParseResult.Error) return null

            val newBuf = obj.optInt("bufferKb", bufferKb)
            if (newBuf !in 4..4096) return null

            val newPool = obj.optInt("poolSize", poolSize)
            if (newPool !in 0..16) return null

            copy(
                host = newHost,
                port = newPort,
                secret = newSecret.ifEmpty { secret },
                fakeTlsDomain = newFtls,
                dcIpRules = newDcRules,
                cfproxyEnabled = obj.optBoolean("cfproxyEnabled", cfproxyEnabled),
                cfproxyH2Media = obj.optBoolean("cfproxyH2Media", cfproxyH2Media),
                cfproxyCustomDomainsEnabled = obj.optBoolean("cfproxyCustomDomainsEnabled", cfproxyCustomDomainsEnabled),
                cfproxyCustomDomains = obj.optString("cfproxyCustomDomains", cfproxyCustomDomains),
                cfworkerDomains = obj.optString("cfworkerDomains", cfworkerDomains),
                disableSecure = obj.optBoolean("disableSecure", disableSecure),
                bufferKb = newBuf,
                poolSize = newPool,
                proxyProtocol = obj.optBoolean("proxyProtocol", proxyProtocol),
                forceTestDc = obj.optBoolean("forceTestDc", forceTestDc),
            )
        } catch (_: Exception) {
            null
        }
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
