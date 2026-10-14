package org.freegram.app.media

import org.freegram.app.protocol.VideoRef
import org.freegram.app.store.RoomStore

data class VideoRetention(val keep: Set<String>, val own: Set<String>, val protected: Set<String>) {
    companion object {
        suspend fun load(store: RoomStore, videos: VideoStore, pubkey: String): VideoRetention {
            val visible = store.savedEvents().mapNotNull(VideoRef::of)
            val owners = store.videoOwners(pubkey)
            fun files(ref: VideoRef): Set<String> = setOf(ref.manifestId, ref.sha256) + videos.manifest(ref.manifestId)?.pieces.orEmpty()
            val keep = (visible + owners.map { it.first }).flatMap { files(it) }.toSet()
            val own = owners.flatMap { files(it.first) }.toSet()
            val protected = owners.filter { it.second || !videos.uploaded(it.first.sha256) }.flatMap { files(it.first) }.toSet()
            return VideoRetention(keep, own, protected)
        }
    }
}
