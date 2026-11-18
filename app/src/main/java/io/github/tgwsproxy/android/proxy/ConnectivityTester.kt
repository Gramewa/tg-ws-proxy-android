package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

sealed class DcConnectivityStatus {
    data class Ok(val latencyMs: Long = 0L) : DcConnectivityStatus()
    data class Failed(val reason: String) : DcConnectivityStatus()
}

data class AutoConnectivityResult(
    val bestDomain: String?,
    val perDc: Map<Int, DcConnectivityStatus>,
)

data class RouteProbeItem(
    val id: String,
    val status: DcConnectivityStatus,
)

data class QuickRouteReport(
    val directWs: DcConnectivityStatus,
    val cfTls: DcConnectivityStatus,
    val cfHttp80: DcConnectivityStatus,
    val suggestPort80: Boolean,
)

object ConnectivityTester {
    val TEST_DCS = listOf(1, 2, 3, 4, 5, 203)

    private data class TestCase(
        val dc: Int,
        val connectHost: String,
        val sniHost: String,
        val reqHost: String,
        val path: String,
    )

    suspend fun testCfProxyDomain(domain: String, secure: Boolean): Map<Int, DcConnectivityStatus> {
        val cases = TEST_DCS.map { dc ->
            val host = "kws$dc.$domain"
            TestCase(dc, host, host, host, WS_PATH)
        }
        return runCases(cases, secure)
    }

    suspend fun testCfProxyMulti(domains: List<String>, secure: Boolean): Map<String, Map<Int, DcConnectivityStatus>> {
        val out = LinkedHashMap<String, Map<Int, DcConnectivityStatus>>()
        for (domain in domains) {
            out[domain] = testCfProxyDomain(domain, secure)
        }
        return out
    }

    suspend fun testCfProxyAuto(domains: List<String>, secure: Boolean): AutoConnectivityResult {
        val pool = domains.ifEmpty { CFPROXY_DEFAULT_DOMAINS }
        val merged = LinkedHashMap<Int, DcConnectivityStatus>()
        var bestDomain: String? = null
        for (domain in pool.asReversed().take(8)) {
            val res = testCfProxyDomain(domain, secure)
            if (res.values.all { it is DcConnectivityStatus.Ok }) {
                return AutoConnectivityResult(domain, res)
            }
            for ((dc, status) in res) {
                if (status is DcConnectivityStatus.Ok) {
                    merged[dc] = status
                    if (bestDomain == null) bestDomain = domain
                } else if (dc !in merged) {
                    merged[dc] = status
                }
            }
        }
        return AutoConnectivityResult(bestDomain, merged)
    }

    suspend fun testCfWorkerMulti(domains: List<String>, secure: Boolean): Map<String, Map<Int, DcConnectivityStatus>> {
        val out = LinkedHashMap<String, Map<Int, DcConnectivityStatus>>()
        for (domain in domains) {
            val cases = TEST_DCS.map { dc ->
                val dst = DC_DEFAULT_IPS[dc] ?: "149.154.167.51"
                val dstEnc = URLEncoder.encode(dst, "UTF-8")
                TestCase(dc, domain, domain, domain, "/apiws?dst=$dstEnc&dc=$dc&media=0")
            }
            out[domain] = runCases(cases, secure)
        }
        return out
    }

