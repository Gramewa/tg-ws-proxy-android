package io.github.tgwsproxy.android.proxy

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Deterministic streaming AES-CTR implemented over AES/ECB/NoPadding.
 * Guarantees 1-byte and arbitrary-size reads/writes advance the 128-bit counter
 * identically to Python cryptography / OpenSSL EVP_EncryptUpdate.
 */
class AesCtr(key: ByteArray, iv: ByteArray) {
    private val ecb: Cipher
    private val counter: ByteArray
    private val keystream = ByteArray(16)
    private var offset = 16

    init {
        require(key.size == 16 || key.size == 24 || key.size == 32) {
            "AES key must be 16, 24, or 32 bytes"
        }
        require(iv.size == 16) { "CTR IV must be 16 bytes" }
        ecb = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        counter = iv.copyOf()
    }

    fun update(data: ByteArray, off: Int = 0, len: Int = data.size - off): ByteArray {
        if (len <= 0) return ByteArray(0)
        val out = ByteArray(len)
        for (i in 0 until len) {
            if (offset == 16) {
                ecb.doFinal(counter, 0, 16, keystream, 0)
                incrementCounter()
                offset = 0
            }
            out[i] = (data[off + i].toInt() xor keystream[offset++].toInt()).toByte()
        }
        return out
    }

    private fun incrementCounter() {
        for (i in 15 downTo 0) {
            val v = (counter[i].toInt() and 0xFF) + 1
            counter[i] = v.toByte()
            if (v <= 0xFF) break
        }
    }
}

class CryptoCtx(
    val cltDec: AesCtr,
    val cltEnc: AesCtr,
    val tgEnc: AesCtr,
    val tgDec: AesCtr,
)

data class HandshakeResult(
    val dcId: Int,
    val isMedia: Boolean,
    val protoTag: ByteArray,
    val protoInt: Int,
    val decPrekeyAndIv: ByteArray,
)

object HandshakeCrypto {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    const val PROTO_ABRIDGED_INT = 0xEFEFEFEF.toInt()
    const val PROTO_INTERMEDIATE_INT = 0xEEEEEEEE.toInt()
    const val PROTO_PADDED_INTERMEDIATE_INT = 0xDDDDDDDD.toInt()

    val PROTO_TAG_ABRIDGED = byteArrayOf(0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte())
    val PROTO_TAG_INTERMEDIATE = byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte())
    val PROTO_TAG_SECURE = byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte())

    private val ZERO_64 = ByteArray(64)
    private val random = SecureRandom()

    private const val RESERVED_FIRST_BYTE = 0xEF.toByte()
    private val RESERVED_STARTS = listOf(
        byteArrayOf(0x48, 0x45, 0x41, 0x44), // HEAD
        byteArrayOf(0x50, 0x4F, 0x53, 0x54), // POST
        byteArrayOf(0x47, 0x45, 0x54, 0x20), // GET
        byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte()),
        byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte()),
        byteArrayOf(0x16, 0x03, 0x01, 0x02),
        byteArrayOf(0x4F, 0x50, 0x54, 0x49), // OPTI
    )
    private val RESERVED_CONTINUE = byteArrayOf(0x00, 0x00, 0x00, 0x00)

    fun tryHandshake(handshake: ByteArray, secret: ByteArray): HandshakeResult? {
        if (handshake.size != HANDSHAKE_LEN) return null

        val decPrekeyAndIv = handshake.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val decPrekey = decPrekeyAndIv.copyOfRange(0, PREKEY_LEN)
        val decIv = decPrekeyAndIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)

        val decKey = sha256(decPrekey, secret)
        val decrypted = AesCtr(decKey, decIv).update(handshake)

        val protoTag = decrypted.copyOfRange(PROTO_TAG_POS, PROTO_TAG_POS + 4)
        val protoInt = when {
            protoTag.contentEquals(PROTO_TAG_ABRIDGED) -> PROTO_ABRIDGED_INT
            protoTag.contentEquals(PROTO_TAG_INTERMEDIATE) -> PROTO_INTERMEDIATE_INT
            protoTag.contentEquals(PROTO_TAG_SECURE) -> PROTO_PADDED_INTERMEDIATE_INT
            else -> return null
        }

        val lo = decrypted[DC_IDX_POS].toInt() and 0xFF
        val hi = decrypted[DC_IDX_POS + 1].toInt() // signed high byte for int16 LE
        val dcIdx = (hi shl 8) or lo

        val dcId = kotlin.math.abs(dcIdx)
        val isMedia = dcIdx < 0
        return HandshakeResult(
            dcId = dcId,
            isMedia = isMedia,
            protoTag = protoTag,
            protoInt = protoInt,
            decPrekeyAndIv = decPrekeyAndIv,
        )
    }

    fun generateRelayInit(protoTag: ByteArray, dcIdx: Int): ByteArray {
        val rnd = ByteArray(HANDSHAKE_LEN)
        while (true) {
            random.nextBytes(rnd)
            if (rnd[0] == RESERVED_FIRST_BYTE) continue
            val first4 = rnd.copyOfRange(0, 4)
            if (RESERVED_STARTS.any { it.contentEquals(first4) }) continue
            val cont = rnd.copyOfRange(4, 8)
            if (cont.contentEquals(RESERVED_CONTINUE)) continue
            break
        }

        val encKey = rnd.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN)
        val encIv = rnd.copyOfRange(SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val encryptor = AesCtr(encKey, encIv)

        val tailPlain = ByteArray(8)
        System.arraycopy(protoTag, 0, tailPlain, 0, 4)
        tailPlain[4] = (dcIdx and 0xFF).toByte()
        tailPlain[5] = ((dcIdx ushr 8) and 0xFF).toByte()
        val tailRand = ByteArray(2)
        random.nextBytes(tailRand)
        tailPlain[6] = tailRand[0]
        tailPlain[7] = tailRand[1]

        val encryptedFull = encryptor.update(rnd)
        val result = rnd.copyOf()
        for (i in 0 until 8) {
            val keystreamByte = encryptedFull[PROTO_TAG_POS + i].toInt() xor rnd[PROTO_TAG_POS + i].toInt()
            result[PROTO_TAG_POS + i] = (tailPlain[i].toInt() xor keystreamByte).toByte()
        }
        return result
    }

    fun buildCryptoCtx(
        clientDecPrekeyIv: ByteArray,
        secret: ByteArray,
        relayInit: ByteArray,
    ): CryptoCtx {
        val cltDecPrekey = clientDecPrekeyIv.copyOfRange(0, PREKEY_LEN)
        val cltDecIv = clientDecPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)
        val cltDecKey = sha256(cltDecPrekey, secret)

        val cltEncPrekeyIv = clientDecPrekeyIv.reversedArray()
        val cltEncKey = sha256(cltEncPrekeyIv.copyOfRange(0, PREKEY_LEN), secret)
        val cltEncIv = cltEncPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)

        val cltDecryptor = AesCtr(cltDecKey, cltDecIv)
        val cltEncryptor = AesCtr(cltEncKey, cltEncIv)
        cltDecryptor.update(ZERO_64)

        val relayEncKey = relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN)
        val relayEncIv = relayInit.copyOfRange(SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val relayDecPrekeyIv = relayInit
            .copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
            .reversedArray()
        val relayDecKey = relayDecPrekeyIv.copyOfRange(0, PREKEY_LEN)
        val relayDecIv = relayDecPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)

        val tgEncryptor = AesCtr(relayEncKey, relayEncIv)
        val tgDecryptor = AesCtr(relayDecKey, relayDecIv)
        tgEncryptor.update(ZERO_64)

        return CryptoCtx(cltDecryptor, cltEncryptor, tgEncryptor, tgDecryptor)
    }

    private fun sha256(a: ByteArray, b: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(a)
        md.update(b)
        return md.digest()
    }
}

