package app.transformer.data

import kotlinx.serialization.Serializable

@Serializable
data class StoredPeer(
    val id: String,
    val name: String,
    val keyB64: String,
    /** Last known address — tried first on reconnect, before waiting on NSD to find it again. */
    val lastHost: String? = null,
    val lastPort: Int? = null,
)

@Serializable
data class AppSettings(
    val myDeviceName: String = "Mein Handy",
    val autoAccept: Boolean = true,
    val notifications: Boolean = true,
    val darkTheme: Boolean = false,
)
