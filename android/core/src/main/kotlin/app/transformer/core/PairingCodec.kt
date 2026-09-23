package app.transformer.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Encodes/decodes the JSON payload that a QR code carries for pairing. */
object PairingCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(payload: PairingPayload): String = json.encodeToString(payload)

    fun decode(text: String): PairingPayload = json.decodeFromString(text)

    fun encodeHello(hello: HelloBody): String = json.encodeToString(hello)

    fun decodeHello(text: String): HelloBody = json.decodeFromString(text)
}
