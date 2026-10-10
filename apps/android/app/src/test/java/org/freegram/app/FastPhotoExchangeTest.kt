package org.freegram.app

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.freegram.app.media.MediaStore
import org.freegram.app.media.sha256Hex
import org.freegram.app.nearby.FilePeerLink
import org.freegram.app.nearby.FastPhotoExchange
import org.freegram.app.nearby.NearbyFrames
import org.freegram.app.nearby.NearbyFrame
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FastPhotoExchangeTest {
    private val context = RuntimeEnvironment.getApplication()
    private val stores = mutableListOf<RoomStore>()
    private val dirs = mutableListOf<File>()
    private fun store() = RoomStore(context, "fast-${UUID.randomUUID()}").also { stores += it }
    private fun media() = MediaStore(File(context.cacheDir, "fast-${UUID.randomUUID()}").also { dirs += it })
    @After fun cleanup() { stores.forEach { it.close() }; dirs.forEach { it.deleteRecursively() } }

    private class Link(val inbox: Channel<String>, val outbox: Channel<String>, val filesIn: Channel<Pair<Long, ByteArray>>, val filesOut: Channel<Pair<Long, ByteArray>>) : FilePeerLink {
        private val staged = mutableMapOf<Long, ByteArray>()
        private val expected = mutableMapOf<Long, Int>()
        var corrupt = false
        var fileSends = 0
        val sentFrames = mutableListOf<NearbyFrame>()
        override suspend fun send(frame: String) { sentFrames += NearbyFrames.parse(frame); outbox.send(frame) }
        override suspend fun receive() = inbox.receiveCatching().getOrNull()
        override fun close() { outbox.close() }
        override fun prepareFile(file: File): Long = ids.incrementAndGet().also { staged[it] = file.readBytes() }
        override fun expectFile(id: Long, size: Int) { check(expected.put(id, size) == null) }
        override suspend fun sendFile(id: Long) {
            fileSends++
            val bytes = staged.remove(id)!!
            filesOut.send(id to if (corrupt) bytes.copyOf().also { it[0] = 99 } else bytes)
        }
        override suspend fun receiveFile(id: Long): ByteArray {
            val (actual, bytes) = filesIn.receive()
            check(actual == id && bytes.size == expected.remove(id))
            return bytes
        }
        companion object { val ids = AtomicLong(1) }
    }

    private fun links(): Pair<Link, Link> {
        val ab = Channel<String>(32); val ba = Channel<String>(32)
        val fab = Channel<Pair<Long, ByteArray>>(1); val fba = Channel<Pair<Long, ByteArray>>(1)
        return Link(ba, ab, fba, fab) to Link(ab, ba, fab, fba)
    }

    @Test fun filesTransferBothWaysAndAckOnlyValidStoredPhotos() = runBlocking {
        val a = store(); val b = store(); a.initialize(); b.initialize()
        val am = media(); val bm = media()
        val bytes = ByteArray(1024) { 7 }
        val ref = PhotoRef(sha256Hex(bytes), bytes.size, 20, 20)
        val event = Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = 3 }, "photo", 1_700_000_000, arrayOf(ref.toTag()))
        a.saveReceivedEvent(event); b.saveReceivedEvent(event)
        am.put(ref.sha256, bytes, emptySet())
        val (la, lb) = links()
        coroutineScope {
            val sent = async { FastPhotoExchange(a, am, photoCheck = { _, _ -> true }).run(la, listOf(ref), emptyList(), true) }
            val received = async { FastPhotoExchange(b, bm, photoCheck = { _, _ -> true }).run(lb, emptyList(), listOf(ref), false) }
            assertEquals(1, sent.await().sent)
            assertEquals(1, received.await().received)
        }
        assertEquals(1, la.fileSends)
        assertArrayEquals(bytes, bm.read(ref.sha256))
    }

    @Test fun corruptedAndNewlyBlockedPhotosAreNotAcknowledged() = runBlocking {
        for (blocked in listOf(false, true)) {
            val a = store(); val b = store(); a.initialize(); b.initialize()
            val am = media(); val bm = media()
            val bytes = ByteArray(1024) { 7 }
            val ref = PhotoRef(sha256Hex(bytes), bytes.size, 20, 20)
            val event = Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = 3 }, "photo", 1_700_000_000, arrayOf(ref.toTag()))
            a.saveReceivedEvent(event); b.saveReceivedEvent(event)
            am.put(ref.sha256, bytes, emptySet())
            if (blocked) b.setAuthorState(event.pubkey, AuthorState.BLOCKED)
            val (la, lb) = links(); la.corrupt = !blocked
            coroutineScope {
                val sent = async { FastPhotoExchange(a, am, photoCheck = { _, _ -> true }).run(la, listOf(ref), emptyList(), true) }
                val received = async { FastPhotoExchange(b, bm, photoCheck = { _, _ -> true }).run(lb, emptyList(), listOf(ref), false) }
                assertEquals(0, sent.await().sent)
                assertEquals(0, received.await().received)
            }
            assertFalse(bm.has(ref.sha256))
        }
    }
    @Test fun negotiatedHandoffRunsAfterTextAndBusyPeersUseChunks() = runBlocking {
        for (busy in listOf(false, true)) {
            val a = store(); val b = store(); a.initialize(); b.initialize()
            val am = media(); val bm = media()
            val bytes = ByteArray(1024) { 7 }
            val ref = PhotoRef(sha256Hex(bytes), bytes.size, 20, 20)
            val event = Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = 3 }, "photo", 1_700_000_000, arrayOf(ref.toTag()))
            a.saveReceivedEvent(event); am.put(ref.sha256, bytes, emptySet())
            val (la, lb) = links()
            var handoffs = 0
            fun hook(token: String, link: Link) = object : org.freegram.app.nearby.FastMediaHandoff {
                override val available = true
                override fun reserve(): String? = if (busy) null else token
                override suspend fun transfer(peerToken: String, exchange: suspend (FilePeerLink, Boolean) -> org.freegram.app.nearby.FastPhotoReport): org.freegram.app.nearby.FastPhotoReport {
                    handoffs++
                    return exchange(link, token < peerToken)
                }
                override fun release() = Unit
            }
            coroutineScope {
                val sent = async { org.freegram.app.nearby.NearbyExchange(a, media = am, photoCheck = { _, _ -> true }, fastMedia = hook("01".repeat(32), la), now = { 1_700_000_060 }).run(la) }
                val received = async { org.freegram.app.nearby.NearbyExchange(b, media = bm, photoCheck = { _, _ -> true }, fastMedia = hook("02".repeat(32), lb), now = { 1_700_000_060 }).run(lb) }
                assertEquals(1, sent.await().photosSent)
                assertEquals(1, received.await().photosReceived)
                assertEquals(1, received.await().received)
            }
            assertEquals(if (busy) 0 else 2, handoffs)
            assertEquals(if (busy) 0 else 1, la.fileSends)
            assertTrue(la.sentFrames.indexOfFirst { it is NearbyFrame.Event } < la.sentFrames.indexOfFirst { it is NearbyFrame.MediaReady })
            assertArrayEquals(bytes, bm.read(ref.sha256))
        }
    }

}
