package app.transformer.core

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey
import kotlin.math.ceil
import kotlin.math.min

const val DEFAULT_CHUNK_BYTES = 48 * 1024

/**
 * One live, authenticated, encrypted connection to a paired device. Wraps a
 * [PeerSocketLink] plus the shared pairing key and speaks the same four-frame
 * protocol (msg / file-meta / file-chunk / ack) as the web prototype.
 *
 * The static pairing key is never used to encrypt frames directly — instead
 * two independent AES-256 subkeys are derived from it via HKDF, one per
 * direction (`transformer/c2s/v1`, `transformer/s2c/v1`) and salted with
 * this connection's own random [connectionNonce] (from the hello preamble),
 * so a client and a server on the same connection never share a key, and no
 * two connections — even an immediate reconnect of the same pairing — ever
 * derive the same keys. Every frame also binds its type and a monotonically
 * increasing per-direction sequence number as GCM additional authenticated
 * data. Together this means: a frame replayed or reordered within the same
 * connection fails the tag check (wrong sequence number), and a frame
 * captured from one connection and replayed into a different one fails
 * outright (wrong keys) — both look exactly like a tampered ciphertext to
 * the receiver.
 *
 * [runReadLoop] blocks, so callers run it on a thread/dispatcher of their
 * own choosing; nothing arrives via [listener] until it's running. A
 * [PeerConnection] is one-shot: once the socket drops it must be discarded
 * and re-created on reconnect (via [PeerServer] accepting a new socket, or
 * [PeerClient] dialing out again).
 */
