package io.github.tgwsproxy.android.proxy

import kotlinx.coroutines.Dispatchers
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
    data object Ok : DcConnectivityStatus()
    data class Failed(val reason: String) : DcConnectivityStatus()
}

data class AutoConnectivityResult(
    val bestDomain: String?,
    val perDc: Map<Int, DcConnectivityStatus>,
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
        for (domain in pool.asReversed()) {
            val res = testCfProxyDomain(domain, secure)
            if (res.values.all { it is DcConnectivityStatus.Ok }) {
                return AutoConnectivityResult(domain, res)
            }
            for ((dc, status) in res) {
                if (status is DcConnectivityStatus.Ok) {
                    merged[dc] = DcConnectivityStatus.Ok
                    bestDomain = domain
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

    private suspend fun runCases(cases: List<TestCase>, secure: Boolean): Map<Int, DcConnectivityStatus> =
        withContext(Dispatchers.IO) {
            val port = if (secure) 443 else 80
            val random = SecureRandom()
            val results = LinkedHashMap<Int, DcConnectivityStatus>()

            for (tc in cases) {
                var raw: Socket? = null
                var active: Socket? = null
                try {
                    raw = Socket()
                    raw.connect(InetSocketAddress(tc.connectHost, port), 5000)
                    raw.soTimeout = 5000

                    val conn: Socket = if (secure) {
                        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                        val ssl = factory.createSocket(raw, tc.sniHost, 443, true) as SSLSocket
                        val params = ssl.sslParameters
                        params.serverNames = listOf(SNIHostName(tc.sniHost))
                        ssl.sslParameters = params
                        ssl.startHandshake()
                        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(tc.sniHost, ssl.session)) {
                            throw SSLPeerUnverifiedException("Hostname mismatch for ${tc.sniHost}")
                        }
                        ssl
                    } else {
                        raw
                    }
                    active = conn

                    val keyBytes = ByteArray(16).also { random.nextBytes(it) }
                    val wsKey = Base64.getEncoder().encodeToString(keyBytes)
                    val req = (
                        "GET ${tc.path} HTTP/1.1\r\n" +
                            "Host: ${tc.reqHost}\r\n" +
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
                    val firstLine = buf.toString(Charsets.UTF_8.name()).split("\r\n").firstOrNull().orEmpty()
                    results[tc.dc] = if (firstLine.contains("101")) {
                        DcConnectivityStatus.Ok
                    } else {
                        DcConnectivityStatus.Failed(firstLine.ifBlank { "no response" }.take(60))
                    }
                } catch (exc: Exception) {
                    val msg = exc.message ?: exc.javaClass.simpleName
                    results[tc.dc] = DcConnectivityStatus.Failed(msg.take(60))
                } finally {
                    try { active?.close() } catch (_: Exception) {}
                    try { raw?.close() } catch (_: Exception) {}
                }
            }
            results
        }
}
