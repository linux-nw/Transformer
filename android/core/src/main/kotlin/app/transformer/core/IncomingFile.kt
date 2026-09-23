package app.transformer.core

import java.io.ByteArrayOutputStream
import java.util.Base64

/** Buffers chunks for one in-flight incoming file transfer. */
internal class IncomingFile(val meta: FileMetaBody) {
    private val chunks = arrayOfNulls<ByteArray>(meta.totalChunks)
    private var received = 0

    fun addChunk(index: Int, chunkB64: String) {
        if (index !in chunks.indices) return
        if (chunks[index] == null) {
            chunks[index] = Base64.getDecoder().decode(chunkB64)
            received++
        }
    }

    fun percent(): Int = if (meta.totalChunks == 0) 100 else (received * 100) / meta.totalChunks

    fun isComplete(): Boolean = received == meta.totalChunks

    fun assemble(): ByteArray {
        val out = ByteArrayOutputStream(meta.size.toInt().coerceAtLeast(0))
        for (chunk in chunks) out.write(chunk ?: ByteArray(0))
        return out.toByteArray()
    }
}
