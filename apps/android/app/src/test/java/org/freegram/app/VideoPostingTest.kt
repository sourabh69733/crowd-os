package org.freegram.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.freegram.app.media.OnlineVideos
import org.freegram.app.media.VideoPosts
import org.freegram.app.media.VideoServer
import org.freegram.app.media.VideoStore
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.VideoManifest
import org.freegram.app.protocol.VideoRef
import org.freegram.app.store.RoomStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoPostingTest {
    private val me = ByteArray(32).also { it[31] = 3 }
    private val other = ByteArray(32).also { it[31] = 4 }
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databaseName = "freegram-test-${UUID.randomUUID()}"
    private val dir = Files.createTempDirectory("video-posting").toFile()
    private val now = System.currentTimeMillis() / 1000
    private val bytes = ByteArray(VideoManifest.PIECE_BYTES * 2 + 100) { (it % 251).toByte() }

    @After fun tearDown() { context.deleteDatabase(databaseName); dir.deleteRecursively() }

    private fun pubkey(secret: ByteArray) = Nip01Protocol.signBulletin(secret, "x", 1).pubkey
    private fun videoPost(secret: ByteArray): Pair<VideoManifest, BulletinEvent> {
        val manifest = VideoManifest.sign(secret, bytes, 640, 360, 12, now)
        return manifest to Nip01Protocol.signBulletin(secret, "Road blocked", now, arrayOf(manifest.ref.toTag()))
    }

    private class FakeServer(val manifest: VideoManifest, val bytes: ByteArray) : VideoServer {
        var manifestUploads = 0; var videoUploads = 0; var pieceDownloads = 0
        override fun downloadManifest(ref: VideoRef) = manifest.takeIf { it.ref == ref }
        override fun downloadPiece(manifest: VideoManifest, index: Int): ByteArray {
            pieceDownloads++
            val start = index * VideoManifest.PIECE_BYTES
            return bytes.copyOfRange(start, start + manifest.pieceSize(index))
        }
        override fun uploadManifest(manifest: VideoManifest, sign: (Array<Array<String>>) -> BulletinEvent) = true.also { manifestUploads++ }
        override fun uploadVideo(manifest: VideoManifest, bytes: ByteArray, sign: (Array<Array<String>>) -> BulletinEvent): Boolean {
            assertArrayEquals(this.bytes, bytes); videoUploads++; return true
        }
    }

    private fun sync(store: RoomStore, videos: VideoStore, server: VideoServer, secret: ByteArray, unmetered: Boolean) = runBlocking {
        OnlineVideos(store, videos, server, pubkey(secret), { tags -> Nip01Protocol.signEvent(secret, 24242, "Upload", now, tags) }, unmetered).sync()
    }

    @Test fun ownVideoIsSavedWholeBeforePostingAndUploadsOnce() = runBlocking {
        val store = RoomStore(context, databaseName)
        val videos = VideoStore(dir)
        val (manifest, post) = videoPost(me)
        VideoPosts.storeOwn(videos, manifest, bytes)
        assertEquals(manifest.pieces.size, videos.savedPieces(manifest))
        assertNotNull(videos.complete(manifest))
        assertEquals(bytes.size.toLong(), videos.readyFile(manifest)!!.length())

        store.saveEvent(post, listOf("wss://one.example", "wss://two.example"))
        val server = FakeServer(manifest, bytes)
        // Own videos upload even on mobile data, once.
        assertFalse(sync(store, videos, server, me, unmetered = false))
        assertEquals(1, server.videoUploads)
        sync(store, videos, server, me, unmetered = false)
        assertEquals(1, server.videoUploads)
        store.close()
    }

    @Test fun othersVideosDownloadOnWifiOrWhenAskedNeverSilentlyOnMobileData() = runBlocking {
        val store = RoomStore(context, databaseName)
        val videos = VideoStore(dir)
        val (manifest, post) = videoPost(other)
        assertTrue(store.saveReceivedEvent(post))
        val server = FakeServer(manifest, bytes)

        sync(store, videos, server, me, unmetered = false)
        assertEquals(0, server.pieceDownloads)
        assertNull(videos.manifest(manifest.event.id))

        videos.request(manifest.ref.sha256)
        assertFalse(sync(store, videos, server, me, unmetered = false))
        assertEquals(manifest.pieces.size, server.pieceDownloads)
        assertArrayEquals(bytes, videos.complete(manifest)!!.readBytes())
        // Carried videos aren't uploaded on mobile data.
        assertEquals(0, server.videoUploads)
        store.close()
    }

    @Test fun downloadsStopAtThePieceBudgetAndResume() = runBlocking {
        val store = RoomStore(context, databaseName)
        val videos = VideoStore(dir)
        val (manifest, post) = videoPost(other)
        store.saveReceivedEvent(post)
        val server = FakeServer(manifest, bytes)
        val signer: (Array<Array<String>>) -> BulletinEvent = { tags -> Nip01Protocol.signEvent(me, 24242, "Upload", now, tags) }
        assertTrue(OnlineVideos(store, videos, server, pubkey(me), signer, unmetered = true).sync(maxPieces = 1))
        assertEquals(1, videos.savedPieces(manifest))
        assertFalse(OnlineVideos(store, videos, server, pubkey(me), signer, unmetered = true).sync())
        assertNotNull(videos.complete(manifest))
        store.close()
    }
}
