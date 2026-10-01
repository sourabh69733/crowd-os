package org.freegram.app

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.freegram.app.nearby.NearbyExchange
import org.freegram.app.nearby.PeerLink
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.relay.retryPendingDeliveries
import org.freegram.app.store.RoomStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** Phase 4 in simulation: offline nearby carry, then automatic publish when the carrier is online. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NearbyBridgeTest {
    private val now = 1_700_000_000L
    private val relays = listOf("wss://one.example", "wss://two.example")
    private val context: Context = RuntimeEnvironment.getApplication()
    private val stores = mutableListOf<Pair<RoomStore, String>>()

    @After fun tearDown() = stores.forEach { (store, name) -> store.close(); context.deleteDatabase(name) }

    private fun phone(): RoomStore = runBlocking {
        val name = "bridge-${UUID.randomUUID()}"
        RoomStore(context, name).also { it.initialize(); stores += it to name }
    }

    private fun post(key: Int, text: String, at: Long = now - 60) =
        Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = key.toByte() }, text, at)

    private class ChannelLink(private val inbox: Channel<String>, private val outbox: Channel<String>) : PeerLink {
        override suspend fun send(frame: String) { outbox.trySend(frame) }
        override suspend fun receive(): String? = inbox.receiveCatching().getOrNull()
        override fun close() { outbox.close() }
    }

    private fun exchange(a: RoomStore, b: RoomStore, bridgeB: List<String>) = runBlocking {
        val ab = Channel<String>(Channel.UNLIMITED); val ba = Channel<String>(Channel.UNLIMITED)
        coroutineScope {
            val first = async { NearbyExchange(a) { now }.run(ChannelLink(ba, ab)) }
            val second = async { NearbyExchange(b, bridgeTo = bridgeB) { now }.run(ChannelLink(ab, ba)) }
            first.await() to second.await()
        }
    }

    /** A relay that stores exactly what it is given, for a reader D to fetch. */
    private class FakeRelay { val stored = mutableMapOf<String, String>() }

    @Test fun carrierPublishesAuthorsUnchangedPostWhenOnline() {
        val a = phone(); val b = phone()
        val bulletin = post(3, "Road to camp 2 flooded")
        runBlocking { a.saveEvent(bulletin, relays) } // A is offline: its own relay targets stay Pending

        exchange(a, b, bridgeB = relays)
        runBlocking {
            assertEquals(listOf("Sending", "Sending"), relays.map { b.relayState(bulletin.id, it) })

            val relayOne = FakeRelay(); val relayTwo = FakeRelay()
            val published = mutableListOf<BulletinEvent>()
            assertFalse(retryPendingDeliveries(b, { url, event ->
                published += event
                (if (url == relays[0]) relayOne else relayTwo).stored[event.id] = Nip01Protocol.toJson(event)
                "Accepted"
            }, now))
            assertEquals(listOf(bulletin, bulletin), published)
            assertEquals(listOf("Accepted", "Accepted"), relays.map { b.relayState(bulletin.id, it) })

            // Reader D fetches from one relay and verifies A's signature; B's key was never involved.
            val fetched = Nip01Protocol.parseImportedBulletin(relayTwo.stored.getValue(bulletin.id))
            assertEquals(bulletin, fetched)
            assertEquals(bulletin.pubkey, fetched.pubkey)
        }
    }

    @Test fun bridgeOffStoresWithoutQueueing() {
        val a = phone(); val b = phone()
        val bulletin = post(3, "no bridge")
        runBlocking { a.saveReceivedEvent(bulletin) }
        exchange(a, b, bridgeB = emptyList())
        runBlocking {
            assertEquals(listOf(bulletin), b.savedEvents())
            assertTrue(b.deliveryTargets(bulletin.id).isEmpty())
            assertTrue(b.retryableDeliveries(0).isEmpty())
        }
    }

    @Test fun queuedNearbyPostsCannotCrowdOutOwnPosts() = runBlocking {
        val b = phone()
        repeat(100) { b.saveNearbyEvent(post(10 + it % 10, "carried $it", now - 1000 + it), 1, relays) }
        assertEquals(100, b.savedEvents().size)
        val own = post(3, "my own post", now)
        b.saveEvent(own, relays)
        assertTrue(own.id in b.savedEvents().map { it.id })
        assertEquals(100, b.savedEvents().size)
    }

    @Test fun rejectedCarriedPostIsNotRetried() = runBlocking {
        val b = phone()
        val bulletin = post(3, "relay refuses")
        b.saveNearbyEvent(bulletin, 1, relays)
        assertFalse(retryPendingDeliveries(b, { _, _ -> "Rejected: blocked: not allowed" }, now))
        assertEquals("Rejected: blocked: not allowed", b.relayState(bulletin.id, relays[0]))
    }
}
