package org.freegram.app.media

import kotlinx.coroutines.CancellationException
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.VideoManifest
import org.freegram.app.protocol.VideoRef
import org.freegram.app.store.RoomStore

/** The media server calls [OnlineVideos] needs; [BlossomClient] in the app, a fake in tests. */
interface VideoServer {
    fun downloadManifest(ref: VideoRef): VideoManifest?
    fun downloadPiece(manifest: VideoManifest, index: Int): ByteArray?
    fun uploadManifest(manifest: VideoManifest, sign: (Array<Array<String>>) -> BulletinEvent): Boolean
    fun uploadVideo(manifest: VideoManifest, bytes: ByteArray, sign: (Array<Array<String>>) -> BulletinEvent): Boolean
}

/**
 * One bounded pass over video posts on this phone. Runs on IO. Uploads this phone's own complete videos on any
 * network; uploads carried videos and downloads missing ones only on Wi-Fi ([unmetered]) or when the user asked
 * for that video, so mobile data isn't spent without consent. Returns true if work remains.
 */
class OnlineVideos(
    private val store: RoomStore,
    private val videos: VideoStore,
    private val server: VideoServer,
    private val pubkey: String,
    private val sign: (Array<Array<String>>) -> BulletinEvent,
    private val unmetered: Boolean,
) {
    suspend fun sync(maxPieces: Int = MAX_PIECES_PER_PASS): Boolean {
        val posts = store.savedEvents().mapNotNull { post -> VideoRef.of(post)?.let { post to it } }.distinctBy { it.second.sha256 }
        var remaining = false
        var budget = maxPieces
        for ((post, ref) in posts) {
            val mine = post.pubkey == pubkey
            val wanted = unmetered || videos.requested(ref.sha256)
            try {
                val manifest = videos.manifest(ref.manifestId) ?: if (!wanted && !mine) null else
                    server.downloadManifest(ref)?.takeIf { it.matches(post) }?.also { manifest ->
                        val retention = VideoRetention.load(store, videos, pubkey)
                        videos.putManifest(manifest, retention.keep, retention.own, retention.protected)
                    }
                if (manifest == null) { if (wanted || mine) remaining = true; continue }
                val complete = videos.complete(manifest)
                if (complete != null) {
                    if (!videos.uploaded(ref.sha256) && (mine || unmetered)) {
                        val sent = server.uploadManifest(manifest, sign) && server.uploadVideo(manifest, complete.readBytes(), sign)
                        if (sent) videos.markUploaded(ref.sha256) else remaining = true
                    }
                    continue
                }
                if (!wanted) continue
                for (index in videos.missing(manifest)) {
                    if (budget-- <= 0) return true
                    val bytes = server.downloadPiece(manifest, index) ?: run { remaining = true; null } ?: break
                    val retention = VideoRetention.load(store, videos, pubkey)
                    videos.putPiece(manifest, index, bytes, retention.keep, retention.own, retention.protected)
                }
                val retention = VideoRetention.load(store, videos, pubkey)
                if (videos.assemble(manifest, retention.keep, retention.own, retention.protected) != null) PhotoChanges.changed()
                else remaining = true
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { remaining = true }
        }
        return remaining
    }

    companion object {
        const val MAX_PIECES_PER_PASS = 40

        /** The app's instance: this phone's key signs uploads; Wi-Fi decides automatic downloads. */
        fun forApp(context: android.content.Context, store: RoomStore): OnlineVideos {
            val identity = org.freegram.app.identity.ProtectedIdentity(context)
            val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            val unmetered = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
                ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
            return OnlineVideos(store, VideoStore.shared(context), BlossomClient(), identity.publicKeyHex(), { tags ->
                identity.withSecret { org.freegram.app.protocol.Nip01Protocol.signEvent(it, 24242, "Upload video", System.currentTimeMillis() / 1000, tags) }
            }, unmetered)
        }
    }
}
