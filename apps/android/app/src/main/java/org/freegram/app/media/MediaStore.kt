package org.freegram.app.media

import java.io.File
import java.security.MessageDigest
import org.freegram.app.protocol.PhotoRef

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Photos on this phone, one file per SHA-256. Bytes are stored only after their hash matches,
 * so a file's name always proves its content. Total size is capped.
 */
class MediaStore(private val dir: File, private val maxTotalBytes: Long = MAX_BYTES) {
    init { dir.mkdirs() }

    private fun file(sha: String): File {
        require(sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' })
        return File(dir, "$sha.jpg")
    }

    @Synchronized fun has(sha: String): Boolean = file(sha).isFile

    @Synchronized fun read(sha: String): ByteArray? = file(sha).takeIf { it.isFile }?.readBytes()

    /** Deletes a photo unless a stored post still references it. */
    @Synchronized fun removeIfUnused(sha: String, keep: Set<String>) { if (sha !in keep) file(sha).delete() }

    fun path(sha: String): File? = file(sha).takeIf { it.isFile }

    /**
     * Stores [bytes] if they hash to [expectedSha]. [keep] lists hashes still referenced by stored posts;
     * unreferenced photos are removed first when space is needed, then the least recently stored.
     */
    @Synchronized fun put(expectedSha: String, bytes: ByteArray, keep: Set<String>): Boolean {
        require(bytes.size in 1..PhotoRef.MAX_BYTES) { "Photo too large" }
        require(sha256Hex(bytes) == expectedSha) { "Photo does not match its hash" }
        if (has(expectedSha)) return true
        makeRoom(bytes.size.toLong(), keep + expectedSha)
        val target = file(expectedSha)
        val temp = File(dir, "$expectedSha.tmp")
        temp.writeBytes(bytes)
        check(temp.renameTo(target))
        return true
    }

    @Synchronized fun totalBytes(): Long = files().sumOf { it.length() }

    private fun files() = dir.listFiles { f -> f.name.endsWith(".jpg") }.orEmpty().toList()

    private fun makeRoom(needed: Long, keep: Set<String>) {
        var total = totalBytes()
        val order = files().sortedWith(compareBy<File>({ it.nameWithoutExtension in keep }, { it.lastModified() }))
        for (candidate in order) {
            if (total + needed <= maxTotalBytes) break
            total -= candidate.length()
            candidate.delete()
        }
        check(total + needed <= maxTotalBytes) { "Photo storage is full" }
    }

    companion object {
        const val MAX_BYTES = 50L * 1024 * 1024
        @Volatile private var shared: MediaStore? = null

        /** One instance per process, shared by the screen and the nearby service. */
        fun shared(context: android.content.Context): MediaStore = shared ?: synchronized(this) {
            shared ?: MediaStore(File(context.applicationContext.filesDir, "media")).also { shared = it }
        }
    }
}
