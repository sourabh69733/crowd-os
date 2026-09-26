package org.freegram.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.freegram.app.protocol.Nip01Protocol
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
class RoomStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "freegram-test-${UUID.randomUUID()}"
        context.getSharedPreferences("freegram_local", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() { context.deleteDatabase(databaseName) }

    private fun event(at: Long = 1_700_000_000) = Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = 3 }, "Test bulletin", at)

    @Test fun signedEventAndRelayStatesSurviveReopen() = runBlocking {
        val first = RoomStore(context, databaseName)
        first.initialize()
        first.saveDraft("local draft")
        val signed = event()
        first.saveEvent(signed, listOf("wss://one.example", "wss://two.example"))
        first.setRelayState(signed.id, "wss://one.example", "Accepted")
        first.close()

        val reopened = RoomStore(context, databaseName)
        reopened.initialize()
        assertEquals("local draft", reopened.draft())
        assertEquals(signed, reopened.latestEvent())
        assertEquals("Accepted", reopened.relayState(signed.id, "wss://one.example"))
        assertEquals("Pending", reopened.relayState(signed.id, "wss://two.example"))
        assertEquals(listOf("wss://two.example"), reopened.pendingRelayTargets(signed.id))
        reopened.saveEvent(signed, listOf("wss://one.example", "wss://two.example"))
        assertEquals("Accepted", reopened.relayState(signed.id, "wss://one.example"))
        reopened.close()
    }

    @Test fun invalidEventDoesNotEnterOutbox() = runBlocking {
        val store = RoomStore(context, databaseName)
        store.initialize()
        val invalid = event().copy(content = "tampered")
        try {
            store.saveEvent(invalid, listOf("wss://one.example"))
            fail("Expected invalid signature to be rejected")
        } catch (_: IllegalArgumentException) { }
        assertNull(store.latestEvent())
        store.close()
    }

    @Test fun migratesExistingPrototypeWithoutChangingSignedEvent() = runBlocking {
        val signed = event()
        val oldPrefs = context.getSharedPreferences("freegram_local", Context.MODE_PRIVATE)
        oldPrefs.edit()
            .putString("draft", "old draft")
            .putString("events", org.json.JSONArray().put(Nip01Protocol.toJson(signed)).toString())
            .putString("relay:${signed.id}:wss://one.example", "Accepted")
            .commit()
        val store = RoomStore(context, databaseName)
        store.initialize()
        assertEquals("old draft", store.draft())
        assertEquals(signed, store.latestEvent())
        assertEquals("Accepted", store.relayState(signed.id, "wss://one.example"))
        assertNull(oldPrefs.getString("events", null))
        store.close()
    }

    @Test fun fullStoreKeepsExistingEventButRejectsNewEvent() = runBlocking {
        val store = RoomStore(context, databaseName)
        store.initialize()
        val relayTargets = listOf("wss://one.example", "wss://two.example")
        val first = event()
        for (offset in 0 until 100) store.saveEvent(event(1_700_000_000L + offset), relayTargets)
        store.setRelayState(first.id, relayTargets[0], "Accepted")
        store.saveEvent(first, relayTargets)
        assertEquals("Accepted", store.relayState(first.id, relayTargets[0]))
        try {
            store.saveEvent(event(1_700_000_100L), relayTargets)
            fail("Expected full outbox rejection")
        } catch (_: IllegalStateException) { }
        store.close()
    }
}
