package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class ProxyConfig(
    val host: String = "127.0.0.1",
    val port: Int = 1443,
    val secret: String = generateSecretHex(),
    val dcRedirects: Map<Int, String> = mapOf(
        2 to "149.154.167.220",
        4 to "149.154.167.220",
    ),
    val bufferSize: Int = 256 * 1024,
    val poolSize: Int = 4,
    val fallbackCfproxy: Boolean = true,
    val cfproxyUserDomains: List<String> = emptyList(),
    val cfproxyWorkerDomains: List<String> = emptyList(),
    val cfproxyH2Media: Boolean = true,
    val disableSecure: Boolean = false,
    val autoPort80Fallback: Boolean = true,
    val frontingSni: String = DEFAULT_FRONTING_SNI,
    val dohProvider: String = DnsResolver.DOH_SYSTEM,
    val cfCleanIp: String = "",
    val ipPreference: String = DnsResolver.IP_PREF_AUTO,
    val cachedCfDomains: List<String> = emptyList(),
    val fakeTlsDomain: String = "",
    val proxyProtocol: Boolean = false,
    val forceTestDc: Boolean = false,
    val verbose: Boolean = false,
) {
    val h2Enabled: Boolean
        get() = cfproxyH2Media && fallbackCfproxy && !disableSecure && !forceTestDc

    val frontingSniList: List<String>
        get() = normalizeDomainPool(coerceDomainList(frontingSni))
            .ifEmpty { listOf(DEFAULT_FRONTING_SNI) }

    val effectiveCfCleanIp: String
        get() = cfCleanIp.trim().let { if (DnsResolver.isValidIpAddress(it)) it else "" }
}

const val DEFAULT_FRONTING_SNI = "sprinthost.ru"

const val CFPROXY_DOMAINS_URL =
    "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"
private const val GITHUB_RAW_HOST = "raw.githubusercontent.com"
private val GITHUB_RAW_PINNED_IPS = listOf(
    "185.199.108.133",
    "185.199.109.133",
    "185.199.110.133",
    "185.199.111.133",
)
private const val CFPROXY_MIN_VALID_DOMAINS = 3
private const val CFPROXY_REFRESH_INTERVAL_MS = 3_600_000L
private val CFPROXY_RETRY_BACKOFFS_MS = longArrayOf(30_000L, 120_000L, 600_000L, CFPROXY_REFRESH_INTERVAL_MS)
private const val MAX_DOMAIN_LIST_BYTES = 64 * 1024

const val WS_PATH = "/apiws"
const val WS_PATH_TEST = "/apiws_test"

val DC_DEFAULT_IPS: Map<Int, String> = mapOf(
    1 to "149.154.175.50",
    2 to "149.154.167.51",
    3 to "149.154.175.100",
    4 to "149.154.167.91",
    5 to "149.154.171.5",
    203 to "91.105.192.100",
)

val DC_TEST_IPS: Map<Int, String> = mapOf(
    1 to "149.154.175.10",
    2 to "149.154.167.40",
    3 to "149.154.175.117",
)

val CFPROXY_ENC: List<String> = listOf(
    "virkgj.com",
    "vmmzovy.com",
    "mkuosckvso.com",
    "zaewayzmplad.com",
    "twdmbzcm.com",
    "awzwsldi.com",
    "clngqrflngqin.com",
    "tjacxbqtj.com",
    "bxaxtxmrw.com",
    "dmohrsgmohcrwb.com",
    "vwbmtmoi.com",
    "khgrre.com",
    "ulihssf.com",
    "tmhqsdqmfpmk.com",
    "xwuwoqbm.com",
    "orgcnunpj.com",
    "zhkuldz.com",
    "zypoljnslxa.com",
    "efabnxaowuzs.com",
    "zaftuzsftqdq.com",
)

private val CFPROXY_SUFFIX = charArrayOf('.', 'c', 'o', '.', 'u', 'k').concatToString()

