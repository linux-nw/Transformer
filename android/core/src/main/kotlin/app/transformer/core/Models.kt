package app.transformer.core

import kotlinx.serialization.Serializable

/** AES-256-GCM ciphertext: iv and data are both Base64. */
@Serializable
data class Envelope(val iv: String, val data: String)

/** Everything sent over a [PeerSocketLink] is one line of this, NDJSON-framed.
 * `type` stays in the clear so the reader knows how to decrypt+decode
 * `envelope` before touching its contents. */
@Serializable
data class Frame(val type: String, val envelope: Envelope)

/** A chat message or shared link. `kind` is "text" or "link". */
@Serializable
data class MessageBody(
    val kind: String,
    val text: String? = null,
    val title: String? = null,
    val domain: String? = null,
    val url: String? = null,
    val time: String,
    val msgId: String,
)

@Serializable
data class FileMetaBody(
    val transferId: String,
    val name: String,
    val size: Long,
    val mime: String,
    val totalChunks: Int,
    val time: Long,
)

@Serializable
data class FileChunkBody(val transferId: String, val index: Int, val chunkB64: String)

@Serializable
data class AckBody(val transferId: String)

/** What a device shows as a QR code (or receives by scanning one).
 * `key` is only present the first time two devices pair — on every later
 * reconnect it is omitted and both sides look the shared key up locally by
 * [id], so the secret only ever crosses devices once. */
@Serializable
data class PairingPayload(
    val v: Int = 1,
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val key: String? = null,
)

/** The plaintext preamble line a connecting client sends right after the
 * TCP handshake, before anything is encrypted, so the accepting side knows
 * which stored peer (and therefore which key) this socket belongs to.
 * [nonce] is a fresh random value the client generates for this connection
 * only — both sides fold it into the HKDF session-key derivation, so every
 * new connection gets entirely different keys even when it's the same
 * pairing reconnecting. That's what stops a frame captured on one
 * connection from being replayed as if it were part of a different one:
 * the replayed ciphertext simply won't decrypt under the new connection's
 * keys. */
@Serializable
data class HelloBody(val id: String, val name: String, val nonce: String)

enum class Direction { SEND, RECEIVE }
enum class TransferStatus { ACTIVE, WAITING, DONE, FAILED }

data class TransferProgress(
    val peerId: String,
    val transferId: String,
    val direction: Direction,
    val name: String,
    val size: Long,
    val status: TransferStatus,
    val percent: Int,
    val reason: String? = null,
    /** Only set once a receive reaches DONE. */
    val bytes: ByteArray? = null,
    val mime: String? = null,
)

enum class IncomingDecision { ACCEPT, REJECT }

/** Which side of a [PeerConnection] this device is on — the dialer or the
 * accepter. Fixes which of the two HKDF-derived subkeys is used to send vs.
 * receive, so each direction has its own key. */
enum class ConnectionRole { CLIENT, SERVER }
