package org.freegram.app.store

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.relay.RetryPolicy
import org.json.JSONArray

enum class AuthorState { FOLLOWING, MUTED, BLOCKED }
data class AuthorPolicy(val pubkey: String, val state: AuthorState)

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
        require(relays.size == 2 && relays.distinct().size == 2 && relays.all { it.startsWith("wss://") })
        saveVerified(event, relays)
    }

    suspend fun saveReceivedEvent(event: BulletinEvent) = saveVerified(event, emptyList())

    private suspend fun saveVerified(event: BulletinEvent, relays: List<String>) {
        require(Nip01Protocol.verifyBulletin(event))
        database.withTransaction {
            require(dao.authorState(event.pubkey) != AuthorState.BLOCKED.name) { "This author is blocked" }
            if (dao.bulletinCount() >= 100 && !dao.hasBulletin(event.id)) {
                error("Local outbox is full")
            }
            dao.insertBulletin(BulletinRow(event.id, event.createdAt, Nip01Protocol.toJson(event)))
            relays.forEach { dao.insertRelay(RelayDeliveryRow(event.id, it, "Pending")) }
        }
    }

    suspend fun latestEvent(): BulletinEvent? = savedEvents().firstOrNull()
    suspend fun savedEvents(): List<BulletinEvent> {
        val blocked = dao.authorPolicies().filter { it.state == AuthorState.BLOCKED.name }.map { it.pubkey }.toSet()
        return dao.savedWires().mapNotNull { wire ->
            runCatching { Nip01Protocol.fromJson(wire) }.getOrNull()
                ?.takeIf { it.pubkey !in blocked && Nip01Protocol.verifyBulletin(it) }
        }
    }

    suspend fun setAuthorState(pubkey: String, state: AuthorState) {
        require(pubkey.length == 64 && pubkey.all { it in '0'..'9' || it in 'a'..'f' }) { "Use a 64-character lowercase public key" }
        database.withTransaction {
            check(dao.authorState(pubkey) != null || dao.authorCount() < 20) { "Author list is full" }
            dao.setAuthorPolicy(AuthorPolicyRow(pubkey, state.name))
        }
    }

    suspend fun removeAuthor(pubkey: String) { dao.removeAuthorPolicy(pubkey) }
    suspend fun authorPolicies(): List<AuthorPolicy> = dao.authorPolicies().map { AuthorPolicy(it.pubkey, AuthorState.valueOf(it.state)) }
    suspend fun feedEvents(): List<BulletinEvent> {
        val followed = dao.authorPolicies().filter { it.state == AuthorState.FOLLOWING.name }.map { it.pubkey }.toSet()
        return savedEvents().filter { it.pubkey in followed }
    }
    suspend fun relayState(eventId: String, relay: String): String = dao.relayState(eventId, relay) ?: "Pending"
    suspend fun deliveryTargets(eventId: String): List<String> = dao.deliveryTargets(eventId)
    suspend fun pendingRelayTargets(eventId: String): List<String> = dao.pendingRelays(eventId)
    /** Records a relay outcome and returns the stored state. A relay's `Accepted` is never replaced by a later failure. */
    suspend fun setRelayState(eventId: String, relay: String, state: String): String {
        require(
            state == "Accepted" || state == "Pending" || state == "Sending" || state == "Timed out" || state == "Network error" ||
                state.startsWith("Rejected:") || Regex("HTTP \\d{3}").matches(state)
        )
        return database.withTransaction {
            if (dao.relayState(eventId, relay) == "Accepted") "Accepted"
            else { dao.setRelay(RelayDeliveryRow(eventId, relay, state)); state }
        }
    }

    /** Events the user already asked to submit whose relays failed temporarily, newer than [notBeforeSeconds]. */
    suspend fun retryableDeliveries(notBeforeSeconds: Long): List<Pair<BulletinEvent, List<String>>> =
        dao.unsettledDeliveries().filter { RetryPolicy.isRetryable(it.state) }.groupBy { it.eventId }.mapNotNull { (id, rows) ->
            val event = dao.wire(id)?.let { runCatching { Nip01Protocol.fromJson(it) }.getOrNull() }
            event?.takeIf { it.createdAt >= notBeforeSeconds && Nip01Protocol.verifyBulletin(it) }?.let { it to rows.map { row -> row.relay } }
        }

    fun relayUrl(index: Int): String = prefs.getString("relay_url_$index", if (index == 0) "wss://relay.damus.io" else "wss://nos.lol") ?: ""
    fun setRelayUrl(index: Int, url: String) {
        require(url.startsWith("wss://"))
        check(prefs.edit().putString("relay_url_$index", url).commit())
    }

    fun close() = database.close()

    companion object {
        @Volatile private var shared: RoomStore? = null

        /** One database instance per process, shared by the UI and background retry. */
        fun shared(context: Context): RoomStore = shared ?: synchronized(this) {
            shared ?: RoomStore(context.applicationContext).also { shared = it }
        }
    }
}