fun decodeCfDomain(s: String): String {
    if (!s.endsWith(".com")) return s
    val prefix = s.substring(0, s.length - 4)
    val shift = prefix.count { it in 'a'..'z' || it in 'A'..'Z' }
    val sb = StringBuilder(prefix.length + CFPROXY_SUFFIX.length)
    for (c in prefix) {
        when (c) {
            in 'a'..'z' -> {
                val offset = ((c - 'a' - shift) % 26 + 26) % 26
                sb.append(('a'.code + offset).toChar())
            }
            in 'A'..'Z' -> {
                val offset = ((c - 'A' - shift) % 26 + 26) % 26
                sb.append(('A'.code + offset).toChar())
            }
            else -> sb.append(c)
        }
    }
    sb.append(CFPROXY_SUFFIX)
    return sb.toString()
}

val CFPROXY_DEFAULT_DOMAINS: List<String> by lazy {
    CFPROXY_ENC.map(::decodeCfDomain)
}

fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
    val mapped = if (dc == 203) 2 else dc
    return if (!isMedia) {
        listOf("kws$mapped.web.telegram.org")
    } else {
        listOf("kws$mapped-1.web.telegram.org", "kws$mapped.web.telegram.org")
    }
}

fun coerceDomainList(value: String): List<String> {
    if (value.isBlank()) return emptyList()
    val seen = HashSet<String>()
    val result = ArrayList<String>()
    for (raw in value.replace(',', ' ').replace(';', ' ').split(Regex("\\s+"))) {
        val item = raw.trim()
        if (item.isEmpty()) continue
        val key = item.lowercase()
        if (seen.add(key)) {
            result.add(item)
        }
    }
    return result
}

fun isValidDomain(domain: String): Boolean {
    if (domain.isEmpty() || domain.length > 253) return false
    if (domain.startsWith('.') || domain.endsWith('.')) return false
    val labels = domain.split('.')
    if (labels.size < 2) return false
    for (label in labels) {
        if (label.isEmpty() || label.length > 63) return false
        if (label.first() == '-' || label.last() == '-') return false
        if (!label.all { it.isLetterOrDigit() || it == '-' }) return false
    }
    val tld = labels.last()
    if (tld.length < 2 || !tld.any { it.isLetter() }) return false
    return true
}

fun normalizeDomainPool(domains: List<String>): List<String> {
    val seen = HashSet<String>()
    val normalized = ArrayList<String>()
    for (domain in domains) {
        val item = domain.trim().lowercase()
        if (!isValidDomain(item)) continue
        if (seen.add(item)) {
            normalized.add(item)
        }
    }
    return normalized
}

private val IPV4_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

fun isValidIpv4(ip: String): Boolean {
    val match = IPV4_REGEX.matchEntire(ip.trim()) ?: return false
    return (1..4).all { idx ->
        val part = match.groupValues[idx]
        val num = part.toIntOrNull() ?: return@all false
        num in 0..255 && (part.length == 1 || !part.startsWith('0'))
    }
}

sealed class DcParseResult {
    data class Success(val map: Map<Int, String>) : DcParseResult()
    data class Error(val entry: String, val kind: Kind) : DcParseResult() {
        enum class Kind { FORMAT, INVALID }
    }
}

fun parseDcIpRules(text: String): DcParseResult {
    val redirects = LinkedHashMap<Int, String>()
    val lines = text.replace(',', '\n').replace(';', '\n').lines()
    for (rawLine in lines) {
        val entry = rawLine.trim()
        if (entry.isEmpty() || entry.startsWith('#')) continue
        val colonIdx = entry.indexOf(':')
        if (colonIdx <= 0 || colonIdx == entry.lastIndex) {
            return DcParseResult.Error(entry, DcParseResult.Error.Kind.FORMAT)
        }
        val dcStr = entry.substring(0, colonIdx).trim()
        val ipStr = entry.substring(colonIdx + 1).trim()
        val dcNum = dcStr.toIntOrNull()
        if (dcNum == null || dcNum <= 0 || !isValidIpv4(ipStr)) {
            return DcParseResult.Error(entry, DcParseResult.Error.Kind.INVALID)
        }
        redirects[dcNum] = ipStr
    }
    return DcParseResult.Success(redirects)
}

