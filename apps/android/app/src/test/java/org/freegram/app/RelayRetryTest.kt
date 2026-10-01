package org.freegram.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.relay.RelayDelivery
import org.freegram.app.relay.RetryPolicy
import org.freegram.app.relay.retryPendingDeliveries
import org.freegram.app.store.RoomStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayRetryTest {
    private val one = "wss://one.example"
    private val two = "wss://two.example"
    private val now = 1_700_000_000L
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var store: RoomStore

    @Before fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        name = "retry-test-${UUID.randomUUID()}"
        context.getSharedPreferences("freegram_local", Context.MODE_PRIVATE).edit().clear().commit()
        store = RoomStore(context, name)
        store.initialize()
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(name)
    }

    private suspend fun saved(at: Long = now): BulletinEvent =
        Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = 3 }, "Retry test $at", at).also { store.saveEvent(it, listOf(one, two)) }

    /** Returns scripted outcomes per relay and counts calls. */
    private class ScriptedRelays(vararg scripts: Pair<String, List<String>>) {
        private val remaining = scripts.associate { it.first to it.second.toMutableList() }
        val calls = mutableMapOf<String, Int>()
        val publish: suspend (String, BulletinEvent) -> String = { relay, _ ->
            synchronized(this) {
                calls[relay] = (calls[relay] ?: 0) + 1
                remaining.getValue(relay).removeAt(0)
            }
        }
    }

    @Test fun temporaryFailuresRetryUntilAccepted() = runBlocking {
        val event = saved()
        val relays = ScriptedRelays(one to listOf("HTTP 503", "Network error", "Accepted"), two to listOf("Accepted"))
        val pauses = mutableListOf<Long>()
        val result = RelayDelivery(store, relays.publish, pause = { synchronized(pauses) { pauses += it } }).deliver(event, listOf(one, two))
        assertEquals(mapOf(one to "Accepted", two to "Accepted"), result)
        assertEquals(mapOf(one to 3, two to 1), relays.calls)
        assertEquals(listOf(2_000L, 5_000L), pauses)
        assertTrue(store.retryableDeliveries(0).isEmpty())
    }

    @Test fun policyRejectionIsNotRetried() = runBlocking {
        val event = saved()
        val relays = ScriptedRelays(one to listOf("Rejected: blocked: not allowed"), two to listOf("Rejected: rate-limited: slow down", "Accepted"))
        val result = RelayDelivery(store, relays.publish, pause = {}).deliver(event, listOf(one, two))
        assertEquals("Rejected: blocked: not allowed", result[one])
        assertEquals("Accepted", result[two])
        assertEquals(mapOf(one to 1, two to 2), relays.calls)
    }

    @Test fun attemptsAreBoundedAndLeftForBackground() = runBlocking {
        val event = saved()
        val relays = ScriptedRelays(one to List(3) { "Timed out" }, two to listOf("Accepted"))
        RelayDelivery(store, relays.publish, pause = {}).deliver(event, listOf(one, two))
        assertEquals(3, relays.calls[one])
        assertEquals(listOf(event to listOf(one)), store.retryableDeliveries(0))
    }

    @Test fun acceptedRelayIsSkippedAndNeverDowngraded() = runBlocking {
        val event = saved()
        store.setRelayState(event.id, one, "Accepted")
        assertEquals("Accepted", store.setRelayState(event.id, one, "Network error"))
        assertEquals("Accepted", store.relayState(event.id, one))
        val relays = ScriptedRelays(one to emptyList(), two to listOf("Accepted"))
        RelayDelivery(store, relays.publish, pause = {}).deliver(event, listOf(one, two))
        assertNull(relays.calls[one])
    }

    @Test fun backgroundRetriesOnlySubmittedRecentEvents() = runBlocking {
        val killedMidSend = saved(now)
        store.setRelayState(killedMidSend.id, one, "Sending")
        store.setRelayState(killedMidSend.id, two, "Accepted")
        val importedNotSubmitted = saved(now + 1)
        val tooOld = saved(now - RetryPolicy.MAX_AGE_SECONDS - 1)
        store.setRelayState(tooOld.id, one, "Network error")
        store.setRelayState(tooOld.id, two, "Network error")

        val relays = ScriptedRelays(one to listOf("HTTP 502", "Accepted"), two to emptyList())
        assertTrue(retryPendingDeliveries(store, relays.publish, now))
        assertEquals("HTTP 502", store.relayState(killedMidSend.id, one))
        assertFalse(retryPendingDeliveries(store, relays.publish, now))
        assertEquals("Accepted", store.relayState(killedMidSend.id, one))
        assertEquals(mapOf(one to 2), relays.calls)
        assertEquals("Pending", store.relayState(importedNotSubmitted.id, one))
        assertEquals("Network error", store.relayState(tooOld.id, one))
    }

    @Test fun publishExceptionIsRecordedAsNetworkError() = runBlocking {
        val event = saved()
        val result = RelayDelivery(store, { relay, _ -> if (relay == one) error("socket closed") else "Accepted" }, retryDelaysMs = emptyList())
            .deliver(event, listOf(one, two))
        assertEquals("Network error", result[one])
    }
}
