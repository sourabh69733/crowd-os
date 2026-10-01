package org.freegram.app.protocol

/**
 * A photo attached to a post by content hash, as a NIP-92 `imeta` tag without a URL:
 * `["imeta", "x <sha256>", "m image/jpeg", "size <bytes>", "dim <w>x<h>"]`.
 * Any source (nearby phone, later a Blossom server) can supply the bytes; the hash proves they match.
 */
data class PhotoRef(val sha256: String, val size: Int, val width: Int, val height: Int) {
    fun toTag(): Array<String> = arrayOf("imeta", "x $sha256", "m $MIME", "size $size", "dim ${width}x$height")

    companion object {
        const val MIME = "image/jpeg"
        const val MAX_BYTES = 1024 * 1024
        const val MAX_DIMENSION = 1600

        /** The post's single photo, or null. Posts with malformed or several photo tags have none. */
        fun of(event: BulletinEvent): PhotoRef? {
            val tags = event.tags.filter { it.firstOrNull() == "imeta" }
            if (tags.size != 1) return null
            val fields = tags[0].drop(1).mapNotNull { entry ->
                entry.indexOf(' ').takeIf { it > 0 }?.let { entry.substring(0, it) to entry.substring(it + 1) }
            }.toMap()
            val sha = fields["x"]?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return null
            if (fields["m"] != MIME) return null
            val size = fields["size"]?.toIntOrNull()?.takeIf { it in 1..MAX_BYTES } ?: return null
            val dim = fields["dim"]?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 } ?: return null
            if (dim.any { it !in 1..MAX_DIMENSION }) return null
            return PhotoRef(sha, size, dim[0], dim[1])
        }
    }
}
