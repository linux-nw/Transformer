package app.transformer.core

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.SecretKey
import kotlin.concurrent.thread

/** Looks up the shared AES key for a pairing id — either an already-persisted
 * peer, or one currently mid-handshake (this device just generated the key
 * and is showing it in a QR code, waiting for the scan to connect back). */
fun interface PeerKeyResolver {
    fun resolveKey(pairingId: String): SecretKey?
}

/**
 * Always-listening TCP server for this device. Every accepted socket reads
 * one plaintext preamble line (a [HelloBody]) to learn which pairing id
 * it's for, resolves the matching key, and — if found — hands a live
 * [PeerConnection] to the caller and blocks that connection's thread
 * running its read loop until it closes.
 *
 * In the real app this runs inside a foreground [android.app.Service] so
 * it keeps accepting connections (fresh QR pairings, and NSD-triggered
 * reconnects from already-known devices) even while the app isn't in the
 * foreground.
 */
class PeerServer(
    port: Int,
    private val keyResolver: PeerKeyResolver,
    private val listener: PeerConnection.Listener,
    /** [remoteName] is whatever the connecting device sent in its hello —
     * the only place a first-time pairing learns the other side's display
     * name, since the accepting device never scanned their QR itself. */
    private val onAccepted: (connection: PeerConnection, remoteName: String) -> Unit,
) {
    private val serverSocket = ServerSocket(port)
    @Volatile private var running = false

    val boundPort: Int get() = serverSocket.localPort

    fun start() {
        running = true
        thread(name = "transformer-accept", isDaemon = true) {
            while (running) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    break
                }
                thread(name = "transformer-conn-${socket.port}", isDaemon = true) { handleAccepted(socket) }
            }
        }
    }

    private fun handleAccepted(socket: Socket) {
        val link = PeerSocketLink(socket)
        val helloLine = try {
            link.readLineOrNull()
        } catch (e: IOException) {
            null
        }
        if (helloLine == null) {
            link.close()
            return
        }
        val hello = try {
            PairingCodec.decodeHello(helloLine)
        } catch (e: Exception) {
            link.close()
            return
        }
        val key = keyResolver.resolveKey(hello.id)
        if (key == null) {
            link.close()
            return
        }
        val connection = PeerConnection(hello.id, link, key, listener)
        onAccepted(connection, hello.name)
        connection.runReadLoop() // blocks this dedicated thread until the socket closes
    }

    fun stop() {
        running = false
        runCatching { serverSocket.close() }
    }
}
