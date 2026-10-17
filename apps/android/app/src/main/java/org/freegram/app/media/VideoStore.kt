package org.freegram.app.media

import java.io.File
import java.security.MessageDigest
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.VideoManifest

/** Checked pieces remain useful after interruption, even before a video is complete. */
class VideoStore(private val dir: File, private var maxBytes: Long = DEFAULT_BYTES) {
    init { require(maxBytes > 0); check(dir.mkdirs() || dir.isDirectory); dir.listFiles { f -> f.extension == "tmp" }.orEmpty().forEach { check(it.delete()) } }

    private val active = mutableMapOf<String, Int>()
    @Synchronized fun protect(manifest: VideoManifest): AutoCloseable {
        val hashes = setOf(manifest.event.id, manifest.ref.sha256) + manifest.pieces
        hashes.forEach { active[it] = (active[it] ?: 0) + 1 }
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return AutoCloseable { synchronized(this) { if (closed.compareAndSet(false, true)) hashes.forEach { hash ->
            val count = active.getValue(hash) - 1
            if (count == 0) active.remove(hash) else active[hash] = count
        } } }
    }
    @Synchronized fun uploaded(hash: String): Boolean = file(hash, "uploaded").isFile
    @Synchronized fun markUploaded(hash: String) { file(hash, "uploaded").writeText("") }
    @Synchronized fun wasComplete(hash: String): Boolean = file(hash, "seen").isFile
    @Synchronized fun requested(hash: String): Boolean = file(hash, "wanted").isFile
    @Synchronized fun request(hash: String) { file(hash, "wanted").writeText("") }
    @Synchronized fun clearRequest(hash: String) { file(hash, "wanted").delete() }

    @Synchronized fun setLimit(bytes: Long) { require(bytes in DEFAULT_BYTES..MAX_BYTES); maxBytes = bytes }
    @Synchronized fun manifest(id: String): VideoManifest? {
        val file = file(id, "manifest")
        if (!file.isFile || file.length() > VideoManifest.MAX_BYTES) return null
        return runCatching { VideoManifest.of(Nip01Protocol.fromJson(file.readText(), VideoManifest.MAX_BYTES)) }
            .getOrNull()?.takeIf { it.event.id == id }
    }
    @Synchronized fun putManifest(manifest: VideoManifest, keep: Set<String> = emptySet(), own: Set<String> = emptySet(), protected: Set<String> = emptySet()) {
        write(manifest.event.id, "manifest", Nip01Protocol.toJson(manifest.event).toByteArray(Charsets.UTF_8), keep, own, protected)
    }
    @Synchronized fun piece(hash: String): File? = file(hash, "piece").takeIf {
        it.isFile && it.length() in 1..VideoManifest.PIECE_BYTES && sha256Hex(it.readBytes()) == hash
    }
    @Synchronized fun missing(manifest: VideoManifest): List<Int> = manifest.pieces.indices.filter {
        piece(manifest.pieces[it])?.length() != manifest.pieceSize(it).toLong()
    }
    @Synchronized fun putPiece(manifest: VideoManifest, index: Int, bytes: ByteArray, keep: Set<String> = emptySet(), own: Set<String> = emptySet(), protected: Set<String> = emptySet()) {
        require(bytes.size == manifest.pieceSize(index)) { "Wrong piece size" }
        putChecked(manifest.pieces[index], bytes, keep, own, protected)
    }
    @Synchronized fun putChecked(hash: String, bytes: ByteArray, keep: Set<String> = emptySet(), own: Set<String> = emptySet(), protected: Set<String> = emptySet()) {
        require(bytes.size in 1..VideoManifest.PIECE_BYTES && sha256Hex(bytes) == hash) { "Wrong piece fingerprint" }
        write(hash, "piece", bytes, keep, own, protected)
    }
    @Synchronized fun complete(manifest: VideoManifest): File? = file(manifest.ref.sha256, "mp4").takeIf {
        it.isFile && it.length() == manifest.ref.size.toLong() && digest(it) == manifest.ref.sha256
    }
    @Synchronized fun assemble(manifest: VideoManifest, keep: Set<String> = emptySet(), own: Set<String> = emptySet(), protected: Set<String> = emptySet()): File? {
        complete(manifest)?.let { return it }
        if (missing(manifest).isNotEmpty()) return null
        val hash = manifest.ref.sha256
        makeRoom(manifest.ref.size.toLong(), keep + manifest.pieces + hash, own, protected + manifest.pieces + hash)
        val target = file(hash, "mp4")
        val temp = file(hash, "tmp")
        try {
            temp.outputStream().use { output -> manifest.pieces.forEach { piece(it)!!.inputStream().use { source -> source.copyTo(output) } } }
            require(temp.length() == manifest.ref.size.toLong() && digest(temp) == hash) { "Video does not match its fingerprint" }
            check(temp.renameTo(target)) { "Could not save video" }
            file(hash, "seen").writeText("")
            clearRequest(hash)
            return target
        } finally { temp.delete() }
    }
    /** Pieces already saved, by size only. Pieces are checked when written, so this skips re-hashing for screens. */
    @Synchronized fun savedPieces(manifest: VideoManifest): Int =
        manifest.pieces.indices.count { file(manifest.pieces[it], "piece").let { f -> f.isFile && f.length() == manifest.pieceSize(it).toLong() } }
    /** The assembled video, by size only (it was checked when assembled). For screens; use [complete] before sending. */
    @Synchronized fun readyFile(manifest: VideoManifest): File? =
        file(manifest.ref.sha256, "mp4").takeIf { it.isFile && it.length() == manifest.ref.size.toLong() }
    @Synchronized fun totalBytes(): Long = files().sumOf { it.length() }

    private fun write(hash: String, extension: String, bytes: ByteArray, keep: Set<String>, own: Set<String>, protected: Set<String>) {
        val target = file(hash, extension)
        if (target.isFile && target.readBytes().contentEquals(bytes)) return
        makeRoom(bytes.size.toLong(), keep + hash, own, protected + hash)
        val temp = file(hash, "tmp")
        try { temp.writeBytes(bytes); check(temp.renameTo(target)) { "Could not save video data" } }
        finally { temp.delete() }
    }
    private fun makeRoom(added: Long, keep: Set<String>, own: Set<String>, protected: Set<String>) {
        var total = totalBytes()
        val victims = files().filter { it.name.substringBefore('.') !in protected && it.name.substringBefore('.') !in active }.sortedWith(
            compareBy<File> { val hash = it.name.substringBefore('.'); when { hash !in keep -> 0; hash in own -> 2; else -> 1 } }.thenBy { it.lastModified() })
        for (victim in victims) {
            if (total + added <= maxBytes) break
            val size = victim.length()
            check(victim.delete()) { "Could not remove old video data" }
            total -= size
        }
        check(total + added <= maxBytes) { "Video storage is full; unsent videos are kept" }
    }
    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.extension in setOf("piece", "manifest", "mp4") }.orEmpty().toList()
    private fun file(hash: String, extension: String): File { require(hash.matches(Regex("[0-9a-f]{64}"))); return File(dir, "$hash.$extension") }
    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    companion object {
        const val DEFAULT_BYTES = 200L * 1024 * 1024
        const val MAX_BYTES = 1024L * 1024 * 1024
        @Volatile private var shared: VideoStore? = null
        fun shared(context: android.content.Context): VideoStore = shared ?: synchronized(this) {
            shared ?: VideoStore(File(context.applicationContext.filesDir, "videos")).also { shared = it }
        }
    }
}