class PeerConnection(
    val peerId: String,
    private val link: PeerSocketLink,
    pairingKey: SecretKey,
    role: ConnectionRole,
    connectionNonce: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onMessage(peerId: String, body: MessageBody)
        /** Decide synchronously (e.g. from a stored "auto-accept" setting) whether to buffer this file. */
        fun onIncomingFileRequest(peerId: String, meta: FileMetaBody): IncomingDecision
        fun onProgress(progress: TransferProgress)
        fun onClosed(peerId: String)
    }

    private val clientToServerKey = Hkdf.deriveAesKey(pairingKey, "transformer/c2s/v1/$connectionNonce")
    private val serverToClientKey = Hkdf.deriveAesKey(pairingKey, "transformer/s2c/v1/$connectionNonce")
    private val sendKey = if (role == ConnectionRole.CLIENT) clientToServerKey else serverToClientKey
    private val recvKey = if (role == ConnectionRole.CLIENT) serverToClientKey else clientToServerKey

    /** Serializes seq-assignment with the actual socket write so concurrent
     * senders (a message and a file send racing on the same connection)
     * can never write their frames in a different order than the sequence
     * numbers they were assigned — the receiver requires strict ordering. */
    private val sendLock = Any()
    private val sendSeq = AtomicLong(0)
    private var recvSeq = 0L // only touched by this connection's single read-loop thread

    private val incoming = ConcurrentHashMap<String, IncomingFile>()
    @Volatile private var cancelled = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var running = false

    /** Runs the blocking read loop on the current thread until the socket
     * closes or a frame fails to decode/authenticate. Call from a background
     * thread/dispatcher. */
    fun runReadLoop() {
        running = true
        try {
            while (running) {
                val frame = try {
                    link.readFrame()
                } catch (e: Exception) {
                    null
                } ?: break
                val ok = try {
                    handleFrame(frame)
                    true
                } catch (e: Exception) {
                    // Malformed JSON, an unauthenticated/replayed/reordered
                    // frame (AEADBadTagException), or any other protocol
                    // violation: the stream can't be trusted from here on,
                    // so close rather than let the thread crash or silently
                    // desync the sequence counter.
                    false
                }
                if (!ok) break
            }
        } finally {
            running = false
            link.close() // a protocol violation breaks the loop above without the socket itself being closed yet
            listener.onClosed(peerId)
        }
    }

    fun stop() {
        running = false
        link.close()
    }

    private fun nextRecvAad(type: String): ByteArray {
        val aad = aadFor(type, recvSeq)
        recvSeq++
        return aad
    }

    private fun handleFrame(frame: Frame) {
        when (frame.type) {
            "msg" -> listener.onMessage(peerId, decryptJson(recvKey, frame.envelope, nextRecvAad("msg")))
            "file-meta" -> {
                val meta: FileMetaBody = decryptJson(recvKey, frame.envelope, nextRecvAad("file-meta"))
                if (meta.size < 0 || meta.size > Limits.MAX_FILE_BYTES || meta.totalChunks !in 1..Limits.MAX_TOTAL_CHUNKS) {
                    listener.onProgress(
                        TransferProgress(peerId, meta.transferId, Direction.RECEIVE, meta.name, meta.size, TransferStatus.FAILED, 0, reason = "Ungültige Dateigröße"),
                    )
                    return
                }
                when (listener.onIncomingFileRequest(peerId, meta)) {
                    IncomingDecision.ACCEPT -> {
                        incoming[meta.transferId] = IncomingFile(meta)
                        listener.onProgress(
                            TransferProgress(peerId, meta.transferId, Direction.RECEIVE, meta.name, meta.size, TransferStatus.ACTIVE, 0),
                        )
                    }
                    IncomingDecision.REJECT -> {
                        listener.onProgress(
                            TransferProgress(peerId, meta.transferId, Direction.RECEIVE, meta.name, meta.size, TransferStatus.FAILED, 0, reason = "Abgelehnt"),
                        )
                    }
                }
            }
            "file-chunk" -> {
                val chunk: FileChunkBody = decryptJson(recvKey, frame.envelope, nextRecvAad("file-chunk"))
                val state = incoming[chunk.transferId] ?: return
                state.addChunk(chunk.index, chunk.chunkB64)
                listener.onProgress(
                    TransferProgress(peerId, chunk.transferId, Direction.RECEIVE, state.meta.name, state.meta.size, TransferStatus.ACTIVE, state.percent()),
                )
                if (state.isComplete()) {
                    incoming.remove(chunk.transferId)
                    val bytes = state.assemble()
                    listener.onProgress(
                        TransferProgress(
                            peerId, chunk.transferId, Direction.RECEIVE, state.meta.name, state.meta.size,
                            TransferStatus.DONE, 100, bytes = bytes, mime = state.meta.mime,
                        ),
                    )
                    sendAck(chunk.transferId)
                }
            }
            "ack" -> {
                val ack: AckBody = decryptJson(recvKey, frame.envelope, nextRecvAad("ack"))
                listener.onProgress(
                    TransferProgress(peerId, ack.transferId, Direction.SEND, "", 0, TransferStatus.DONE, 100),
                )
            }
        }
    }

    fun sendMessage(body: MessageBody) {
        send("msg", body)
    }

    /** Cooperative cancel: stops queuing further chunks for this transfer on the next check. */
    fun cancelSend(transferId: String) {
        cancelled.add(transferId)
    }

    fun sendFile(transferId: String, name: String, mime: String, bytes: ByteArray, chunkBytes: Int = DEFAULT_CHUNK_BYTES) {
        val totalChunks = if (bytes.isEmpty()) 1 else ceil(bytes.size.toDouble() / chunkBytes).toInt()
        send("file-meta", FileMetaBody(transferId, name, bytes.size.toLong(), mime, totalChunks, System.currentTimeMillis()))
        listener.onProgress(TransferProgress(peerId, transferId, Direction.SEND, name, bytes.size.toLong(), TransferStatus.ACTIVE, 0))
        for (i in 0 until totalChunks) {
            if (cancelled.remove(transferId)) {
                listener.onProgress(
                    TransferProgress(peerId, transferId, Direction.SEND, name, bytes.size.toLong(), TransferStatus.FAILED, 0, reason = "Vom Nutzer abgebrochen"),
                )
                return
            }
            val start = i * chunkBytes
            val end = min(bytes.size, start + chunkBytes)
            val chunkB64 = Base64.getEncoder().encodeToString(bytes.copyOfRange(start, end))
            send("file-chunk", FileChunkBody(transferId, i, chunkB64))
            val pct = ((i + 1) * 100) / totalChunks
            listener.onProgress(TransferProgress(peerId, transferId, Direction.SEND, name, bytes.size.toLong(), TransferStatus.ACTIVE, pct))
        }
    }

    private fun sendAck(transferId: String) {
        send("ack", AckBody(transferId))
    }

    private inline fun <reified T> send(type: String, body: T) {
        synchronized(sendLock) {
            val seq = sendSeq.getAndIncrement()
            link.sendFrame(Frame(type, encryptJson(sendKey, body, aadFor(type, seq))))
        }
    }

    val isOpen: Boolean get() = link.isOpen
}

/** Binds a frame's type and sequence number into its GCM tag: type prevents
 * splicing a captured ciphertext into a different frame slot, seq (8 bytes,
 * big-endian) prevents replaying or reordering it. */
private fun aadFor(type: String, seq: Long): ByteArray {
    val seqBytes = ByteArray(8)
    for (i in 0 until 8) seqBytes[7 - i] = ((seq shr (i * 8)) and 0xFF).toByte()
    return type.toByteArray(Charsets.UTF_8) + seqBytes
}
