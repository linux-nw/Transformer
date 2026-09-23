package app.transformer.core

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One line of JSON per frame (NDJSON) over a raw TCP socket. Safe because a
 * frame's JSON never contains a literal newline — file bytes are Base64
 * inside [FileChunkBody], never raw binary on the wire.
 */
class PeerSocketLink(private val socket: Socket) : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writeLock = Any()

    val remoteHost: String get() = socket.inetAddress?.hostAddress ?: "?"

    fun sendLine(line: String) {
        synchronized(writeLock) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    fun sendFrame(frame: Frame) = sendLine(json.encodeToString(frame))

    /** Blocks until a line arrives; returns null when the peer closed the connection. */
    @Throws(IOException::class)
    fun readLineOrNull(): String? = reader.readLine()

    fun readFrame(): Frame? = readLineOrNull()?.let { json.decodeFromString(it) }

    override fun close() {
        runCatching { socket.close() }
    }

    val isOpen: Boolean get() = socket.isConnected && !socket.isClosed
}
