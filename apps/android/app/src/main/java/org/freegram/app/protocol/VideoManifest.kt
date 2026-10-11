package org.freegram.app.protocol

import org.freegram.app.media.sha256Hex

/** The manifest ID and video fingerprint are signed into the post before it is shared. */
data class VideoRef(val sha256: String, val size: Int, val width: Int, val height: Int,
    val durationSeconds: Int, val manifestId: String) {
    fun toTag(): Array<String> = arrayOf("imeta", "x $sha256", "m video/mp4", "size $size",
        "dim ${width}x$height", "duration $durationSeconds", "manifest $manifestId")

    companion object {
        fun of(post: BulletinEvent): VideoRef? {
            val tags = post.tags.filter { it.firstOrNull() == "imeta" }
            if (tags.size != 1) return null
            val fields = tags.single().drop(1).mapNotNull {
                val split = it.indexOf(' ')
                if (split < 1) null else it.substring(0, split) to it.substring(split + 1)
            }
            if (fields.map { it.first }.distinct().size != fields.size) return null
            val values = fields.toMap()
            if (values["m"] != "video/mp4") return null
            return videoRef(values["x"], values["size"], values["dim"], values["duration"], values["manifest"])
        }
    }
}

class VideoManifest private constructor(val event: BulletinEvent, val ref: VideoRef, val pieces: List<String>) {
    fun pieceSize(index: Int): Int {
        require(index in pieces.indices)
        return minOf(PIECE_BYTES, ref.size - index * PIECE_BYTES)
    }
    fun matches(post: BulletinEvent): Boolean = Nip01Protocol.verifyBulletin(post) &&
        post.pubkey == event.pubkey && VideoRef.of(post) == ref

    companion object {
        const val KIND = 1063
        const val PIECE_BYTES = 256 * 1024
        const val MAX_VIDEO_BYTES = 15 * 1024 * 1024
        const val MAX_SOURCE_BYTES = 40 * 1024 * 1024
        const val MAX_BYTES = 8192
        const val MAX_DURATION_SECONDS = 300

        fun sign(secret: ByteArray, bytes: ByteArray, width: Int, height: Int, durationSeconds: Int, at: Long): VideoManifest {
            require(bytes.size in 1..MAX_VIDEO_BYTES)
            val pieces = (bytes.indices step PIECE_BYTES).map { sha256Hex(bytes.copyOfRange(it, minOf(it + PIECE_BYTES, bytes.size))) }
            val tags = arrayOf(arrayOf("x", sha256Hex(bytes)), arrayOf("m", "video/mp4"), arrayOf("size", bytes.size.toString()),
                arrayOf("dim", "${width}x$height"), arrayOf("duration", durationSeconds.toString()),
                arrayOf("freegram-piece", PIECE_BYTES.toString()), arrayOf("freegram-pieces", pieces.joinToString(",")))
            return requireNotNull(of(Nip01Protocol.signEvent(secret, KIND, "", at, tags))) { "Invalid video manifest" }
        }

        fun of(event: BulletinEvent): VideoManifest? {
            if (event.kind != KIND || !Nip01Protocol.verifySigned(event, MAX_BYTES)) return null
            val tags = event.tags
            if (tags.size != 7 || tags.any { it.size != 2 } || tags.map { it[0] }.distinct().size != tags.size) return null
            val values = tags.associate { it[0] to it[1] }
            if (values["m"] != "video/mp4" || values["freegram-piece"] != PIECE_BYTES.toString()) return null
            val ref = videoRef(values["x"], values["size"], values["dim"], values["duration"], event.id) ?: return null
            val pieces = values["freegram-pieces"]?.split(',') ?: return null
            if (pieces.size != (ref.size + PIECE_BYTES - 1) / PIECE_BYTES || pieces.any { !videoHash(it) }) return null
            return VideoManifest(event, ref, pieces)
        }
    }
}

private fun videoRef(hash: String?, size: String?, dimensions: String?, duration: String?, manifest: String?): VideoRef? {
    if (!videoHash(hash) || !videoHash(manifest)) return null
    val length = size?.toIntOrNull()?.takeIf { it in 1..VideoManifest.MAX_VIDEO_BYTES } ?: return null
    val dims = dimensions?.split('x')?.takeIf { it.size == 2 }?.map { it.toIntOrNull() ?: return null } ?: return null
    if (dims.any { it !in 1..1280 } || dims.min() > 720) return null
    val seconds = duration?.toIntOrNull()?.takeIf { it in 1..VideoManifest.MAX_DURATION_SECONDS } ?: return null
    return VideoRef(hash!!, length, dims[0], dims[1], seconds, manifest!!)
}
private fun videoHash(value: String?): Boolean = value != null && value.matches(Regex("[0-9a-f]{64}"))