fun generateSecretHex(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

fun isValidSecretHex(secret: String): Boolean =
    secret.length == 32 && secret.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

fun getLinkHost(host: String): String {
    if (host != "0.0.0.0") return host
    return try {
        DatagramSocket().use { sock ->
            sock.connect(InetAddress.getByName("8.8.8.8"), 80)
            sock.localAddress?.hostAddress ?: "127.0.0.1"
        }
    } catch (_: Exception) {
        "127.0.0.1"
    }
}

fun buildTelegramProxyLink(config: ProxyConfig, resolvedHost: String = getLinkHost(config.host)): String {
    val ftls = config.fakeTlsDomain.trim()
    val fullSecret = if (ftls.isNotEmpty()) {
        val domainHex = ftls.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
        "ee${config.secret}$domainHex"
    } else {
        "dd${config.secret}"
    }
    return "tg://proxy?server=$resolvedHost&port=${config.port}&secret=$fullSecret"
}

class CfDomainRefresher(
    private val balancer: DomainBalancer,
) {
    private var refreshJob: Job? = null
    private val random = SecureRandom()
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var autoMode: Boolean = false
    @Volatile private var lastSuccessMs: Long = 0L

    fun start(
        scope: CoroutineScope,
        userDomains: List<String>,
        cachedDomains: List<String> = emptyList(),
        onDomainsPersisted: (List<String>) -> Unit = {},
    ) {
        stop()
        if (userDomains.isNotEmpty()) {
            autoMode = false
            balancer.updateDomainsList(userDomains)
            return
        }
        autoMode = true
        lastSuccessMs = 0L
        val cachedValid = normalizeDomainPool(cachedDomains)
        val initialPool = if (cachedValid.size >= CFPROXY_MIN_VALID_DOMAINS) {
            ProxyLogger.d("Seeded CF proxy domain pool from local cache (${cachedValid.size} domains)")
            cachedValid
        } else {
            CFPROXY_DEFAULT_DOMAINS
        }
        balancer.updateDomainsList(initialPool)
        // Drain any stale wakeup signal
        while (wakeSignal.tryReceive().isSuccess) {}

        refreshJob = scope.launch {
            var failures = 0
            while (isActive) {
                val ok = refreshOnce(onDomainsPersisted)
                val waitMs = if (ok) {
                    failures = 0
                    lastSuccessMs = System.currentTimeMillis()
                    CFPROXY_REFRESH_INTERVAL_MS
                } else {
                    val idx = failures.coerceAtMost(CFPROXY_RETRY_BACKOFFS_MS.lastIndex)
                    failures++
                    CFPROXY_RETRY_BACKOFFS_MS[idx]
                }
                withTimeoutOrNull(waitMs) {
                    wakeSignal.receive()
                }
            }
        }
    }

    fun triggerRefreshIfStale() {
        if (!autoMode || refreshJob?.isActive != true) return
        val now = System.currentTimeMillis()
        if (lastSuccessMs == 0L || now - lastSuccessMs >= 600_000L) {
            wakeSignal.trySend(Unit)
        }
    }

    fun stop() {
        autoMode = false
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun refreshOnce(onDomainsPersisted: (List<String>) -> Unit): Boolean {
        val fetched = fetchCfProxyDomainList()
        val pool = normalizeDomainPool(fetched)
        if (pool.size >= CFPROXY_MIN_VALID_DOMAINS) {
            balancer.updateDomainsList(pool)
            runCatching { onDomainsPersisted(pool) }
            ProxyLogger.i("CF proxy domain pool updated from GitHub (${pool.size} domains)")
            return true
        }
        if (fetched.isNotEmpty()) {
            ProxyLogger.w(
                "Ignoring fetched CF proxy domains due to low-quality payload " +
                    "(total=${fetched.size}, valid=${pool.size}, required>=$CFPROXY_MIN_VALID_DOMAINS); " +
                    "keeping current domain pool"
            )
        } else {
            ProxyLogger.w("CF proxy domain refresh failed or empty response; keeping current domain pool")
        }
        return false
    }

    private fun fetchCfProxyDomainList(): List<String> {
        val suffix = buildString(7) {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            repeat(7) { append(chars[random.nextInt(chars.length)]) }
        }
        val queryPath = "/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt?$suffix"
        val fullUrl = "$CFPROXY_DOMAINS_URL?$suffix"

        val body = tryFetchViaPinnedIps(queryPath) ?: tryFetchViaStandardHttps(fullUrl) ?: return emptyList()
        return body.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map(::decodeCfDomain)
    }

    private fun tryFetchViaPinnedIps(path: String): String? {
        for (pinnedIp in GITHUB_RAW_PINNED_IPS.shuffled(random)) {
            val body = tryFetchViaSinglePinnedIp(pinnedIp, path)
            if (body != null) return body
        }
        return null
    }

    private fun tryFetchViaSinglePinnedIp(pinnedIp: String, path: String): String? {
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        return try {
            rawSocket = Socket()
            rawSocket.connect(InetSocketAddress(pinnedIp, 443), 6_000)
            rawSocket.soTimeout = 8_000
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSocket = factory.createSocket(rawSocket, GITHUB_RAW_HOST, 443, true) as SSLSocket
            val params = sslSocket.sslParameters
            params.serverNames = listOf(SNIHostName(GITHUB_RAW_HOST))
            sslSocket.sslParameters = params
            sslSocket.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(GITHUB_RAW_HOST, sslSocket.session)) {
                throw SSLPeerUnverifiedException("Hostname verification failed for $GITHUB_RAW_HOST")
            }

            val req = "GET $path HTTP/1.1\r\nHost: $GITHUB_RAW_HOST\r\nUser-Agent: tg-ws-proxy\r\nConnection: close\r\n\r\n"
            sslSocket.outputStream.write(req.toByteArray(Charsets.US_ASCII))
            sslSocket.outputStream.flush()

            val input = BufferedInputStream(sslSocket.inputStream)
            val headerBytes = ByteArrayOutputStream()
            var prev3 = 0
            var prev2 = 0
            var prev1 = 0
            while (true) {
                val b = input.read()
                if (b == -1) break
                headerBytes.write(b)
                if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                    break
                }
                prev3 = prev2
                prev2 = prev1
                prev1 = b
            }
            val headerStr = headerBytes.toString(Charsets.ISO_8859_1.name())
            val firstLine = headerStr.lineSequence().firstOrNull().orEmpty()
            if (!firstLine.contains(" 200 ")) return null

            val isChunked = headerStr.lines().any {
                it.startsWith("transfer-encoding:", ignoreCase = true) &&
                    it.contains("chunked", ignoreCase = true)
            }
            val bodyBytes = if (isChunked) {
                readChunkedBody(input, MAX_DOMAIN_LIST_BYTES)
            } else {
                readBoundedBytes(input, MAX_DOMAIN_LIST_BYTES)
            }
            bodyBytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        } finally {
            try { sslSocket?.close() } catch (_: Exception) {}
            try { rawSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun readBoundedBytes(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < limit) {
            val maxRead = minOf(buf.size, limit - out.size())
            val r = input.read(buf, 0, maxRead)
            if (r <= 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private fun readChunkedBody(input: BufferedInputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        while (out.size() < limit) {
            val sizeLine = readAsciiLine(input) ?: break
            val semi = sizeLine.indexOf(';')
            val clean = (if (semi >= 0) sizeLine.substring(0, semi) else sizeLine).trim()
            val size = clean.toIntOrNull(16) ?: break
            if (size <= 0 || size > limit || out.size() + size > limit) break
            val buf = ByteArray(size)
            var off = 0
            while (off < size) {
                val r = input.read(buf, off, size - off)
                if (r <= 0) break
                off += r
            }
            out.write(buf, 0, off)
            readAsciiLine(input) // trailing CRLF
        }
        return out.toByteArray()
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val line = ByteArrayOutputStream()
        while (line.size() < 256) {
            val b = input.read()
            if (b == -1) return if (line.size() == 0) null else line.toString(Charsets.US_ASCII.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) line.write(b)
        }
        return line.toString(Charsets.US_ASCII.name())
    }

    private fun tryFetchViaStandardHttps(fullUrl: String): String? {
        var conn: HttpsURLConnection? = null
        return try {
            val url = URL(fullUrl)
            if (!url.protocol.equals("https", ignoreCase = true)) return null
            conn = (url.openConnection() as? HttpsURLConnection)?.apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = false
                hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                setRequestProperty("User-Agent", "tg-ws-proxy")
            } ?: return null
            if (conn.responseCode !in 200..299) return null
            readBoundedBytes(conn.inputStream, MAX_DOMAIN_LIST_BYTES).toString(Charsets.UTF_8)
        } catch (exc: Exception) {
            ProxyLogger.w("Failed to fetch CF proxy domain list: ${exc.javaClass.simpleName}: ${exc.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
