package io.github.tgwsproxy.android.proxy

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DnsResolver {
    const val DOH_SYSTEM = "system"
    const val DOH_CLOUDFLARE = "cloudflare"
    const val DOH_GOOGLE = "google"
    const val DOH_QUAD9 = "quad9"

    const val IP_PREF_AUTO = "auto"
    const val IP_PREF_IPV4 = "ipv4"
    const val IP_PREF_IPV6 = "ipv6"

    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val MIN_TTL_SEC = 30L
    private const val MAX_TTL_SEC = 600L

    private val DNS_MEDIA_TYPE = "application/dns-message".toMediaType()

    private data class CacheKey(val host: String, val dohProvider: String, val ipPreference: String)
    private data class CacheEntry(val addresses: List<InetAddress>, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(2, 3, TimeUnit.MINUTES))
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .writeTimeout(2500, TimeUnit.MILLISECONDS)
            .callTimeout(3000, TimeUnit.MILLISECONDS)
            .proxy(Proxy.NO_PROXY)
            .build()
    }

    fun dohEndpointFor(provider: String): String? = when (provider.trim().lowercase()) {
        DOH_CLOUDFLARE -> "https://1.1.1.1/dns-query"
        DOH_GOOGLE -> "https://8.8.8.8/dns-query"
        DOH_QUAD9 -> "https://9.9.9.9/dns-query"
        else -> null
    }

    fun isValidIpAddress(raw: String): Boolean {
        val cleaned = raw.trim().removePrefix("[").removeSuffix("]")
        if (cleaned.isEmpty()) return false
        if (isValidIpv4(cleaned)) return true
        if (':' !in cleaned) return false
        if (!cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            return false
        }
        return runCatching { InetAddress.getByName(cleaned) is Inet6Address }.getOrDefault(false)
    }

    fun parseIpLiteralOrNull(raw: String): InetAddress? {
        val cleaned = raw.trim().removePrefix("[").removeSuffix("]")
        if (!isValidIpAddress(cleaned)) return null
        return runCatching { InetAddress.getByName(cleaned) }.getOrNull()
    }

    fun clearCache() {
        cache.clear()
    }

    fun resolve(
        host: String,
        dohProvider: String = DOH_SYSTEM,
        ipPreference: String = IP_PREF_AUTO,
        cleanIpOverride: String = "",
    ): List<InetAddress> {
        val overrideAddr = if (cleanIpOverride.isNotBlank()) parseIpLiteralOrNull(cleanIpOverride) else null
        if (overrideAddr != null) {
            return listOf(overrideAddr)
        }

        val trimmed = host.trim()
        val literal = parseIpLiteralOrNull(trimmed)
        if (literal != null) {
            return listOf(literal)
        }

        val normHost = trimmed.lowercase()
        val normDoh = dohProvider.trim().lowercase().ifEmpty { DOH_SYSTEM }
        val normPref = ipPreference.trim().lowercase().ifEmpty { IP_PREF_AUTO }
        val key = CacheKey(normHost, normDoh, normPref)
        val now = System.currentTimeMillis()
        val cached = cache[key]
        if (cached != null && now < cached.expiresAtMs && cached.addresses.isNotEmpty()) {
            return cached.addresses
        }

        val endpoint = dohEndpointFor(normDoh)
        if (endpoint != null) {
            val dohRes = runCatching { queryDoh(endpoint, normHost, normPref) }.getOrNull()
            if (dohRes != null && dohRes.first.isNotEmpty()) {
                val ordered = orderByPreference(dohRes.first, normPref)
                val ttlMs = dohRes.second.coerceIn(MIN_TTL_SEC, MAX_TTL_SEC) * 1000L
                cache[key] = CacheEntry(ordered, now + ttlMs)
                return ordered
            }
        }

        val sysAddrs = InetAddress.getAllByName(normHost).toList()
        if (sysAddrs.isEmpty()) {
            throw UnknownHostException("No addresses resolved for $normHost")
        }
        val ordered = orderByPreference(sysAddrs, normPref)
        cache[key] = CacheEntry(ordered, now + MIN_TTL_SEC * 1000L)
        return ordered
    }

    fun toOkHttpDns(
        dohProvider: String,
        ipPreference: String,
        cleanIpOverride: String = "",
    ): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                resolve(
                    host = hostname,
                    dohProvider = dohProvider,
                    ipPreference = ipPreference,
                    cleanIpOverride = cleanIpOverride,
                )
            } catch (e: UnknownHostException) {
                throw e
            } catch (e: Exception) {
                throw UnknownHostException("DNS lookup failed for $hostname: ${e.message}").apply { initCause(e) }
            }
        }
    }

    fun orderByPreference(addresses: List<InetAddress>, ipPreference: String): List<InetAddress> {
        if (addresses.size <= 1) return addresses
        val v4 = ArrayList<InetAddress>(addresses.size)
        val v6 = ArrayList<InetAddress>(addresses.size)
        val seen = HashSet<String>(addresses.size)
        for (addr in addresses) {
            val key = addr.hostAddress ?: continue
            if (!seen.add(key)) continue
            when (addr) {
                is Inet4Address -> v4.add(addr)
                is Inet6Address -> v6.add(addr)
                else -> v4.add(addr)
            }
        }
        return when (ipPreference.trim().lowercase()) {
            IP_PREF_IPV4 -> v4.ifEmpty { v6 }
            IP_PREF_IPV6 -> v6.ifEmpty { v4 }
            else -> {
                // RFC 8305 Happy Eyeballs interleaving (IPv4 first to avoid broken mobile IPv6 blackholes)
                val out = ArrayList<InetAddress>(v4.size + v6.size)
                val maxLen = maxOf(v4.size, v6.size)
                for (i in 0 until maxLen) {
                    if (i < v4.size) out.add(v4[i])
                    if (i < v6.size) out.add(v6[i])
                }
                out
            }
        }
    }

    private fun queryDoh(
        endpoint: String,
        host: String,
        ipPreference: String,
    ): Pair<List<InetAddress>, Long> {
        val types = when (ipPreference) {
            IP_PREF_IPV4 -> intArrayOf(TYPE_A)
            IP_PREF_IPV6 -> intArrayOf(TYPE_AAAA)
            else -> intArrayOf(TYPE_A, TYPE_AAAA)
        }
        val combined = ArrayList<InetAddress>(4)
        var minTtl = MAX_TTL_SEC
        for (qtype in types) {
            if (Thread.currentThread().isInterrupted) break
            val query = buildDnsQuery(host, qtype)
            val req = Request.Builder()
                .url(endpoint)
                .post(query.toRequestBody(DNS_MEDIA_TYPE))
                .header("Accept", "application/dns-message")
                .build()
            runCatching {
                dohClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.bytes() ?: return@use
                    val parsed = parseDnsResponse(body)
                    if (parsed.first.isNotEmpty()) {
                        combined.addAll(parsed.first)
                        if (parsed.second < minTtl) minTtl = parsed.second
                    }
                }
            }
        }
        return combined to minTtl
    }

    fun buildDnsQuery(host: String, qtype: Int): ByteArray {
        val out = ByteArrayOutputStream(64)
        // Header: ID=0x0000, Flags=0x0100 (RD=1), QDCOUNT=1, AN/NS/AR=0
        out.write(0x00); out.write(0x00)
        out.write(0x01); out.write(0x00)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)

        val labels = host.trim().trimEnd('.').split('.')
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) continue
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)
        out.write((qtype ushr 8) and 0xFF)
        out.write(qtype and 0xFF)
        out.write(0x00); out.write(0x01) // QCLASS = IN
        return out.toByteArray()
    }

    fun parseDnsResponse(packet: ByteArray): Pair<List<InetAddress>, Long> {
        if (packet.size < 12) return emptyList<InetAddress>() to MIN_TTL_SEC
        val rcode = packet[3].toInt() and 0x0F
        if (rcode != 0) return emptyList<InetAddress>() to MIN_TTL_SEC

        val qdCount = readU16(packet, 4)
        val anCount = readU16(packet, 6)
        var pos = 12

        repeat(qdCount) {
            pos = skipDnsName(packet, pos)
            if (pos < 0 || pos + 4 > packet.size) return emptyList<InetAddress>() to MIN_TTL_SEC
            pos += 4 // QTYPE + QCLASS
        }

        val addrs = ArrayList<InetAddress>(anCount.coerceAtMost(16))
        var minTtl = MAX_TTL_SEC

        repeat(anCount) {
            pos = skipDnsName(packet, pos)
            if (pos < 0 || pos + 10 > packet.size) return addrs to minTtl
            val rtype = readU16(packet, pos)
            val rclass = readU16(packet, pos + 2)
            val ttl = readU32(packet, pos + 4)
            val rdLen = readU16(packet, pos + 8)
            pos += 10
            if (pos + rdLen > packet.size) return addrs to minTtl

            if (rclass == 1) {
                if (rtype == TYPE_A && rdLen == 4) {
                    addrs.add(InetAddress.getByAddress(packet.copyOfRange(pos, pos + 4)))
                    if (ttl in 1 until minTtl) minTtl = ttl
                } else if (rtype == TYPE_AAAA && rdLen == 16) {
                    addrs.add(InetAddress.getByAddress(packet.copyOfRange(pos, pos + 16)))
                    if (ttl in 1 until minTtl) minTtl = ttl
                }
            }
            pos += rdLen
        }

        return addrs to minTtl
    }

    private fun skipDnsName(packet: ByteArray, startPos: Int): Int {
        var pos = startPos
        var steps = 0
        while (pos < packet.size && steps < 128) {
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if ((len and 0xC0) == 0xC0) {
                return if (pos + 2 <= packet.size) pos + 2 else -1
            }
            if (len > 63) return -1
            pos += 1 + len
            steps++
        }
        return -1
    }

    private fun readU16(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)

    private fun readU32(data: ByteArray, off: Int): Long =
        ((data[off].toLong() and 0xFF) shl 24) or
            ((data[off + 1].toLong() and 0xFF) shl 16) or
            ((data[off + 2].toLong() and 0xFF) shl 8) or
            (data[off + 3].toLong() and 0xFF)
}
