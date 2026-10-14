package org.freegram.app

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.freegram.app.media.VideoStore
import org.freegram.app.nearby.*
import org.freegram.app.protocol.*
import org.freegram.app.store.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FastVideoExchangeTest {
    private class Link(private val incoming: Channel<String>, private val outgoing: Channel<String>) : FilePeerLink {
        lateinit var peer: Link
        var corrupt = false
        private val prepared = mutableMapOf<Long, File>()
        private val expected = mutableMapOf<Long, CompletableDeferred<ByteArray>>()
        override suspend fun send(frame: String) { outgoing.send(frame) }
        override suspend fun receive() = incoming.receive()
        override fun close() { }
        override fun prepareFile(file: File): Long = ids.incrementAndGet().also { prepared[it] = file }
        override fun expectFile(id: Long, size: Int) { expected[id] = CompletableDeferred() }
        override suspend fun sendFile(id: Long) {
            val bytes = prepared.getValue(id).readBytes()
            if (corrupt) bytes[0] = (bytes[0].toInt() xor 1).toByte()
            peer.expected.getValue(id).complete(bytes)
        }
        override suspend fun receiveFile(id: Long): ByteArray = expected.getValue(id).await()
        companion object { val ids = AtomicLong() }
    }
    private fun links(): Pair<Link, Link> {
        val a = Channel<String>(64); val b = Channel<String>(64)
        val first = Link(a, b); val second = Link(b, a)
        first.peer = second; second.peer = first
        return first to second
    }
    @Test fun videoResumesAcrossTwoPeersAndCorruptPiecesAreNotSaved() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication() as Context
        val root = File(context.cacheDir, UUID.randomUUID().toString()).also { it.mkdirs() }
        val stores = (0..2).map { RoomStore(context, "video-${UUID.randomUUID()}").also { store -> store.initialize() } }
        try {
            val bytes = ByteArray(VideoManifest.PIECE_BYTES * 2 + 10) { (it % 251).toByte() }
            val manifest = VideoManifest.sign(ByteArray(32) { 3 }, bytes, 640, 480, 10, 100)
            val event = Nip01Protocol.signBulletin(ByteArray(32) { 3 }, "video", 100, arrayOf(manifest.ref.toTag()))
            stores.forEach { it.saveReceivedEvent(event) }
            val media = (0..2).map { VideoStore(File(root, it.toString())).also { videos -> videos.putManifest(manifest) } }
            media[0].putPiece(manifest, 0, bytes.copyOfRange(0, VideoManifest.PIECE_BYTES))
            media[0].putPiece(manifest, 2, bytes.copyOfRange(VideoManifest.PIECE_BYTES * 2, bytes.size))
            media[1].putPiece(manifest, 1, bytes.copyOfRange(VideoManifest.PIECE_BYTES, VideoManifest.PIECE_BYTES * 2))
            suspend fun transfer(source: Int, index: Int, corrupt: Boolean = false) = coroutineScope {
                val (left, right) = links(); left.corrupt = corrupt
                val request = VideoPieceId(manifest.event.id, index)
                val sender = async { FastVideoExchange(stores[source], media[source], "f".repeat(64)).run(left, listOf(request), emptyList(), true) }
                val receiver = async { FastVideoExchange(stores[2], media[2], "f".repeat(64)).run(right, emptyList(), listOf(request), false) }
                sender.await() to receiver.await()
            }
            assertEquals(0, transfer(0, 0, true).second.piecesReceived)
            assertEquals(listOf(0,1,2), media[2].missing(manifest))
            assertEquals(1, transfer(0, 0).second.piecesReceived)
            assertEquals(listOf(1,2), VideoStore(File(root, "2")).missing(manifest))
            assertEquals(1, transfer(1, 1).second.piecesReceived)
            assertEquals(1, transfer(0, 2).second.piecesReceived)
            assertArrayEquals(bytes, media[2].complete(manifest)!!.readBytes())
            stores[2].setAuthorState(event.pubkey, AuthorState.BLOCKED)
            assertEquals(0, transfer(0, 0).second.piecesReceived)
        } finally { stores.forEach { it.close() }; root.deleteRecursively() }
    }
    @Test fun nearbyNegotiatesManifestAfterTextAndResumesOnlyMissingPieces() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication() as Context
        val root = File(context.cacheDir, UUID.randomUUID().toString()).also { it.mkdirs() }
        val source = RoomStore(context, "video-${UUID.randomUUID()}").also { it.initialize() }
        val receiver = RoomStore(context, "video-${UUID.randomUUID()}").also { it.initialize() }
        try {
            val bytes = ByteArray(VideoManifest.PIECE_BYTES * 2 + 10) { (it % 251).toByte() }
            val manifest = VideoManifest.sign(ByteArray(32) { 3 }, bytes, 640, 480, 10, 100)
            val event = Nip01Protocol.signBulletin(ByteArray(32) { 3 }, "video", 100, arrayOf(manifest.ref.toTag()))
            source.saveReceivedEvent(event)
            val leftMedia = VideoStore(File(root, "source")); val rightMedia = VideoStore(File(root, "receiver"))
            leftMedia.putManifest(manifest)
            leftMedia.putPiece(manifest, 0, bytes.copyOfRange(0, VideoManifest.PIECE_BYTES))
            leftMedia.putPiece(manifest, 2, bytes.copyOfRange(VideoManifest.PIECE_BYTES * 2, bytes.size))
            suspend fun meeting() = coroutineScope {
                val (clusterLeft, clusterRight) = links(); val (fileLeft, fileRight) = links()
                fun handoff(link: FilePeerLink, first: Boolean) = object : FastMediaHandoff {
                    override val available = true
                    override fun reserve() = (if (first) "a" else "b").repeat(64)
                    override fun release() { }
                    override suspend fun transfer(peerToken: String, exchange: suspend (FilePeerLink, Boolean) -> FastPhotoReport): FastPhotoReport {
                        assertEquals(event, receiver.savedEvents().single())
                        return exchange(link, first)
                    }
                }
                val sender = async { NearbyExchange(source, media = org.freegram.app.media.MediaStore(File(root, "photos-left")), videos = leftMedia, fastMedia = handoff(fileLeft,true), now = {100}).run(clusterLeft) }
                val peer = async { NearbyExchange(receiver, media = org.freegram.app.media.MediaStore(File(root, "photos-right")), videos = rightMedia, fastMedia = handoff(fileRight,false), now = {100}).run(clusterRight) }
                sender.await() to peer.await()
            }
            val first = meeting()
            assertEquals("Complete", first.second.outcome)
            assertEquals(1, first.second.received)
            assertEquals(2, first.second.videoPiecesReceived)
            assertEquals(listOf(1), rightMedia.missing(manifest))
            leftMedia.putPiece(manifest, 1, bytes.copyOfRange(VideoManifest.PIECE_BYTES, VideoManifest.PIECE_BYTES * 2))
            val next = meeting()
            assertEquals(0, next.second.received)
            assertEquals(1, next.second.videoPiecesReceived)
            assertArrayEquals(bytes, rightMedia.complete(manifest)!!.readBytes())
            assertEquals(event, receiver.savedEvents().single())
        } finally { source.close(); receiver.close(); root.deleteRecursively() }
    }

}
