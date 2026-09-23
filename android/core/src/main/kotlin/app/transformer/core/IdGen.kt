package app.transformer.core

import java.security.SecureRandom
import java.util.Base64

/** Short, URL/QR-friendly random ids for pairing/transfer/message ids. */
object IdGen {
    private val random = SecureRandom()

    fun next(): String {
        val bytes = ByteArray(9)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
