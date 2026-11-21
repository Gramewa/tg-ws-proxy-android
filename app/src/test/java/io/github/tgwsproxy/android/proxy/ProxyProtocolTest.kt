package io.github.tgwsproxy.android.proxy

import io.github.tgwsproxy.android.update.AppUpdater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ProxyProtocolTest {

    @Test
    fun cfDomainDecodingProducesValidDomains() {
        assertEquals("pclead.co.uk", decodeCfDomain("virkgj.com"))
        assertEquals(20, CFPROXY_DEFAULT_DOMAINS.size)
        for (domain in CFPROXY_DEFAULT_DOMAINS) {
            assertTrue("Expected .co.uk suffix for $domain", domain.endsWith(".co.uk"))
            assertTrue("Expected valid domain: $domain", isValidDomain(domain))
        }
    }

    @Test
    fun aesCtrByteByByteMatchesChunkAndCarriesAcrossFfBoundary() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val iv = ByteArray(16) { 0xFF.toByte() }
        iv[15] = 0xFF.toByte()
        iv[14] = 0xFF.toByte()

        val payload = ByteArray(97) { (it * 13 + 5).toByte() }
        val chunked = AesCtr(key, iv).update(payload)

        val streamEnc = AesCtr(key, iv)
        val byteByByte = ByteArray(payload.size)
        for (i in payload.indices) {
            byteByByte[i] = streamEnc.update(byteArrayOf(payload[i]))[0]
        }
        assertArrayEquals(chunked, byteByByte)

        val decrypted = AesCtr(key, iv).update(chunked)
        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun handshakeAndCryptoCtxRoundTrip() {
        val secret = ByteArray(16) { (it + 1).toByte() }
        val protoTags = listOf(
            HandshakeCrypto.PROTO_TAG_ABRIDGED to HandshakeCrypto.PROTO_ABRIDGED_INT,
            HandshakeCrypto.PROTO_TAG_INTERMEDIATE to HandshakeCrypto.PROTO_INTERMEDIATE_INT,
            HandshakeCrypto.PROTO_TAG_SECURE to HandshakeCrypto.PROTO_PADDED_INTERMEDIATE_INT,
        )

        for ((tag, expectedProtoInt) in protoTags) {
            for (dcIdx in listOf(2, -4, 10002)) {
                val rnd = ByteArray(64) { (it * 3 + 11).toByte() }
                rnd[0] = 0x22
                val cltEncKeyRaw = rnd.copyOfRange(8, 40)
                val cltEncIv = rnd.copyOfRange(40, 56)
                val cltEncKey = MessageDigest.getInstance("SHA-256").run {
                    update(cltEncKeyRaw)
                    update(secret)
                    digest()
                }

                val encStream = AesCtr(cltEncKey, cltEncIv)
                val fullEnc = encStream.update(rnd)
                val tailPlain = byteArrayOf(
                    tag[0], tag[1], tag[2], tag[3],
                    (dcIdx and 0xFF).toByte(),
                    ((dcIdx ushr 8) and 0xFF).toByte(),
                    0x12, 0x34,
                )
                val clientHandshake = rnd.copyOf()
                for (i in 0 until 8) {
                    val ks = fullEnc[56 + i].toInt() xor rnd[56 + i].toInt()
                    clientHandshake[56 + i] = (tailPlain[i].toInt() xor ks).toByte()
                }

                val parsed = HandshakeCrypto.tryHandshake(clientHandshake, secret)
                assertNotNull(parsed)
                parsed!!
                assertEquals(kotlin.math.abs(dcIdx), parsed.dcId)
                assertEquals(dcIdx < 0, parsed.isMedia)
                assertEquals(expectedProtoInt, parsed.protoInt)

                val relayInit = HandshakeCrypto.generateRelayInit(parsed.protoTag, dcIdx)
                assertEquals(64, relayInit.size)

                val ctx = HandshakeCrypto.buildCryptoCtx(parsed.decPrekeyAndIv, secret, relayInit)

                // Client sends message -> proxy decrypts with cltDec -> encrypts with tgEnc -> Telegram decrypts with relay key
                val message = "MTProto test message 1234".toByteArray()
                val fromClient = encStream.update(message)
                val decryptedAtProxy = ctx.cltDec.update(fromClient)
                assertArrayEquals(message, decryptedAtProxy)

                val toTelegram = ctx.tgEnc.update(decryptedAtProxy)
                val tgRecvDec = AesCtr(relayInit.copyOfRange(8, 40), relayInit.copyOfRange(40, 56))
                tgRecvDec.update(ByteArray(64))
                assertArrayEquals(message, tgRecvDec.update(toTelegram))

                // Telegram replies -> proxy decrypts with tgDec -> encrypts with cltEnc -> Client decrypts
                val revRelay = relayInit.copyOfRange(8, 56).reversedArray()
                val tgSendEnc = AesCtr(revRelay.copyOfRange(0, 32), revRelay.copyOfRange(32, 48))
                val reply = "Telegram server reply!".toByteArray()
                val fromTelegram = tgSendEnc.update(reply)
                val proxyPlain = ctx.tgDec.update(fromTelegram)
                assertArrayEquals(reply, proxyPlain)
                val toClient = ctx.cltEnc.update(proxyPlain)

                val revClient = parsed.decPrekeyAndIv.reversedArray()
                val clientRecvKey = MessageDigest.getInstance("SHA-256").run {
                    update(revClient.copyOfRange(0, 32))
                    update(secret)
                    digest()
                }
                val clientRecvDec = AesCtr(clientRecvKey, revClient.copyOfRange(32, 48))
                assertArrayEquals(reply, clientRecvDec.update(toClient))
            }
        }
    }

    @Test
    fun msgSplitterSplitsAbridgedAndIntermediateAcrossChunks() {
        // Abridged: 1-byte length header + 12 bytes + large 0x7f header + 512 bytes
        val p1Plain = byteArrayOf(3) + ByteArray(12) { 0x11 }
        val p2Words = 128
        val p2Plain = byteArrayOf(
            0x7F,
            (p2Words and 0xFF).toByte(),
            ((p2Words ushr 8) and 0xFF).toByte(),
            ((p2Words ushr 16) and 0xFF).toByte(),
        ) + ByteArray(p2Words * 4) { 0x22 }
        val allPlain = p1Plain + p2Plain
        val allCipher = allPlain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        val splitterA = MsgSplitter(HandshakeCrypto.PROTO_ABRIDGED_INT)
        val firstCut = 20
        val out1 = splitterA.split(allCipher.copyOfRange(0, firstCut), allPlain.copyOfRange(0, firstCut))
        assertEquals(1, out1.size)
        assertEquals(p1Plain.size, out1[0].size)

        val out2 = splitterA.split(
            allCipher.copyOfRange(firstCut, allCipher.size),
            allPlain.copyOfRange(firstCut, allPlain.size),
        )
        assertEquals(1, out2.size)
        assertEquals(p2Plain.size, out2[0].size)

        // Intermediate
        val iPlain = byteArrayOf(8, 0, 0, 0) + ByteArray(8) { 0x33 } +
            byteArrayOf(4, 0, 0, 0) + ByteArray(4) { 0x44 }
        val iCipher = iPlain.copyOf()
        val splitterI = MsgSplitter(HandshakeCrypto.PROTO_INTERMEDIATE_INT)
        val parts = splitterI.split(iCipher, iPlain)
        assertEquals(2, parts.size)
        assertEquals(12, parts[0].size)
        assertEquals(8, parts[1].size)
    }

    @Test
    fun fakeTlsVerifiesClientHelloRejectsReplayAndWrapsStreams() {
        val secret = ByteArray(16) { (it + 0xA0).toByte() }
        val nowSec = 1_760_000_000L

        // Build minimal TLS ClientHello (record len 64)
        val body = ByteArray(64)
        body[0] = 0x01 // ClientHello
        body[1] = 0x00; body[2] = 0x00; body[3] = 60
        body[4] = 0x03; body[5] = 0x03 // TLS 1.2
        val rawHello = byteArrayOf(0x16, 0x03, 0x01, 0x00, 64) + body

        val expected = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(rawHello)
        }
        val hello = rawHello.copyOf()
        System.arraycopy(expected, 0, hello, FakeTls.CLIENT_RANDOM_OFFSET, 28)
        for (i in 0 until 4) {
            val tsByte = ((nowSec ushr (i * 8)) and 0xFF).toInt()
            hello[FakeTls.CLIENT_RANDOM_OFFSET + 28 + i] =
                (tsByte xor (expected[28 + i].toInt() and 0xFF)).toByte()
        }

        val verified = FakeTls.verifyClientHello(hello, secret, nowSec = nowSec)
        assertNotNull(verified)
        assertEquals(nowSec, verified!!.timestamp)

        val cache = FakeTls.ReplayCache()
        assertTrue(cache.firstSeen(verified.clientRandom, nowMs = 1000L))
        assertFalse(cache.firstSeen(verified.clientRandom, nowMs = 2000L))

        val serverHello = FakeTls.buildServerHello(
            secret = secret,
            clientRandom = verified.clientRandom,
            sessionId = verified.sessionId,
        )
        assertEquals(0x16, serverHello[0].toInt() and 0xFF)

        val zeroedServer = serverHello.copyOf()
        zeroedServer.fill(0, 11, 43)
        val expectedServerRand = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(verified.clientRandom + zeroedServer)
        }
        assertArrayEquals(expectedServerRand, serverHello.copyOfRange(11, 43))

        // FakeTlsStream roundtrip skipping CCS
        val payload = "Hello FakeTLS world!".toByteArray()
        val wrapped = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01) + FakeTls.wrapTlsRecord(payload)
        val outBuf = ByteArrayOutputStream()
        val tlsStream = FakeTlsStream(
            DataInputStream(ByteArrayInputStream(wrapped)),
            outBuf,
            Socket(),
        )
        val readBack = tlsStream.readExactly(payload.size)
        assertArrayEquals(payload, readBack)

        tlsStream.write(payload)
        assertArrayEquals(FakeTls.wrapTlsRecord(payload), outBuf.toByteArray())
    }

    @Test
    fun cfH2MtProtoFramingStripsAndAddsPaddingCleanly() {
        val body = ByteArray(40) { (it + 1).toByte() } // 24 + 16*1 aligned MTProto encrypted payload
        val encoded = CfH2Pool.encodeMtProtoReply(body, HandshakeCrypto.PROTO_TAG_SECURE)

        val key = ByteArray(32) { 0x42 }
        val iv = ByteArray(16) { 0x24 }
        val cltEncrypt = AesCtr(key, iv)
        val cltDecrypt = AesCtr(key, iv)

        val wireBytes = cltEncrypt.update(encoded)
        val memStream = object : ClientStream {
            private val input = DataInputStream(ByteArrayInputStream(wireBytes))
            override fun read(buf: ByteArray): Int = input.read(buf)
            override fun readExactly(n: Int): ByteArray {
                val b = ByteArray(n)
                input.readFully(b)
                return b
            }
            override fun write(data: ByteArray) = Unit
            override fun close() = Unit
        }

        val (decodedBody, quick) = CfH2Pool.readMtProtoPacket(
            memStream,
            cltDecrypt,
            HandshakeCrypto.PROTO_TAG_SECURE,
        )
        assertFalse(quick)
        assertArrayEquals(body, decodedBody)
    }

    @Test
    fun domainCensorKeepsTelegramIntactAndMasksOthers() {
        val msg = "Connecting kws2.web.telegram.org via pclead.co.uk and secret.workers.dev"
        val censored = DomainCensor.censor(msg)
        assertTrue(censored.contains("kws2.web.telegram.org"))
        assertFalse(censored.contains("pclead.co.uk"))
        assertFalse(censored.contains("secret.workers.dev"))
        assertTrue(censored.contains("pcl***.c*.uk"))
        assertTrue(censored.contains("sec***.wor****.dev"))
    }

    @Test
    fun proxyConfigH2DisabledWhenNoSecureEnabled() {
        val secureCfg = ProxyConfig(fallbackCfproxy = true, cfproxyH2Media = true, disableSecure = false)
        assertTrue(secureCfg.h2Enabled)

        val noSecureCfg = secureCfg.copy(disableSecure = true)
        assertFalse(noSecureCfg.h2Enabled)

        val eeCfg = noSecureCfg.copy(secret = "00112233445566778899aabbccddeeff", fakeTlsDomain = "example.com")
        val link = buildTelegramProxyLink(eeCfg, "127.0.0.1")
        assertTrue(link.startsWith("tg://proxy?server=127.0.0.1&port=1443&secret=ee00112233445566778899aabbccddeeff"))
    }

    @Test
    fun appUpdaterVersionComparisonAndJsonParsing() {
        assertTrue(AppUpdater.isNewerVersion("1.0.0", "v1.1.0"))
        assertTrue(AppUpdater.isNewerVersion("1.1.0-debug", "v1.1.1"))
        assertTrue(AppUpdater.isNewerVersion("1.2.0", "1.10.0"))
        assertFalse(AppUpdater.isNewerVersion("1.1.0", "v1.1.0"))
        assertFalse(AppUpdater.isNewerVersion("1.2.0", "v1.1.9"))

        val trustedApk = "https://github.com/Gramewa/tg-ws-proxy-android/releases/download/v1.1.0/tg-ws-proxy-android-v1.1.0.apk"
        assertTrue(AppUpdater.isTrustedGithubHttpsUrl(trustedApk))
        assertFalse(AppUpdater.isTrustedGithubHttpsUrl("http://github.com/Gramewa/tg-ws-proxy-android/releases/download/v1.1.0/a.apk"))
        assertFalse(AppUpdater.isTrustedGithubHttpsUrl("https://example.com/evil.apk"))
        assertFalse(AppUpdater.isTrustedGithubHttpsUrl("https://github.com/other/repo/releases/download/v1.0.0/evil.apk"))

        val releaseJson = """
            {
              "tag_name": "v1.1.0",
              "name": "TG WS Proxy Android v1.1.0",
              "body": "Добавлено автообновление и Простой режим.",
              "html_url": "https://github.com/Gramewa/tg-ws-proxy-android/releases/tag/v1.1.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "tg-ws-proxy-android-v1.1.0-debug.apk",
                  "browser_download_url": "$trustedApk",
                  "size": 1000
                },
                {
                  "name": "untrusted.apk",
                  "browser_download_url": "https://example.com/untrusted.apk",
                  "size": 500
                },
                {
                  "name": "tg-ws-proxy-android-v1.1.0.apk",
                  "browser_download_url": "$trustedApk",
                  "size": 1700000
                }
              ]
            }
        """.trimIndent()

        val parsed = AppUpdater.parseReleaseJson(releaseJson)
        assertNotNull(parsed)
        parsed!!
        assertEquals("1.1.0", parsed.version)
        assertEquals("v1.1.0", parsed.tagName)
        assertEquals("tg-ws-proxy-android-v1.1.0.apk", parsed.apkFileName)
        assertEquals(trustedApk, parsed.apkDownloadUrl)
        assertEquals(1700000L, parsed.apkSizeBytes)

        val draftJson = """{"tag_name":"v1.2.0","draft":true,"prerelease":false,"assets":[]}"""
        assertNull(AppUpdater.parseReleaseJson(draftJson))
    }

    @Test
    fun securityHardeningRedactsSecretsAndRejectsPrivateMaskingIps() {
        val logLine = "Connect: tg://proxy?server=127.0.0.1&port=1443&secret=dd00112233445566778899aabbccddeeff"
        val censored = DomainCensor.censor(logLine)
        assertFalse(censored.contains("dd001122"))
        assertTrue(censored.contains("secret=***"))

        assertFalse(FakeTls.isPublicUnicastAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(FakeTls.isPublicUnicastAddress(InetAddress.getByName("192.168.1.1")))
        assertFalse(FakeTls.isPublicUnicastAddress(InetAddress.getByName("10.0.0.1")))
        assertTrue(FakeTls.isPublicUnicastAddress(InetAddress.getByName("149.154.167.220")))

        // Oversized Intermediate packet header (100 MB) must trigger safe pass-through rather than huge buffering
        val oversizedPlain = byteArrayOf(0x00, 0x00, 0x40, 0x06) + ByteArray(8)
        val splitter = MsgSplitter(HandshakeCrypto.PROTO_INTERMEDIATE_INT)
        val out = splitter.split(oversizedPlain.copyOf(), oversizedPlain)
        assertEquals(1, out.size)
        assertEquals(oversizedPlain.size, out[0].size)
    }

    @Test
    fun settingsJsonExportImportAndDiffHelpersRoundTrip() {
        val original = io.github.tgwsproxy.android.data.AppSettings(
            host = "127.0.0.1",
            port = 2443,
            secret = "0123456789abcdef0123456789abcdef",
            fakeTlsDomain = "example.com",
            disableSecure = true,
            bufferKb = 512,
            poolSize = 6,
            language = "en",
            themeMode = "dark",
        )
        val json = original.toExportJson()
        val targetBase = io.github.tgwsproxy.android.data.AppSettings(language = "ru", themeMode = "light")
        val restored = targetBase.applyImportedJson(json)
        assertNotNull(restored)
        restored!!
        assertTrue(restored.hasSameNetworkConfigAs(original))
        assertTrue(restored.hasSameClientLinkAs(original))
        // UI preferences stay untouched on target device
        assertEquals("ru", restored.language)
        assertEquals("light", restored.themeMode)

        val changedSecret = restored.copy(secret = "fedcba9876543210fedcba9876543210")
        assertFalse(changedSecret.hasSameClientLinkAs(original))
        assertFalse(changedSecret.hasSameNetworkConfigAs(original))

        assertNull(targetBase.applyImportedJson("""{"host":"999.999.1.1"}"""))
        assertNull(targetBase.applyImportedJson("""{"port":70000}"""))
        assertNull(targetBase.applyImportedJson("not-json"))
    }

    @Test
    fun aesCtrZeroAllocUpdateInPlaceAndUpdateIntoMatchUpdate() {
        val key = ByteArray(32) { (it * 11 + 7).toByte() }
        val iv = ByteArray(16) { (0xF0 + (it and 0x0F)).toByte() }
        val totalLen = 70_000
        val plain = ByteArray(totalLen) { ((it * 31 + 17) and 0xFF).toByte() }

        val reference = AesCtr(key, iv).update(plain)

        // Streaming updateInPlace with odd unaligned chunk sizes across >64KB
        val inPlaceBuf = ByteArray(totalLen + 13)
        System.arraycopy(plain, 0, inPlaceBuf, 5, totalLen)
        val inPlaceCipher = AesCtr(key, iv)
        val cuts = intArrayOf(1, 3, 15, 16, 17, 255, 4097, 65537)
        var pos = 0
        for (c in cuts) {
            val take = minOf(c, totalLen - pos)
            if (take <= 0) break
            inPlaceCipher.updateInPlace(inPlaceBuf, 5 + pos, take)
            pos += take
        }
        if (pos < totalLen) {
            inPlaceCipher.updateInPlace(inPlaceBuf, 5 + pos, totalLen - pos)
        }
        assertArrayEquals(reference, inPlaceBuf.copyOfRange(5, 5 + totalLen))

        // Streaming updateInto with non-zero srcOff and dstOff
        val intoDst = ByteArray(totalLen + 11)
        val intoCipher = AesCtr(key, iv)
        intoCipher.updateInto(plain, 0, 12345, intoDst, 3)
        intoCipher.updateInto(plain, 12345, totalLen - 12345, intoDst, 3 + 12345)
        assertArrayEquals(reference, intoDst.copyOfRange(3, 3 + totalLen))

        // Decrypt back in place
        val dec = AesCtr(key, iv)
        dec.updateInPlace(intoDst, 3, totalLen)
        assertArrayEquals(plain, intoDst.copyOfRange(3, 3 + totalLen))
    }

    @Test
    fun msgSplitterSliceFastPathCompactionAndLargePacketShrink() {
        // Large intermediate packet (>256KB) split across multiple slices with non-zero offsets
        val largePayloadLen = 300_000
        val largePacket = ByteArray(4 + largePayloadLen) { 0x55 }
        largePacket[0] = (largePayloadLen and 0xFF).toByte()
        largePacket[1] = ((largePayloadLen ushr 8) and 0xFF).toByte()
        largePacket[2] = ((largePayloadLen ushr 16) and 0xFF).toByte()
        largePacket[3] = ((largePayloadLen ushr 24) and 0xFF).toByte()

        val smallPacket = byteArrayOf(8, 0, 0, 0) + ByteArray(8) { 0x2A }
        val combinedPlain = largePacket + smallPacket + smallPacket
        val combinedCipher = combinedPlain.map { (it.toInt() xor 0x3C).toByte() }.toByteArray()

        val paddedPlain = ByteArray(7) + combinedPlain + ByteArray(5)
        val paddedCipher = ByteArray(11) + combinedCipher + ByteArray(9)

        val splitter = MsgSplitter(HandshakeCrypto.PROTO_INTERMEDIATE_INT)
        val collected = ArrayList<ByteArray>()
        var offset = 0
        val step = 13_000
        while (offset < combinedPlain.size) {
            val len = minOf(step, combinedPlain.size - offset)
            collected.addAll(
                splitter.split(
                    chunk = paddedCipher,
                    chunkOff = 11 + offset,
                    plain = paddedPlain,
                    plainOff = 7 + offset,
                    len = len,
                ),
            )
            offset += len
        }

        assertEquals(3, collected.size)
        assertEquals(largePacket.size, collected[0].size)
        assertEquals(smallPacket.size, collected[1].size)
        assertEquals(smallPacket.size, collected[2].size)
        assertArrayEquals(combinedCipher.copyOfRange(0, largePacket.size), collected[0])
        assertTrue(splitter.flush().isEmpty())

        // Fast-path two small packets in one call after shrink
        val twoSmallPlain = smallPacket + smallPacket
        val twoSmallCipher = twoSmallPlain.copyOf()
        val fastParts = splitter.split(twoSmallCipher, 0, twoSmallPlain, 0, twoSmallPlain.size)
        assertEquals(2, fastParts.size)
        assertArrayEquals(smallPacket, fastParts[0])
        assertArrayEquals(smallPacket, fastParts[1])
    }

    @Test
    fun fakeTlsStreamSliceWriteAndMultiRecordRead() {
        val payload = ByteArray(25_000) { ((it * 19 + 3) and 0xFF).toByte() }
        val wireIn = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01) +
            FakeTls.wrapTlsRecord(payload, 0, 10_000) +
            FakeTls.wrapTlsRecord(payload, 10_000, payload.size - 10_000)

        val outBuf = ByteArrayOutputStream()
        val tlsStream = FakeTlsStream(
            DataInputStream(ByteArrayInputStream(wireIn)),
            outBuf,
            Socket(),
        )

        val readOut = ByteArrayOutputStream()
        val tmp = ByteArray(4096)
        while (true) {
            val n = tlsStream.read(tmp)
            if (n <= 0) break
            readOut.write(tmp, 0, n)
        }
        assertArrayEquals(payload, readOut.toByteArray())

        // Slice write with non-zero offset spanning multiple TLS records (>16384 bytes)
        val padded = ByteArray(9) + payload + ByteArray(6)
        tlsStream.write(padded, 9, payload.size)
        assertArrayEquals(FakeTls.wrapTlsRecord(payload, 0, payload.size), outBuf.toByteArray())
    }

    @Test
    fun dnsResolverWireFormatIpValidationAndHappyEyeballsOrdering() {
        assertTrue(DnsResolver.isValidIpAddress("104.16.132.229"))
        assertTrue(DnsResolver.isValidIpAddress("2606:4700:4700::1111"))
        assertTrue(DnsResolver.isValidIpAddress("[2606:4700::1111]"))
        assertFalse(DnsResolver.isValidIpAddress(""))
        assertFalse(DnsResolver.isValidIpAddress("999.1.2.3"))
        assertFalse(DnsResolver.isValidIpAddress("example.com"))

        // Query encoding check
        val query = DnsResolver.buildDnsQuery("kws2.example.com", 1)
        assertTrue(query.size > 12)
        assertEquals(0x01, query[2].toInt() and 0xFF) // RD=1
        assertEquals(1, ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)) // QDCOUNT=1
        assertEquals(1, query[query.size - 3].toInt() and 0xFF) // QTYPE=A
        assertEquals(1, query[query.size - 1].toInt() and 0xFF) // QCLASS=IN

        // Synthetic DNS response with 1 question + compressed A (TTL 120) and AAAA (TTL 60) answers
        val resp = ByteArrayOutputStream()
        // Header: ID=0, Flags=0x8180 (QR=1, RD=1, RA=1, RCODE=0), QD=1, AN=2, NS=0, AR=0
        resp.write(byteArrayOf(0x00, 0x00, 0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00))
        // Question: kws2.example.com (starts at offset 12), QTYPE=A, QCLASS=IN
        resp.write(query, 12, query.size - 12)
        // Answer 1: ptr 0xC00C, TYPE=A (1), CLASS=IN (1), TTL=120, RDLEN=4, 104.16.132.229
        resp.write(byteArrayOf(0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 120, 0x00, 0x04))
        resp.write(byteArrayOf(104, 16, 132.toByte(), 229.toByte()))
        // Answer 2: ptr 0xC00C, TYPE=AAAA (28), CLASS=IN (1), TTL=60, RDLEN=16, 2606:4700:4700::1111
        val v6Bytes = InetAddress.getByName("2606:4700:4700::1111").address
        resp.write(byteArrayOf(0xC0.toByte(), 0x0C, 0x00, 0x1C, 0x00, 0x01, 0x00, 0x00, 0x00, 60, 0x00, 0x10))
        resp.write(v6Bytes)

        val (parsedAddrs, ttl) = DnsResolver.parseDnsResponse(resp.toByteArray())
        assertEquals(2, parsedAddrs.size)
        assertEquals("104.16.132.229", parsedAddrs[0].hostAddress)
        assertArrayEquals(v6Bytes, parsedAddrs[1].address)
        assertEquals(60L, ttl)

        // NXDOMAIN (rcode=3) yields empty result
        val nxPacket = resp.toByteArray().also { it[3] = (it[3].toInt() or 0x03).toByte() }
        assertTrue(DnsResolver.parseDnsResponse(nxPacket).first.isEmpty())

        // Happy Eyeballs ordering
        val a1 = InetAddress.getByName("104.16.132.229")
        val a2 = InetAddress.getByName("104.16.133.229")
        val b1 = InetAddress.getByName("2606:4700:4700::1111")
        val b2 = InetAddress.getByName("2606:4700:4700::1001")
        val mixed = listOf(b1, a1, b2, a2, a1) // includes duplicate a1

        val autoOrder = DnsResolver.orderByPreference(mixed, DnsResolver.IP_PREF_AUTO)
        assertEquals(listOf(a1, b1, a2, b2), autoOrder)
        assertEquals(listOf(a1, a2), DnsResolver.orderByPreference(mixed, DnsResolver.IP_PREF_IPV4))
        assertEquals(listOf(b1, b2), DnsResolver.orderByPreference(mixed, DnsResolver.IP_PREF_IPV6))
    }

    @Test
    fun domainBalancerEwmaCooldownPort80AndProbeWinner() {
        val balancer = DomainBalancer()
        balancer.updateDomainsList(listOf("slow.co.uk", "fast.co.uk", "mid.co.uk"))

        val t0 = 1_000_000L
        balancer.reportSuccess(dcId = 2, domain = "slow.co.uk", latencyMs = 450L, nowMs = t0)
        balancer.reportSuccess(dcId = 4, domain = "mid.co.uk", latencyMs = 200L, nowMs = t0)
        balancer.reportSuccess(dcId = 1, domain = "fast.co.uk", latencyMs = 70L, nowMs = t0)

        // Unpinned DC3 should rank healthy measured domains by EWMA latency (fast < mid < slow)
        balancer.updateDomainForDc(3, "fast.co.uk")
        val orderDc3 = balancer.getDomainsForDc(3, nowMs = t0 + 100L)
        assertEquals(listOf("fast.co.uk", "mid.co.uk", "slow.co.uk"), orderDc3)

        // TLS-specific failure triggers port-80 preference without global cooldown
        balancer.reportFailure("fast.co.uk", tlsSpecific = true, nowMs = t0 + 200L)
        assertTrue(balancer.shouldPreferPort80("fast.co.uk", nowMs = t0 + 500L))
        assertFalse(balancer.isDomainInCooldown("fast.co.uk", nowMs = t0 + 500L))

        // Hard failure puts domain into cooldown and demotes it behind healthy domains
        balancer.reportFailure("fast.co.uk", tlsSpecific = false, nowMs = t0 + 600L)
        assertTrue(balancer.isDomainInCooldown("fast.co.uk", nowMs = t0 + 1000L))
        val demotedOrder = balancer.getDomainsForDc(3, nowMs = t0 + 1000L)
        assertEquals("fast.co.uk", demotedOrder.last())

        // Diagnostic probe winner takes top slot across DCs
        balancer.applyProbeWinner("winner.co.uk", latencyMs = 55L, nowMs = t0 + 2000L)
        assertEquals("winner.co.uk", balancer.getDomainsForDc(2, nowMs = t0 + 2100L).first())
        assertEquals("winner.co.uk", balancer.getDomainsForDc(4, nowMs = t0 + 2100L).first())

        // Network change clears cooldowns and TLS port-80 flags
        balancer.onNetworkChanged()
        assertFalse(balancer.isDomainInCooldown("fast.co.uk", nowMs = t0 + 2200L))
        assertFalse(balancer.shouldPreferPort80("fast.co.uk", nowMs = t0 + 2200L))
    }

    @Test
    fun rawWebSocketAcceptHeaderMatchesRfc6455Vector() {
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            RawWebSocket.computeWebSocketAccept("dGhlIHNhbXBsZSBub25jZQ=="),
        )
    }

    @Test
    fun settingsV1CompatV2RoundTripAndHotReloadDiff() {
        val base = io.github.tgwsproxy.android.data.AppSettings(
            host = "127.0.0.1",
            port = 1443,
            secret = "0123456789abcdef0123456789abcdef",
            autoPort80Fallback = false,
            frontingSni = "cloudflare.com, apple.com",
            dohProvider = DnsResolver.DOH_CLOUDFLARE,
            cfCleanIp = "104.16.132.229",
            ipPreference = DnsResolver.IP_PREF_IPV4,
        )

        // Hot-reload eligible changes keep hasSameBindConfigAs == true
        val hotChanged = base.copy(
            dohProvider = DnsResolver.DOH_QUAD9,
            autoPort80Fallback = true,
            cfCleanIp = "2606:4700::1111",
            poolSize = 8,
        )
        assertTrue(hotChanged.hasSameBindConfigAs(base))
        assertFalse(hotChanged.hasSameNetworkConfigAs(base))

        // Bind-affecting change requires restart
        assertFalse(base.copy(port = 2443).hasSameBindConfigAs(base))
        assertFalse(base.copy(fakeTlsDomain = "example.com").hasSameBindConfigAs(base))

        // V2 JSON roundtrip preserves all new v1.3.0 fields
        val v2Json = base.toExportJson()
        val importedV2 = io.github.tgwsproxy.android.data.AppSettings().applyImportedJson(v2Json)
        assertNotNull(importedV2)
        importedV2!!
        assertTrue(importedV2.hasSameNetworkConfigAs(base))
        assertFalse(importedV2.autoPort80Fallback)
        assertEquals("cloudflare.com, apple.com", importedV2.frontingSni)
        assertEquals(DnsResolver.DOH_CLOUDFLARE, importedV2.dohProvider)
        assertEquals("104.16.132.229", importedV2.cfCleanIp)
        assertEquals(DnsResolver.IP_PREF_IPV4, importedV2.ipPreference)

        // V1 JSON (missing v1.3.0 keys) imports cleanly using defaults
        val v1Json = """{"v":1,"host":"127.0.0.1","port":1443,"secret":"0123456789abcdef0123456789abcdef","bufferKb":256,"poolSize":4}"""
        val importedV1 = io.github.tgwsproxy.android.data.AppSettings().applyImportedJson(v1Json)
        assertNotNull(importedV1)
        importedV1!!
        assertTrue(importedV1.autoPort80Fallback)
        assertEquals(DEFAULT_FRONTING_SNI, importedV1.frontingSni)
        assertEquals(DnsResolver.DOH_SYSTEM, importedV1.dohProvider)
        assertEquals("", importedV1.cfCleanIp)
        assertEquals(DnsResolver.IP_PREF_AUTO, importedV1.ipPreference)

        // Validation rejects malformed cfCleanIp and frontingSni
        assertNull(base.applyImportedJson("""{"cfCleanIp":"not-an-ip"}"""))
        assertNull(base.applyImportedJson("""{"frontingSni":"https://bad/host"}"""))
    }

    @Test
    fun rawWebSocketReadHttpLineCapsHostileUnboundedLine() {
        val hugeLine = ByteArray(20_000) { 'A'.code.toByte() }
        val line = RawWebSocket.readHttpLine(ByteArrayInputStream(hugeLine), maxLineBytes = 512)
        assertNotNull(line)
        assertEquals(512, line!!.length)

        val normal = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n".toByteArray()
        val input = ByteArrayInputStream(normal)
        assertEquals("HTTP/1.1 101 Switching Protocols", RawWebSocket.readHttpLine(input))
        assertEquals("Upgrade: websocket", RawWebSocket.readHttpLine(input))
        assertEquals("", RawWebSocket.readHttpLine(input))
        assertNull(RawWebSocket.readHttpLine(input))
    }

    @Test
    fun proxyConfigH2DisabledForTestDc() {
        val cfg = ProxyConfig(
            fallbackCfproxy = true,
            cfproxyH2Media = true,
            disableSecure = false,
            forceTestDc = true,
        )
        assertFalse(cfg.h2Enabled)
    }
}
