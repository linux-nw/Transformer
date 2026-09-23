package app.transformer.core

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * AES-256-GCM for every message and file chunk, on top of whatever
 * transport security the socket already has (none, here — see
 * [PeerSocketLink]). The key is generated once at pairing time and only
 * ever leaves a device inside the first QR code; every reconnect reuses it.
 */
object Crypto {
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val KEY_ALGO = "AES"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun generateKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KEY_ALGO)
        gen.init(256, random)
        return gen.generateKey()
    }

    fun exportKeyB64(key: SecretKey): String = Base64.getEncoder().encodeToString(key.encoded)

    fun importKeyB64(b64: String): SecretKey =
        SecretKeySpec(Base64.getDecoder().decode(b64), KEY_ALGO)

    fun encrypt(key: SecretKey, plaintext: ByteArray): Envelope {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return Envelope(
            iv = Base64.getEncoder().encodeToString(iv),
            data = Base64.getEncoder().encodeToString(ciphertext),
        )
    }

    fun decrypt(key: SecretKey, envelope: Envelope): ByteArray {
        val iv = Base64.getDecoder().decode(envelope.iv)
        val ciphertext = Base64.getDecoder().decode(envelope.data)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}

@PublishedApi
internal val protocolJson = Json { ignoreUnknownKeys = true }

inline fun <reified T> encryptJson(key: SecretKey, value: T): Envelope =
    Crypto.encrypt(key, protocolJson.encodeToString(value).toByteArray(Charsets.UTF_8))

inline fun <reified T> decryptJson(key: SecretKey, envelope: Envelope): T =
    protocolJson.decodeFromString(Crypto.decrypt(key, envelope).toString(Charsets.UTF_8))
