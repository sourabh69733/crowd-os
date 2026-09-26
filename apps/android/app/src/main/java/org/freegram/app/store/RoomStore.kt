package org.freegram.app.store

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.json.JSONArray

/** Durable local data for public bulletins. The Nostr signing secret is stored separately. */
class RoomStore(context: Context, databaseName: String = "freegram.db") {
    private val prefs = context.getSharedPreferences("freegram_local", Context.MODE_PRIVATE)
    private val database = Room.databaseBuilder(context.applicationContext, FreegramDatabase::class.java, databaseName).build()
    private val dao = database.bulletins()
    private val migrationLock = Mutex()

    suspend fun initialize() = migrationLock.withLock {
        if (prefs.getBoolean("room_migrated_v1", false)) return@withLock
        val oldDraft = prefs.getString("draft", null)
        val oldWires = JSONArray(prefs.getString("events", "[]"))
        val oldEvents = (0 until oldWires.length()).map { index ->
            Nip01Protocol.fromJson(oldWires.getString(index)).also { require(Nip01Protocol.verifyBulletin(it)) }
        }
        val oldStates = prefs.all.filterKeys { it.startsWith("relay:") }
        database.withTransaction {
            oldDraft?.let { dao.setDraft(DraftRow(content = it)) }
            for (event in oldEvents) {
                dao.insertBulletin(BulletinRow(event.id, event.createdAt, Nip01Protocol.toJson(event)))
                for ((key, value) in oldStates) {
                    val prefix = "relay:${event.id}:"
                    if (key.startsWith(prefix) && value is String) {
                        dao.setRelay(RelayDeliveryRow(event.id, key.removePrefix(prefix), value))
                    }
                }
            }
        }
        val edit = prefs.edit().remove("events").remove("draft").putBoolean("room_migrated_v1", true)
        oldStates.keys.forEach(edit::remove)
        check(edit.commit())
    }

    suspend fun draft(): String = dao.draft() ?: ""
    suspend fun saveDraft(value: String) { dao.setDraft(DraftRow(content = value)) }

    suspend fun saveEvent(event: BulletinEvent, relays: List<String>) {
        require(Nip01Protocol.verifyBulletin(event))
        require(relays.size == 2 && relays.distinct().size == 2 && relays.all { it.startsWith("wss://") })
        database.withTransaction {
            if (dao.bulletinCount() >= 100 && !dao.hasBulletin(event.id)) {
                error("Local outbox is full")
            }
            dao.insertBulletin(BulletinRow(event.id, event.createdAt, Nip01Protocol.toJson(event)))
            relays.forEach { dao.insertRelay(RelayDeliveryRow(event.id, it, "Pending")) }
        }
    }

    suspend fun latestEvent(): BulletinEvent? = dao.latestWire()?.let(Nip01Protocol::fromJson)?.takeIf(Nip01Protocol::verifyBulletin)
    suspend fun relayState(eventId: String, relay: String): String = dao.relayState(eventId, relay) ?: "Pending"
    suspend fun pendingRelayTargets(eventId: String): List<String> = dao.pendingRelays(eventId)
    suspend fun setRelayState(eventId: String, relay: String, state: String) {
        require(state == "Accepted" || state == "Pending" || state == "Timed out" || state == "Network error" || state.startsWith("Rejected:"))
        dao.setRelay(RelayDeliveryRow(eventId, relay, state))
    }

    fun relayUrl(index: Int): String = prefs.getString("relay_url_$index", if (index == 0) "wss://relay.damus.io" else "wss://nos.lol") ?: ""
    fun setRelayUrl(index: Int, url: String) {
        require(url.startsWith("wss://"))
        check(prefs.edit().putString("relay_url_$index", url).commit())
    }

    fun close() = database.close()
}
