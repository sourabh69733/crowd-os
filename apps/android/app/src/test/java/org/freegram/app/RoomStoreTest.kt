package org.freegram.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.store.RoomStore
import org.freegram.app.store.AuthorState
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

    @Test fun fetchedEventIsNotQueuedUntilUserChoosesToCarryIt() = runBlocking {
        val store = RoomStore(context, databaseName)
        store.initialize()
        val signed = event()
        store.saveReceivedEvent(signed)
        assertEquals(signed, store.latestEvent())
        assertTrue(store.deliveryTargets(signed.id).isEmpty())
        val relays = listOf("wss://one.example", "wss://two.example")
        store.saveEvent(signed, relays)
        assertEquals(relays, store.deliveryTargets(signed.id))
        store.close()
    }

    @Test fun savedBulletinsAreUniqueNewestFirstAndSurviveReopen() = runBlocking {
        val store = RoomStore(context, databaseName)
        store.initialize()
        val older = event(1_700_000_000)
        val newer = event(1_700_000_060)
        store.saveReceivedEvent(newer)
        store.saveReceivedEvent(older)
        store.saveReceivedEvent(newer)
        assertEquals(listOf(newer, older), store.savedEvents())
        store.close()

        val reopened = RoomStore(context, databaseName)
        reopened.initialize()
        assertEquals(listOf(newer, older), reopened.savedEvents())
        reopened.close()
    }

    @Test fun authorControlsPersistAndBlockedEventsStayOutOfStorage() = runBlocking {
        val store = RoomStore(context, databaseName)
        store.initialize()
        val first = event()
        store.saveReceivedEvent(first)
        store.setAuthorState(first.pubkey, AuthorState.FOLLOWING)
        assertEquals(listOf(first), store.feedEvents())
        store.setAuthorState(first.pubkey, AuthorState.MUTED)
        assertTrue(store.feedEvents().isEmpty())
        assertEquals(listOf(first), store.savedEvents())
        store.setAuthorState(first.pubkey, AuthorState.BLOCKED)
        assertTrue(store.feedEvents().isEmpty())
        assertTrue(store.savedEvents().isEmpty())
        assertNull(store.latestEvent())
        try {
            store.saveReceivedEvent(event(1_700_000_060))
            fail("Blocked author must not enter storage")
        } catch (_: IllegalArgumentException) { }
        store.close()

        val reopened = RoomStore(context, databaseName)
        reopened.initialize()
        assertEquals(AuthorState.BLOCKED, reopened.authorPolicies().single().state)
        reopened.removeAuthor(first.pubkey)
        assertEquals(listOf(first), reopened.savedEvents())
        assertTrue(reopened.feedEvents().isEmpty())
        reopened.close()
    }

    @Test fun versionOneBulletinsSurviveAuthorPolicyMigration() = runBlocking {
        val signed = event()
        val old = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        old.execSQL("CREATE TABLE bulletins (id TEXT NOT NULL PRIMARY KEY, createdAt INTEGER NOT NULL, wire TEXT NOT NULL)")
        old.execSQL("CREATE TABLE relay_deliveries (eventId TEXT NOT NULL, relay TEXT NOT NULL, state TEXT NOT NULL, PRIMARY KEY(eventId, relay), FOREIGN KEY(eventId) REFERENCES bulletins(id) ON DELETE CASCADE)")
        old.execSQL("CREATE INDEX index_relay_deliveries_eventId ON relay_deliveries(eventId)")
        old.execSQL("CREATE TABLE drafts (slot INTEGER NOT NULL PRIMARY KEY, content TEXT NOT NULL)")
        old.execSQL("INSERT INTO bulletins (id, createdAt, wire) VALUES (?, ?, ?)", arrayOf<Any>(signed.id, signed.createdAt, Nip01Protocol.toJson(signed)))
        old.execSQL("INSERT INTO relay_deliveries (eventId, relay, state) VALUES (?, ?, ?)", arrayOf(signed.id, "wss://one.example", "Accepted"))
        old.version = 1
        old.close()

        val store = RoomStore(context, databaseName)
        store.initialize()
        assertEquals(listOf(signed), store.savedEvents())
        assertEquals("Accepted", store.relayState(signed.id, "wss://one.example"))
        store.setAuthorState(signed.pubkey, AuthorState.FOLLOWING)
        assertEquals(listOf(signed), store.feedEvents())
        store.close()
    }

    @Test fun authorControlsRejectInvalidKeysAndCapTheList() = runBlocking {
        val store = RoomStore(context, databaseName)
        store.initialize()
        try {
            store.setAuthorState("not-a-key", AuthorState.FOLLOWING)
            fail("Invalid public key must be rejected")
        } catch (_: IllegalArgumentException) { }
        for (number in 0 until 20) {
            store.setAuthorState(number.toString(16).padStart(64, '0'), AuthorState.FOLLOWING)
        }
        try {
            store.setAuthorState("f".repeat(64), AuthorState.FOLLOWING)
            fail("The twenty-first author must be rejected")
        } catch (_: IllegalStateException) { }
        assertEquals(20, store.authorPolicies().size)
        store.close()
    }
}
