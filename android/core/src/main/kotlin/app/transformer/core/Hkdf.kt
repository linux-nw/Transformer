package app.transformer.core

import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 5869 HMAC-based key derivation (HKDF), HMAC-SHA256 only. Verified
 * against the RFC's own test vectors in HkdfTest — both the extract and
 * expand steps are the plain recursive HMAC construction the RFC defines,
 * nothing improvised.
 */
object Hkdf {
    private const val HMAC_ALGO = "HmacSHA256"
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val saltKey = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(saltKey, HMAC_ALGO))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(HASH_LEN * 255)) { "HKDF output length out of range: $length" }
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(prk, HMAC_ALGO))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    /** Derives a 256-bit AES key from [ikm] and a fixed, purpose-specific [infoLabel]
     * (domain separation — a different label always yields an unrelated key). */
    fun deriveAesKey(ikm: SecretKey, infoLabel: String): SecretKey {
        val prk = extract(ByteArray(0), ikm.encoded)
        val okm = expand(prk, infoLabel.toByteArray(Charsets.UTF_8), 32)
        return SecretKeySpec(okm, "AES")
    }
}
