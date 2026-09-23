package app.transformer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CryptoTest {
    @Test
    fun `encrypt then decrypt round-trips arbitrary json`() {
        val key = Crypto.generateKey()
        val body = MessageBody(kind = "text", text = "Hallo Welt äöü 😀", time = "09:12", msgId = "m1")
        val envelope = encryptJson(key, body)
        val decoded: MessageBody = decryptJson(key, envelope)
        assertEquals(body, decoded)
    }

    @Test
    fun `key export then import produces an equivalent key`() {
        val key = Crypto.generateKey()
        val b64 = Crypto.exportKeyB64(key)
        val imported = Crypto.importKeyB64(b64)
        val envelope = Crypto.encrypt(key, "secret".toByteArray())
        val plain = Crypto.decrypt(imported, envelope)
        assertEquals("secret", String(plain))
    }

    @Test
    fun `wrong key fails to decrypt`() {
        val key = Crypto.generateKey()
        val other = Crypto.generateKey()
        val envelope = Crypto.encrypt(key, "secret".toByteArray())
        assertThrows(Exception::class.java) { Crypto.decrypt(other, envelope) }
    }

    @Test
    fun `matching aad round-trips`() {
        val key = Crypto.generateKey()
        val envelope = Crypto.encrypt(key, "secret".toByteArray(), aad = "msg:0".toByteArray())
        assertEquals("secret", String(Crypto.decrypt(key, envelope, aad = "msg:0".toByteArray())))
    }

    @Test
    fun `mismatched aad fails to decrypt - detects replay or reorder`() {
        val key = Crypto.generateKey()
        val envelope = Crypto.encrypt(key, "secret".toByteArray(), aad = "msg:0".toByteArray())
        assertThrows(Exception::class.java) { Crypto.decrypt(key, envelope, aad = "msg:1".toByteArray()) }
    }
}
