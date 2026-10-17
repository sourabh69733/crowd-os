package org.freegram.app.media

import org.freegram.app.protocol.VideoManifest

/** Saves a new video's manifest, pieces and file on this phone before the post that points to it is signed. */
object VideoPosts {
    fun storeOwn(videos: VideoStore, manifest: VideoManifest, bytes: ByteArray) {
        require(bytes.size == manifest.ref.size) { "Video does not match its manifest" }
        val hashes = setOf(manifest.event.id, manifest.ref.sha256) + manifest.pieces
        videos.protect(manifest).use {
            videos.putManifest(manifest, hashes, hashes, hashes)
            for (index in manifest.pieces.indices) {
                val start = index * VideoManifest.PIECE_BYTES
                videos.putPiece(manifest, index, bytes.copyOfRange(start, start + manifest.pieceSize(index)), hashes, hashes, hashes)
            }
            checkNotNull(videos.assemble(manifest, hashes, hashes, hashes)) { "Could not save the video" }
        }
    }
}
