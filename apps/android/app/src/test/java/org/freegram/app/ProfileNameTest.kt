package org.freegram.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.ProfileEvent
import org.freegram.app.store.RoomStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileNameTest {
    private fun key(k: Int) = ByteArray(32).also { it[31] = k.toByte() }

    @Test fun nameIsSignedCleanedAndBounded() {
        val event = ProfileEvent.sign(key(3), "  Meera‮\u0007 ", 1_700_000_000)
        assertEquals(ProfileEvent.KIND, event.kind)
        assertEquals("Meera", ProfileEvent.nameOf(event))
        assertEquals(40, ProfileEvent.nameOf(ProfileEvent.sign(key(3), "x".repeat(100), 1))!!.length)
        assertNull(ProfileEvent.nameOf(event.copy(content = "{\"name\":\"Police\"}")))
        assertNull(ProfileEvent.nameOf(Nip01Protocol.signEvent(key(3), 1, "{\"name\":\"x\"}", 1, emptyArray())))
        assertNull(ProfileEvent.nameOf(Nip01Protocol.signEvent(key(3), 0, "not json", 1, emptyArray())))
        assertThrows(IllegalArgumentException::class.java) { ProfileEvent.sign(key(3), " \u0007 ", 1) }
    }

    @Test fun otherClientsDisplayNameIsAccepted() {
        val event = Nip01Protocol.signEvent(key(4), 0, "{\"display_name\":\"Ravi K.\",\"about\":\"hi\"}", 1, emptyArray())
        assertEquals("Ravi K.", ProfileEvent.nameOf(event))
    }

    @Test fun newestProfilePerKeyWins() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "profiles-${UUID.randomUUID()}"
        val store = RoomStore(context, name).also { it.initialize() }
        try {
            val newer = ProfileEvent.sign(key(3), "New name", 200)
            val older = ProfileEvent.sign(key(3), "Old name", 100)
            assertTrue(store.saveProfile(newer))
            assertFalse(store.saveProfile(older))
            assertTrue(store.saveProfile(ProfileEvent.sign(key(5), "Other", 50)))
            assertEquals(mapOf(newer.pubkey to "New name", ProfileEvent.sign(key(5), "x", 1).pubkey to "Other"), store.names())
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
