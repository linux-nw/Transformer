package app.transformer.core

import java.util.HexFormat
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Exact RFC 5869 Appendix A test vectors (SHA-256 cases). Fetched from the
 * Go standard library's own hkdf_test.go (which mirrors the RFC verbatim)
 * and independently re-derived from scratch with a plain HMAC-SHA256
 * implementation before being trusted here, so these hex constants are not
 * something typed from memory.
 */
class HkdfTest {
    private fun hex(s: String): ByteArray = HexFormat.of().parseHex(s)

    @Test
    fun `RFC 5869 test case 1 - SHA-256, basic`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expectedPrk = hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
        val expectedOkm = hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")

        val prk = Hkdf.extract(salt, ikm)
        assertArrayEquals(expectedPrk, prk)

        val okm = Hkdf.expand(prk, info, 42)
        assertArrayEquals(expectedOkm, okm)
    }

    @Test
    fun `RFC 5869 test case 3 - SHA-256, zero-length salt and info`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = ByteArray(0)
        val info = ByteArray(0)
        val expectedPrk = hex("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04")
        val expectedOkm = hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8")

        val prk = Hkdf.extract(salt, ikm)
        assertArrayEquals(expectedPrk, prk)

        val okm = Hkdf.expand(prk, info, 42)
        assertArrayEquals(expectedOkm, okm)
    }

    @Test
    fun `expand rejects an out-of-range length`() {
        val prk = ByteArray(32)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            Hkdf.expand(prk, ByteArray(0), 32 * 255 + 1)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            Hkdf.expand(prk, ByteArray(0), 0)
        }
    }

    @Test
    fun `deriveAesKey is deterministic and label-separated`() {
        val ikm = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val a1 = Hkdf.deriveAesKey(ikm, "transformer/c2s/v1")
        val a2 = Hkdf.deriveAesKey(ikm, "transformer/c2s/v1")
        val b = Hkdf.deriveAesKey(ikm, "transformer/s2c/v1")

        assertEquals(32, a1.encoded.size)
        assertArrayEquals(a1.encoded, a2.encoded)
        assertNotEquals(a1.encoded.toList(), b.encoded.toList())
    }
}