class MsgSplitter(private val protoInt: Int) {
    companion object {
        const val MAX_SPLIT_PACKET_LEN = 4 * 1024 * 1024
    }

    private val cipherBuf = ByteArrayOutputStream()
    private val plainBuf = ByteArrayOutputStream()
    private var disabled = false

    fun split(chunk: ByteArray, plain: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        cipherBuf.write(chunk)
        plainBuf.write(plain)

        val cipherBytes = cipherBuf.toByteArray()
        val plainBytes = plainBuf.toByteArray()
        val parts = ArrayList<ByteArray>()
        var offset = 0
        val bufLen = cipherBytes.size

        while (offset < bufLen) {
            val packetLen = nextPacketLen(plainBytes, offset, bufLen - offset) ?: break
            if (packetLen <= 0) {
                parts.add(cipherBytes.copyOfRange(offset, bufLen))
                offset = bufLen
                disabled = true
                break
            }
            parts.add(cipherBytes.copyOfRange(offset, offset + packetLen))
            offset += packetLen
        }

        if (offset > 0) {
            cipherBuf.reset()
            plainBuf.reset()
            if (offset < bufLen) {
                cipherBuf.write(cipherBytes, offset, bufLen - offset)
                plainBuf.write(plainBytes, offset, bufLen - offset)
            }
        }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipherBuf.size() == 0) return emptyList()
        val tail = cipherBuf.toByteArray()
        cipherBuf.reset()
        plainBuf.reset()
        return listOf(tail)
    }

    private fun nextPacketLen(plain: ByteArray, offset: Int, avail: Int): Int? {
        if (avail <= 0) return null
        return when (protoInt) {
            HandshakeCrypto.PROTO_ABRIDGED_INT -> nextAbridgedLen(plain, offset, avail)
            HandshakeCrypto.PROTO_INTERMEDIATE_INT,
            HandshakeCrypto.PROTO_PADDED_INTERMEDIATE_INT -> nextIntermediateLen(plain, offset, avail)
            else -> 0
        }
    }

    private fun nextAbridgedLen(plain: ByteArray, offset: Int, avail: Int): Int? {
        val first = plain[offset].toInt() and 0xFF
        val payloadLen: Int
        val headerLen: Int
        if (first == 0x7F || first == 0xFF) {
            if (avail < 4) return null
            val b1 = plain[offset + 1].toInt() and 0xFF
            val b2 = plain[offset + 2].toInt() and 0xFF
            val b3 = plain[offset + 3].toInt() and 0xFF
            payloadLen = (b1 or (b2 shl 8) or (b3 shl 16)) * 4
            headerLen = 4
        } else {
            payloadLen = (first and 0x7F) * 4
            headerLen = 1
        }
        if (payloadLen <= 0 || payloadLen > MAX_SPLIT_PACKET_LEN) return 0
        val packetLen = headerLen + payloadLen
        return if (avail < packetLen) null else packetLen
    }

    private fun nextIntermediateLen(plain: ByteArray, offset: Int, avail: Int): Int? {
        if (avail < 4) return null
        val b0 = plain[offset].toInt() and 0xFF
        val b1 = plain[offset + 1].toInt() and 0xFF
        val b2 = plain[offset + 2].toInt() and 0xFF
        val b3 = plain[offset + 3].toInt() and 0xFF
        val raw = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        val payloadLen = raw and 0x7FFFFFFF
        if (payloadLen <= 0 || payloadLen > MAX_SPLIT_PACKET_LEN) return 0
        val packetLen = 4 + payloadLen
        return if (avail < packetLen) null else packetLen
    }
}