    suspend fun quickRouteCheck(config: ProxyConfig): QuickRouteReport = coroutineScope {
        val cfSample = config.cfproxyUserDomains.ifEmpty { CFPROXY_DEFAULT_DOMAINS }.asReversed().take(3)
        val dc2Ip = config.dcRedirects[2] ?: DC_DEFAULT_IPS[2] ?: "149.154.167.220"

        val directDeferred = async(Dispatchers.IO) {
            probeSingle(
                connectHost = dc2Ip,
                sniHost = "kws2.web.telegram.org",
                reqHost = "kws2.web.telegram.org",
                path = WS_PATH,
                secure = true,
                verifyHostname = true,
                timeoutMs = 3500,
            ).let { first ->
                if (first is DcConnectivityStatus.Ok) {
                    first
                } else {
                    probeSingle(
                        connectHost = dc2Ip,
                        sniHost = "sprinthost.ru",
                        reqHost = "kws2.web.telegram.org",
                        path = WS_PATH,
                        secure = true,
                        verifyHostname = false,
                        timeoutMs = 3500,
                    )
                }
            }
        }

        val cfTlsDeferred = async(Dispatchers.IO) {
            var last: DcConnectivityStatus = DcConnectivityStatus.Failed("timeout")
            for (dom in cfSample) {
                val host = "kws2.$dom"
                val res = probeSingle(host, host, host, WS_PATH, secure = true, verifyHostname = true, timeoutMs = 3800)
                last = res
                if (res is DcConnectivityStatus.Ok) break
            }
            last
        }

        val cfHttpDeferred = async(Dispatchers.IO) {
            var last: DcConnectivityStatus = DcConnectivityStatus.Failed("timeout")
            for (dom in cfSample) {
                val host = "kws2.$dom"
                val res = probeSingle(host, host, host, WS_PATH, secure = false, verifyHostname = false, timeoutMs = 3500)
                last = res
                if (res is DcConnectivityStatus.Ok) break
            }
            last
        }

        val directWs = directDeferred.await()
        val cfTls = cfTlsDeferred.await()
        val cfHttp80 = cfHttpDeferred.await()

        val suggestPort80 = !config.disableSecure &&
            cfTls is DcConnectivityStatus.Failed &&
            cfHttp80 is DcConnectivityStatus.Ok

        QuickRouteReport(
            directWs = directWs,
            cfTls = cfTls,
            cfHttp80 = cfHttp80,
            suggestPort80 = suggestPort80,
        )
    }

    private suspend fun runCases(cases: List<TestCase>, secure: Boolean): Map<Int, DcConnectivityStatus> =
        withContext(Dispatchers.IO) {
            val sem = Semaphore(6)
            val pairs = coroutineScope {
                cases.map { tc ->
                    async {
                        sem.withPermit {
                            tc.dc to probeSingle(
                                connectHost = tc.connectHost,
                                sniHost = tc.sniHost,
                                reqHost = tc.reqHost,
                                path = tc.path,
                                secure = secure,
                                verifyHostname = secure,
                                timeoutMs = 4500,
                            )
                        }
                    }
                }.awaitAll()
            }
            val ordered = LinkedHashMap<Int, DcConnectivityStatus>()
            for ((dc, status) in pairs) {
                ordered[dc] = status
            }
            ordered
        }

    private fun probeSingle(
        connectHost: String,
        sniHost: String,
        reqHost: String,
        path: String,
        secure: Boolean,
        verifyHostname: Boolean,
        timeoutMs: Int,
    ): DcConnectivityStatus {
        val port = if (secure) 443 else 80
        val random = SecureRandom()
        var raw: Socket? = null
        var active: Socket? = null
        val startedAt = System.currentTimeMillis()
        return try {
            raw = Socket()
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(connectHost, port), timeoutMs)
            raw.soTimeout = timeoutMs

            val conn: Socket = if (secure) {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(raw, sniHost, 443, true) as SSLSocket
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(sniHost))
                ssl.sslParameters = params
                ssl.startHandshake()
                if (verifyHostname && !HttpsURLConnection.getDefaultHostnameVerifier().verify(sniHost, ssl.session)) {
                    throw SSLPeerUnverifiedException("Hostname mismatch for $sniHost")
                }
                ssl
            } else {
                raw
            }
            active = conn

            val keyBytes = ByteArray(16).also { random.nextBytes(it) }
            val wsKey = Base64.getEncoder().encodeToString(keyBytes)
            val req = (
                "GET $path HTTP/1.1\r\n" +
                    "Host: $reqHost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $wsKey\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Protocol: binary\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)

            conn.getOutputStream().apply {
                write(req)
                flush()
            }

            val input = BufferedInputStream(conn.getInputStream(), 2048)
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) break
                buf.write(b)
                val str = buf.toString(Charsets.UTF_8.name())
                if (str.contains("\r\n\r\n")) break
                if (buf.size() > 8192) break
            }
            val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            val firstLine = buf.toString(Charsets.UTF_8.name()).split("\r\n").firstOrNull().orEmpty()
            if (firstLine.contains("101")) {
                DcConnectivityStatus.Ok(elapsedMs)
            } else {
                DcConnectivityStatus.Failed(firstLine.ifBlank { "no response" }.take(60))
            }
        } catch (exc: Exception) {
            val msg = exc.message ?: exc.javaClass.simpleName
            DcConnectivityStatus.Failed(msg.take(60))
        } finally {
            try { active?.close() } catch (_: Exception) {}
            try { raw?.close() } catch (_: Exception) {}
        }
    }
}
