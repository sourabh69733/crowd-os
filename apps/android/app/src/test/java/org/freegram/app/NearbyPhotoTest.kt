package org.freegram.app

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.freegram.app.media.MediaStore
import org.freegram.app.media.sha256Hex
import org.freegram.app.nearby.ExchangeReport
import org.freegram.app.nearby.NearbyExchange
import org.freegram.app.nearby.NearbyFrame
import org.freegram.app.nearby.NearbyFrames
import org.freegram.app.nearby.PeerLink
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.PhotoRef
import org.freegram.app.store.AuthorState
import org.freegram.app.store.RoomStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NearbyPhotoTest {
    private val now = 1_700_000_000L
    private val context: Context = RuntimeEnvironment.getApplication()
    private val stores = mutableListOf<Pair<RoomStore, String>>()
    private val dirs = mutableListOf<File>()

    @After fun tearDown() {
        stores.forEach { (store, name) -> store.close(); context.deleteDatabase(name) }
        dirs.forEach { it.deleteRecursively() }
    }

    private inner class Phone(val media: MediaStore? = newMedia()) {
        val name = "photo-${UUID.randomUUID()}"
        val store = runBlocking { RoomStore(context, name).also { it.initialize(); stores += it to name } }
        suspend fun exchange(link: PeerLink) = NearbyExchange(store, media = media, photoCheck = { bytes, ref -> bytes.size == ref.size }) { now }.run(link)
    }

    private fun newMedia(maxBytes: Long = 50L * 1024 * 1024) =
        MediaStore(File(context.cacheDir, "media-${UUID.randomUUID()}").also { dirs += it }, maxBytes)

    /** Fake JPEG bytes: the SOI marker and random data, several chunks long. */
    private fun photoBytes(size: Int = 40_000) = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + Random(size).nextBytes(size - 2)

    private fun photoPost(key: Int, bytes: ByteArray, text: String = "photo post"): BulletinEvent {
        val ref = PhotoRef(sha256Hex(bytes), bytes.size, 800, 600)
        return Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = key.toByte() }, text, now - 60, arrayOf(ref.toTag()))
    }

    private class ChannelLink(private val inbox: Channel<String>, private val outbox: Channel<String>) : PeerLink {
        override suspend fun send(frame: String) { outbox.trySend(frame) }
        override suspend fun receive(): String? = inbox.receiveCatching().getOrNull()
        override fun close() { outbox.close() }
    }

    private fun exchange(a: Phone, b: Phone): Pair<ExchangeReport, ExchangeReport> = runBlocking {
        val ab = Channel<String>(Channel.UNLIMITED); val ba = Channel<String>(Channel.UNLIMITED)
        coroutineScope {
            val first = async { a.exchange(ChannelLink(ba, ab)) }
            val second = async { b.exchange(ChannelLink(ab, ba)) }
            first.await() to second.await()
        }
    }

    private fun Phone.hold(post: BulletinEvent, bytes: ByteArray?) = runBlocking {
        store.saveReceivedEvent(post)
        if (bytes != null) media!!.put(sha256Hex(bytes), bytes, emptySet())
    }

    @Test fun photoRefParsesOnlyOneValidImeta() {
        val bytes = photoBytes()
        val post = photoPost(3, bytes)
        assertEquals(PhotoRef(sha256Hex(bytes), bytes.size, 800, 600), PhotoRef.of(post))
        assertTrue(Nip01Protocol.verifyBulletin(post))
        fun with(vararg tags: Array<String>) = post.copy(tags = arrayOf(*tags))
        val good = PhotoRef.of(post)!!.toTag()
        assertNull(PhotoRef.of(with(good, good)))
        assertNull(PhotoRef.of(with(good.map { it.replace("image/jpeg", "image/png") }.toTypedArray())))
        assertNull(PhotoRef.of(with(good.map { if (it.startsWith("size")) "size 2000000" else it }.toTypedArray())))
        assertNull(PhotoRef.of(with(good.map { if (it.startsWith("x ")) "x abc" else it }.toTypedArray())))
        assertNull(PhotoRef.of(with(good.map { if (it.startsWith("dim")) "dim 5000x10" else it }.toTypedArray())))
    }

    @Test fun postAndPhotoTravelTwoHops() {
        val a = Phone(); val b = Phone(); val c = Phone()
        val bytes = photoBytes()
        val post = photoPost(3, bytes)
        a.hold(post, bytes)

        val (ab, ba) = exchange(a, b)
        assertEquals(1, ab.photosSent); assertEquals(1, ba.photosReceived)
        val (_, cb) = exchange(b, c)
        assertEquals(1, cb.photosReceived)
        assertArrayEquals(bytes, c.media!!.read(sha256Hex(bytes)))
        runBlocking { assertEquals(listOf(post), c.store.savedEvents()) }
        val (again, _) = exchange(b, c)
        assertEquals(0, again.photosSent)
    }

    @Test fun phoneWithoutPhotoSupportStillGetsText() {
        val a = Phone(); val old = Phone(media = null)
        val bytes = photoBytes()
        val post = photoPost(3, bytes)
        a.hold(post, bytes)
        val (ours, theirs) = exchange(a, old)
        assertEquals("Complete", ours.outcome); assertEquals("Complete", theirs.outcome)
        assertEquals(1, theirs.received)
        assertEquals(0, ours.photosSent)
    }

    @Test fun postWithoutItsPhotoCanGetThePhotoLater() {
        val a = Phone(); val b = Phone(); val c = Phone()
        val bytes = photoBytes()
        val post = photoPost(3, bytes)
        a.hold(post, null)  // A has only the text
        exchange(a, b)
        assertFalse(b.media!!.has(sha256Hex(bytes)))
        c.hold(post, bytes) // C has the photo
        val (_, bc) = exchange(c, b)
        assertEquals(1, bc.photosReceived)
        assertTrue(b.media!!.has(sha256Hex(bytes)))
    }

    private fun script(us: Phone, peer: suspend (Channel<String>, Channel<String>) -> Unit): ExchangeReport = runBlocking {
        val toUs = Channel<String>(Channel.UNLIMITED); val fromUs = Channel<String>(Channel.UNLIMITED)
        coroutineScope {
            val ours = async { us.exchange(ChannelLink(toUs, fromUs)) }
            peer(toUs, fromUs)
            ours.await()
        }
    }

    private fun f(frame: NearbyFrame) = NearbyFrames.encode(frame)
    private suspend fun Channel<String>.frame() = NearbyFrames.parse(receive())

    /** Runs the text round with nothing to swap, then returns the photo hashes we asked for. */
    private suspend fun toPhotoRound(toUs: Channel<String>, fromUs: Channel<String>, offer: List<String>): List<String> {
        toUs.send(f(NearbyFrame.Hello(1, media = true))); fromUs.frame()
        fromUs.frame(); toUs.send(f(NearbyFrame.Have(emptyList())))
        fromUs.frame(); toUs.send(f(NearbyFrame.Want(emptyList())))
        fromUs.frame(); toUs.send(f(NearbyFrame.Sent))
        fromUs.frame() // our BLOBHAVE
        toUs.send(f(NearbyFrame.BlobHave(offer)))
        val want = (fromUs.frame() as NearbyFrame.BlobWant).hashes
        toUs.send(f(NearbyFrame.BlobWant(emptyList())))
        assertEquals(NearbyFrame.BlobSent, fromUs.frame())
        return want
    }

    private suspend fun sendPhoto(toUs: Channel<String>, sha: String, bytes: ByteArray) {
        toUs.send(f(NearbyFrame.Blob(sha, bytes.size)))
        (bytes.indices step NearbyFrames.CHUNK_BYTES).forEachIndexed { i, start ->
            toUs.send(f(NearbyFrame.Chunk(sha, i, bytes.copyOfRange(start, minOf(start + NearbyFrames.CHUNK_BYTES, bytes.size)))))
        }
    }

    @Test fun alteredPhotoIsRejectedAndNotStored() {
        val us = Phone()
        val bytes = photoBytes()
        val post = photoPost(3, bytes)
        us.hold(post, null)
        val sha = sha256Hex(bytes)
        val altered = bytes.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val report = script(us) { toUs, fromUs ->
            assertEquals(listOf(sha), toPhotoRound(toUs, fromUs, listOf(sha)))
            sendPhoto(toUs, sha, altered)
            assertEquals(NearbyFrame.BlobNack(sha, "hash mismatch"), fromUs.frame())
            toUs.send(f(NearbyFrame.BlobSent)); toUs.close()
        }
        assertEquals("Complete", report.outcome)
        assertEquals(0, report.photosReceived)
        assertFalse(us.media!!.has(sha))
    }

    @Test fun unrequestedPhotoIsDiscarded() {
        val us = Phone()
        val stranger = photoBytes(20_000)
        val sha = sha256Hex(stranger)
        val report = script(us) { toUs, fromUs ->
            assertTrue(toPhotoRound(toUs, fromUs, emptyList()).isEmpty())
            sendPhoto(toUs, sha, stranger)
            assertEquals(NearbyFrame.BlobNack(sha, "unrequested"), fromUs.frame())
            toUs.send(f(NearbyFrame.BlobSent)); toUs.close()
        }
        assertEquals("Complete", report.outcome)
        assertFalse(us.media!!.has(sha))
    }

    @Test fun malformedPhotoTransfersEndTheExchange() {
        val bytes = photoBytes()
        val sha = sha256Hex(bytes)
        val outOfOrder = script(Phone().also { it.hold(photoPost(3, bytes), null) }) { toUs, fromUs ->
            toPhotoRound(toUs, fromUs, listOf(sha))
            toUs.send(f(NearbyFrame.Blob(sha, bytes.size)))
            toUs.send(f(NearbyFrame.Chunk(sha, 1, bytes.copyOfRange(0, 10))))
        }
        assertEquals("Protocol error: Out-of-order chunk", outOfOrder.outcome)

        val oversize = script(Phone().also { it.hold(photoPost(3, bytes), null) }) { toUs, fromUs ->
            toPhotoRound(toUs, fromUs, listOf(sha))
            toUs.send(f(NearbyFrame.Blob(sha, 10)))
            toUs.send(f(NearbyFrame.Chunk(sha, 0, bytes.copyOfRange(0, 20))))
        }
        assertEquals("Protocol error: Photo larger than announced", oversize.outcome)

        val cutOff = script(Phone().also { it.hold(photoPost(3, bytes), null) }) { toUs, fromUs ->
            toPhotoRound(toUs, fromUs, listOf(sha))
            toUs.send(f(NearbyFrame.Blob(sha, bytes.size)))
            toUs.send(f(NearbyFrame.BlobSent))
        }
        assertEquals("Protocol error: Incomplete photo", cutOff.outcome)
    }

    @Test fun blockedAuthorsPhotoIsNotOffered() {
        val a = Phone(); val b = Phone()
        val bytes = photoBytes()
        val post = photoPost(9, bytes)
        a.hold(post, bytes)
        runBlocking { a.store.setAuthorState(post.pubkey, AuthorState.BLOCKED) }
        val (ab, _) = exchange(a, b)
        assertEquals(0, ab.sent); assertEquals(0, ab.photosSent)
    }

    @Test fun mediaStoreChecksHashAndMakesRoomFromUnreferencedPhotosFirst() {
        val store = newMedia(maxBytes = 100_000)
        val kept = photoBytes(40_000); val loose = photoBytes(40_001); val incoming = photoBytes(40_002)
        assertThrows(IllegalArgumentException::class.java) { store.put(sha256Hex(kept), loose, emptySet()) }
        store.put(sha256Hex(kept), kept, emptySet())
        store.put(sha256Hex(loose), loose, emptySet())
        store.put(sha256Hex(incoming), incoming, setOf(sha256Hex(kept)))
        assertTrue(store.has(sha256Hex(kept)))
        assertFalse(store.has(sha256Hex(loose)))
        assertTrue(store.has(sha256Hex(incoming)))
        assertTrue(store.totalBytes() <= 100_000)
    }

    @Test fun photoFramesRoundTripAndStayWithinNearbyPayloadLimit() {
        val sha = "ab".repeat(32)
        val chunk = NearbyFrame.Chunk(sha, 3, Random(1).nextBytes(NearbyFrames.CHUNK_BYTES))
        val wire = NearbyFrames.encode(chunk)
        assertTrue(wire.toByteArray().size <= NearbyFrames.MAX_CHUNK_FRAME_BYTES)
        assertTrue(NearbyFrames.MAX_CHUNK_FRAME_BYTES < 32 * 1024) // Nearby BYTES payload limit
        assertEquals(chunk, NearbyFrames.parse(wire))
        listOf(NearbyFrame.Hello(1, media = true), NearbyFrame.BlobHave(listOf(sha)), NearbyFrame.BlobWant(listOf(sha)),
            NearbyFrame.Blob(sha, 1000), NearbyFrame.BlobAck(sha), NearbyFrame.BlobNack(sha, "x"), NearbyFrame.BlobSent)
            .forEach { assertEquals(it, NearbyFrames.parse(NearbyFrames.encode(it))) }
        assertEquals(NearbyFrame.Hello(1, media = false), NearbyFrames.parse("[\"HELLO\",1,{}]"))
    }
}
