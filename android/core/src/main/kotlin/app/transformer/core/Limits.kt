package app.transformer.core

/** Sanity caps on values a peer controls, so a malicious or buggy peer can't
 * make us allocate an unbounded amount of memory (e.g. a [FileMetaBody]
 * claiming billions of chunks). */
object Limits {
    const val MAX_FILE_BYTES: Long = 20L * 1024 * 1024 * 1024 // 20 GiB
    const val MAX_TOTAL_CHUNKS: Int = 500_000
}
