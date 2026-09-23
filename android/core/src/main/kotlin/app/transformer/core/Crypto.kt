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

    /** [aad] (additional authenticated data) is bound into the GCM tag but
     * never encrypted — used to tie a ciphertext to context (frame type,
     * sequence number) that must match exactly on decrypt or the tag check
     * fails, e.g. to detect a replayed or reordered frame. */
    fun encrypt(key: SecretKey, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): Envelope {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return Envelope(
            iv = Base64.getEncoder().encodeToString(iv),
            data = Base64.getEncoder().encodeToString(ciphertext),
        )
    }

    /** Throws (AEADBadTagException) if [aad] doesn't match what the sender
     * used, exactly as if the ciphertext itself had been tampered with. */
    fun decrypt(key: SecretKey, envelope: Envelope, aad: ByteArray = ByteArray(0)): ByteArray {
        val iv = Base64.getDecoder().decode(envelope.iv)
        val ciphertext = Base64.getDecoder().decode(envelope.data)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}

@PublishedApi
internal val protocolJson = Json { ignoreUnknownKeys = true }

inline fun <reified T> encryptJson(key: SecretKey, value: T, aad: ByteArray = ByteArray(0)): Envelope =
    Crypto.encrypt(key, protocolJson.encodeToString(value).toByteArray(Charsets.UTF_8), aad)

inline fun <reified T> decryptJson(key: SecretKey, envelope: Envelope, aad: ByteArray = ByteArray(0)): T =
    protocolJson.decodeFromString(Crypto.decrypt(key, envelope, aad).toString(Charsets.UTF_8))
