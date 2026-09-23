package app.transformer.core

import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import kotlin.math.ceil
import kotlin.math.min

const val DEFAULT_CHUNK_BYTES = 48 * 1024

/**
 * One live, authenticated, encrypted connection to a paired device. Wraps a
 * [PeerSocketLink] plus the shared AES key and speaks the same four-frame
 * protocol (msg / file-meta / file-chunk / ack) as the web prototype.
 *
 * The read loop runs on the caller-supplied [readDispatcher] via [start];
 * callers must call that before frames are processed. A [PeerConnection] is
 * one-shot: once the socket drops it must be discarded and re-created on
 * reconnect (via [PeerServer] accepting a new socket, or [PeerClient]
 * dialing out again).
 */
class PeerConnection(
    val peerId: String,
    private val link: PeerSocketLink,
    private val key: SecretKey,
    private val listener: Listener,
) {
    interface Listener {
        fun onMessage(peerId: String, body: MessageBody)
        /** Decide synchronously (e.g. from a stored "auto-accept" setting) whether to buffer this file. */
        fun onIncomingFileRequest(peerId: String, meta: FileMetaBody): IncomingDecision
        fun onProgress(progress: TransferProgress)
        fun onClosed(peerId: String)
    }

    private val incoming = ConcurrentHashMap<String, IncomingFile>()
    @Volatile private var cancelled = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var running = false

    /** Runs the blocking read loop on the current thread until the socket closes. Call from a background thread/dispatcher. */
    fun runReadLoop() {
        running = true
        try {
            while (running) {
                val frame = try {
                    link.readFrame()
                } catch (e: IOException) {
                    null
                } ?: break
                handleFrame(frame)
            }
        } finally {
            running = false
            listener.onClosed(peerId)
        }
    }

    fun stop() {
        running = false
        link.close()
    }

    private fun handleFrame(frame: Frame) {
        when (frame.type) {
            "msg" -> listener.onMessage(peerId, decryptJson(key, frame.envelope))
            "file-meta" -> {
                val meta: FileMetaBody = decryptJson(key, frame.envelope)
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
                val chunk: FileChunkBody = decryptJson(key, frame.envelope)
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
                val ack: AckBody = decryptJson(key, frame.envelope)
                listener.onProgress(
                    TransferProgress(peerId, ack.transferId, Direction.SEND, "", 0, TransferStatus.DONE, 100),
                )
            }
        }
    }

    fun sendMessage(body: MessageBody) {
        link.sendFrame(Frame("msg", encryptJson(key, body)))
    }

    /** Cooperative cancel: stops queuing further chunks for this transfer on the next check. */
    fun cancelSend(transferId: String) {
        cancelled.add(transferId)
    }

    fun sendFile(transferId: String, name: String, mime: String, bytes: ByteArray, chunkBytes: Int = DEFAULT_CHUNK_BYTES) {
        val totalChunks = if (bytes.isEmpty()) 1 else ceil(bytes.size.toDouble() / chunkBytes).toInt()
        link.sendFrame(
            Frame("file-meta", encryptJson(key, FileMetaBody(transferId, name, bytes.size.toLong(), mime, totalChunks, System.currentTimeMillis()))),
        )
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
            link.sendFrame(Frame("file-chunk", encryptJson(key, FileChunkBody(transferId, i, chunkB64))))
            val pct = ((i + 1) * 100) / totalChunks
            listener.onProgress(TransferProgress(peerId, transferId, Direction.SEND, name, bytes.size.toLong(), TransferStatus.ACTIVE, pct))
        }
    }

    private fun sendAck(transferId: String) {
        link.sendFrame(Frame("ack", encryptJson(key, AckBody(transferId))))
    }

    val isOpen: Boolean get() = link.isOpen
}
