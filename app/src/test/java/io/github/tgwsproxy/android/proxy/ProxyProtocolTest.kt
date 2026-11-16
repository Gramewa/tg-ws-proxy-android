package io.github.tgwsproxy.android.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
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
        assertTrue(censored.contains(".co.uk"))
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
}
