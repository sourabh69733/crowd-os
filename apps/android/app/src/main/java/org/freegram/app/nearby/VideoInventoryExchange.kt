package org.freegram.app.nearby

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.freegram.app.media.VideoRetention
import org.freegram.app.media.VideoStore
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.VideoManifest
import org.freegram.app.protocol.VideoRef
import org.freegram.app.store.RoomStore

data class VideoPlan(val send: List<VideoPieceId>, val receive: List<VideoPieceId>)

class VideoInventoryExchange(private val store: RoomStore, private val videos: VideoStore, private val pubkey: String) {
    private suspend fun <T> io(action: () -> T): T = withContext(Dispatchers.IO) { action() }
    suspend fun run(offers: List<BulletinEvent>, send: suspend (NearbyFrame) -> Unit, next: suspend () -> NearbyFrame): VideoPlan {
        val offered = offers.mapNotNull { post -> VideoRef.of(post)?.let { ref ->
            io { videos.manifest(ref.manifestId) }?.takeIf { it.matches(post) }?.let { ref.manifestId to it }
        } }.distinctBy { it.first }.take(NearbyFrames.MAX_VIDEO_MANIFESTS).toMap()
        send(NearbyFrame.VideoHave(offered.keys.toList()))
        val theirHave = next() as? NearbyFrame.VideoHave ?: throw IllegalArgumentException("Expected video inventory")
        val posts = store.savedEvents().mapNotNull { post -> VideoRef.of(post)?.let { it.manifestId to post } }.toMap()
        val wanted = theirHave.ids.filter { id -> posts[id]?.let { post -> io { videos.manifest(id) }?.matches(post) != true } == true }.toMutableSet()
        send(NearbyFrame.VideoWant(wanted.toList()))
        val theirWant = next() as? NearbyFrame.VideoWant ?: throw IllegalArgumentException("Expected video request")
        require(offered.keys.containsAll(theirWant.ids)) { "Unrequested video manifest" }
        for (id in theirWant.ids) {
            val manifest = offered.getValue(id)
            if (store.savedEvents().any(manifest::matches)) send(NearbyFrame.Manifest(manifest.event))
        }
        send(NearbyFrame.ManifestsSent)
        while (true) {
            val frame = next()
            if (frame == NearbyFrame.ManifestsSent) break
            require(frame is NearbyFrame.Manifest) { "Expected signed video manifest" }
            val manifest = requireNotNull(VideoManifest.of(frame.event))
            require(wanted.remove(manifest.event.id) && posts[manifest.event.id]?.let(manifest::matches) == true) { "Video manifest does not match the signed post" }
            if (store.savedEvents().any(manifest::matches)) {
                val retention = VideoRetention.load(store, videos, pubkey)
                try { io { videos.putManifest(manifest, retention.keep, retention.own, retention.protected) } }
                catch (_: IllegalStateException) { }
            }
        }
        val myPieces = offered.mapNotNull { (id, manifest) ->
            if (!store.savedEvents().any(manifest::matches)) null else {
                val missing = io { videos.missing(manifest) }.toSet()
                VideoPieceInventory(id, manifest.pieces.indices.filter { it !in missing })
            }
        }
        send(NearbyFrame.PieceHave(myPieces))
        val theirs = next() as? NearbyFrame.PieceHave ?: throw IllegalArgumentException("Expected piece inventory")
        require(theirHave.ids.containsAll(theirs.videos.map { it.manifestId })) { "Unannounced video pieces" }
        val receive = mutableListOf<VideoPieceId>()
        val hashes = mutableSetOf<String>()
        for (inventory in theirs.videos) {
            val manifest = io { videos.manifest(inventory.manifestId) } ?: continue
            if (!store.savedEvents().any(manifest::matches)) continue
            if (io { videos.wasComplete(manifest.ref.sha256) && !videos.requested(manifest.ref.sha256) }) continue
            val missing = io { videos.missing(manifest) }.toSet()
            inventory.indices.filter { it in missing }.forEach { index ->
                if (receive.size < NearbyFrames.MAX_VIDEO_PIECES && hashes.add(manifest.pieces[index])) receive.add(VideoPieceId(inventory.manifestId, index))
            }
        }
        send(NearbyFrame.PieceWant(receive))
        val theirPieces = next() as? NearbyFrame.PieceWant ?: throw IllegalArgumentException("Expected piece request")
        require(theirPieces.pieces.all { request -> myPieces.any { it.manifestId == request.manifestId && request.index in it.indices } }) { "Requested a piece that was not offered" }
        val distinctSend = theirPieces.pieces.distinctBy { request -> offered.getValue(request.manifestId).pieces[request.index] }
        return VideoPlan(distinctSend, receive)
    }
}
