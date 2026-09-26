package org.freegram.app

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.freegram.app.nearby.ExchangeReport
import org.freegram.app.nearby.NearbyExchange
import org.freegram.app.nearby.NearbyFrame
import org.freegram.app.nearby.NearbyFrames
import org.freegram.app.nearby.NearbyPolicy
import org.freegram.app.nearby.PeerLink
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.store.AuthorState
import org.freegram.app.store.RoomStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NearbyExchangeTest {
    private val now = 1_700_000_000L
    private val context: Context = RuntimeEnvironment.getApplication()
    private val stores = mutableListOf<Pair<RoomStore, String>>()

    @After fun tearDown() = stores.forEach { (store, name) -> store.close(); context.deleteDatabase(name) }

    private fun phone(): RoomStore = runBlocking {
        val name = "nearby-${UUID.randomUUID()}"
        RoomStore(context, name).also { it.initialize(); stores += it to name }
    }

    private fun post(key: Int, text: String, at: Long = now - 60): BulletinEvent =
        Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = key.toByte() }, text, at)

    private class ChannelLink(private val inbox: Channel<String>, private val outbox: Channel<String>) : PeerLink {
        override suspend fun send(frame: String) { outbox.trySend(frame) }
        override suspend fun receive(): String? = inbox.receiveCatching().getOrNull()
        override fun close() { outbox.close() }
    }

    private fun linkPair(): Pair<PeerLink, PeerLink> {
        val ab = Channel<String>(Channel.UNLIMITED)
        val ba = Channel<String>(Channel.UNLIMITED)
        return ChannelLink(ba, ab) to ChannelLink(ab, ba)
    }

    private fun exchange(a: RoomStore, b: RoomStore, policy: NearbyPolicy = NearbyPolicy()): Pair<ExchangeReport, ExchangeReport> = runBlocking {
        val (linkA, linkB) = linkPair()
        coroutineScope {
            val first = async { NearbyExchange(a, policy) { now }.run(linkA) }
            val second = async { NearbyExchange(b, policy) { now }.run(linkB) }
            first.await() to second.await()
        }
    }

    /** Runs our side against a scripted peer that reads and writes raw frames. */
    private fun againstScript(store: RoomStore, policy: NearbyPolicy = NearbyPolicy(), script: suspend (Channel<String>, Channel<String>) -> Unit): ExchangeReport = runBlocking {
        val toUs = Channel<String>(Channel.UNLIMITED)
        val fromUs = Channel<String>(Channel.UNLIMITED)
        coroutineScope {
            val ours = async { NearbyExchange(store, policy) { now }.run(ChannelLink(toUs, fromUs)) }
            script(toUs, fromUs)
            ours.await()
        }
    }

    private fun frame(value: NearbyFrame) = NearbyFrames.encode(value)
    private suspend fun Channel<String>.frame() = NearbyFrames.parse(receive())
    private suspend fun handshake(toUs: Channel<String>, fromUs: Channel<String>, have: List<String>, want: List<String> = emptyList()): List<String> {
        toUs.send(frame(NearbyFrame.Hello(1)))
        assertEquals(NearbyFrame.Hello(1), fromUs.frame())
        val ours = fromUs.frame() as NearbyFrame.Have
        toUs.send(frame(NearbyFrame.Have(have)))
        val wanted = (fromUs.frame() as NearbyFrame.Want).ids
        toUs.send(frame(NearbyFrame.Want(want)))
        assertEquals(NearbyFrame.Sent, fromUs.frame())
        return wanted.also { assertTrue(ours.ids.containsAll(want)) }
    }

    @Test fun postTravelsAToBToCWithoutDirectContact() {
        val a = phone(); val b = phone(); val c = phone()
        val bulletin = post(3, "Water point open at north gate")
        runBlocking { a.saveEvent(bulletin, listOf("wss://one.example", "wss://two.example")) }

        val (reportA, reportB) = exchange(a, b)
        assertEquals(ExchangeReport(1, 1, 1, 0, 0, "Complete"), reportA)
        assertEquals(1, reportB.received)
        val (reportB2, reportC) = exchange(b, c)
        assertEquals("Complete", reportB2.outcome)
        assertEquals(1, reportC.received)

        runBlocking {
            assertEquals(listOf(bulletin), c.savedEvents())
            assertTrue(Nip01Protocol.verifyBulletin(c.savedEvents().single()))
            assertEquals(listOf(bulletin to 2), c.nearbyOffers(0, Long.MAX_VALUE, 6, 10))
            assertTrue(c.deliveryTargets(bulletin.id).isEmpty())
        }
    }

    @Test fun bothSidesSwapAndSkipWhatTheyHold() {
        val a = phone(); val b = phone()
        val shared = post(3, "shared"); val onlyA = post(3, "only A"); val onlyB = post(5, "only B")
        runBlocking {
            a.saveReceivedEvent(shared); a.saveReceivedEvent(onlyA)
            b.saveReceivedEvent(shared); b.saveReceivedEvent(onlyB)
        }
        val (reportA, reportB) = exchange(a, b)
        assertEquals(1, reportA.sent); assertEquals(1, reportA.received)
        assertEquals(1, reportB.sent); assertEquals(1, reportB.received)
        runBlocking {
            assertEquals(setOf(shared, onlyA, onlyB), a.savedEvents().toSet())
            assertEquals(setOf(shared, onlyA, onlyB), b.savedEvents().toSet())
        }
        val (again, _) = exchange(a, b)
        assertEquals(0, again.sent)
    }

    @Test fun tamperedEventIsRejectedAndNotStored() {
        val us = phone()
        val original = post(3, "Exit B is safe")
        val tampered = original.copy(content = "Exit B is closed")
        val report = againstScript(us) { toUs, fromUs ->
            assertEquals(listOf(original.id), handshake(toUs, fromUs, have = listOf(original.id)))
            toUs.send(frame(NearbyFrame.Event(0, tampered)))
            assertEquals(NearbyFrame.Nack(original.id, "invalid event"), fromUs.frame())
            toUs.send(frame(NearbyFrame.Sent)); toUs.close()
        }
        assertEquals(1, report.rejected)
        assertEquals("Complete", report.outcome)
        runBlocking { assertTrue(us.savedEvents().isEmpty()) }
    }

    @Test fun unrequestedAndDuplicateEventsAreRejected() {
        val us = phone()
        val requested = post(3, "requested"); val pushed = post(3, "pushed")
        val report = againstScript(us) { toUs, fromUs ->
            handshake(toUs, fromUs, have = listOf(requested.id))
            toUs.send(frame(NearbyFrame.Event(0, pushed)))
            assertEquals(NearbyFrame.Nack(pushed.id, "unrequested"), fromUs.frame())
            toUs.send(frame(NearbyFrame.Event(0, requested)))
            assertEquals(NearbyFrame.Ack(requested.id), fromUs.frame())
            toUs.send(frame(NearbyFrame.Event(0, requested)))
            assertEquals(NearbyFrame.Nack(requested.id, "unrequested"), fromUs.frame())
            toUs.send(frame(NearbyFrame.Sent)); toUs.close()
        }
        assertEquals(1, report.received); assertEquals(2, report.rejected)
        runBlocking { assertEquals(listOf(requested), us.savedEvents()) }
    }

    @Test fun hopAgeClockAndBlockLimitsApply() {
        val us = phone()
        val atLimit = post(3, "hop 6"); val stale = post(3, "stale", now - 49 * 3600); val future = post(3, "future", now + 3600)
        val blocked = post(9, "blocked author")
        runBlocking { us.setAuthorState(blocked.pubkey, AuthorState.BLOCKED) }
        val report = againstScript(us) { toUs, fromUs ->
            handshake(toUs, fromUs, have = listOf(atLimit.id, stale.id, future.id, blocked.id))
            toUs.send(frame(NearbyFrame.Event(6, atLimit)))
            assertEquals(NearbyFrame.Nack(atLimit.id, "hop limit"), fromUs.frame())
            toUs.send(frame(NearbyFrame.Event(0, stale)))
            assertEquals(NearbyFrame.Nack(stale.id, "too old"), fromUs.frame())
            toUs.send(frame(NearbyFrame.Event(0, future)))
            assertEquals(NearbyFrame.Nack(future.id, "future timestamp"), fromUs.frame())
            toUs.send(frame(NearbyFrame.Event(0, blocked)))
            assertEquals(NearbyFrame.Nack(blocked.id, "blocked author"), fromUs.frame())
            toUs.send(frame(NearbyFrame.Sent)); toUs.close()
        }
        assertEquals(4, report.rejected)
        runBlocking { assertTrue(us.savedEvents().isEmpty()) }
    }

    @Test fun ineligiblePostsAreNotOffered() {
        val us = phone()
        val fresh = post(3, "fresh")
        runBlocking {
            us.saveReceivedEvent(fresh)
            us.saveReceivedEvent(post(3, "old", now - 49 * 3600))
            us.saveNearbyEvent(post(3, "far travelled"), 6)
            val blocked = post(9, "blocked").also { us.saveReceivedEvent(it) }
            us.setAuthorState(blocked.pubkey, AuthorState.BLOCKED)
        }
        val report = againstScript(us) { toUs, fromUs ->
            toUs.send(frame(NearbyFrame.Hello(1)))
            fromUs.frame()
            assertEquals(NearbyFrame.Have(listOf(fresh.id)), fromUs.frame())
            toUs.close()
        }
        assertEquals("Disconnected", report.outcome)
    }

    @Test fun inventoryAndTransferAreBoundedPerExchange() {
        val a = phone(); val b = phone()
        runBlocking { repeat(40) { a.saveReceivedEvent(post(3, "post $it", now - 1000 + it)) } }
        val (first, _) = exchange(a, b)
        assertEquals(32, first.sent)
        val (second, _) = exchange(a, b)
        assertEquals(8, second.sent)
        runBlocking { assertEquals(40, b.savedEvents().size) }

        val big = phone()
        runBlocking { repeat(100) { big.saveReceivedEvent(post(3, "many $it", now - 1000 + it)) } }
        assertTrue(runBlocking { big.nearbyOffers(0, Long.MAX_VALUE, 6, NearbyFrames.MAX_HAVE) }.size <= NearbyFrames.MAX_HAVE)
        assertThrows(IllegalArgumentException::class.java) {
            NearbyFrames.parse(frame(NearbyFrame.Have(List(129) { "%064x".format(it) })))
        }
    }

    @Test fun peerMayNotRequestUnofferedIds() {
        val us = phone()
        val secret = post(3, "not offered")
        val report = againstScript(us) { toUs, fromUs ->
            toUs.send(frame(NearbyFrame.Hello(1))); fromUs.frame(); fromUs.frame()
            toUs.send(frame(NearbyFrame.Have(emptyList()))); fromUs.frame()
            toUs.send(frame(NearbyFrame.Want(listOf(secret.id))))
        }
        assertEquals("Protocol error: Requested an ID that was not offered", report.outcome)
        assertEquals(0, report.sent)
    }

    @Test fun disconnectTimeoutAndGarbageEndSafely() {
        val us = phone()
        val wanted = post(3, "never arrives")
        val dropped = againstScript(us) { toUs, fromUs ->
            handshake(toUs, fromUs, have = listOf(wanted.id)); toUs.close()
        }
        assertEquals("Disconnected", dropped.outcome)
        assertEquals(0, dropped.received)

        val silent = againstScript(us, NearbyPolicy(frameTimeoutMs = 200)) { _, _ -> }
        assertEquals("Timed out", silent.outcome)

        val garbage = againstScript(us) { toUs, _ -> toUs.send("{not json") }
        assertTrue(garbage.outcome.startsWith("Protocol error"))

        val huge = againstScript(us) { toUs, _ -> toUs.send("[\"HELLO\",1,\"${"x".repeat(7000)}\"]") }
        assertEquals("Protocol error: Frame too large", huge.outcome)

        val wrongVersion = againstScript(us) { toUs, _ -> toUs.send(frame(NearbyFrame.Hello(2))) }
        assertEquals("Protocol error: Unsupported version 2", wrongVersion.outcome)
        runBlocking { assertTrue(us.savedEvents().isEmpty()) }
    }

    @Test fun framesRoundTrip() {
        val event = post(3, "round trip")
        listOf(
            NearbyFrame.Hello(1), NearbyFrame.Have(listOf(event.id)), NearbyFrame.Want(emptyList()),
            NearbyFrame.Event(2, event), NearbyFrame.Ack(event.id), NearbyFrame.Nack(event.id, "store full"), NearbyFrame.Sent,
        ).forEach { assertEquals(it, NearbyFrames.parse(NearbyFrames.encode(it))) }
    }
}
