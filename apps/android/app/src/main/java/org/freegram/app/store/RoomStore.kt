package org.freegram.app.store

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.moderation.HideList
import org.freegram.app.relay.RetryPolicy
import org.json.JSONArray

enum class AuthorState { FOLLOWING, MUTED, BLOCKED }
data class AuthorPolicy(val pubkey: String, val state: AuthorState)
data class Maintainer(val pubkey: String, val enabled: Boolean)

/** Posts and authors hidden by the maintainers this phone follows. */
data class Hidden(val posts: Set<String>, val authors: Set<String>) {
    fun covers(event: BulletinEvent) = event.id in posts || event.pubkey in authors
}

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
        check(saveVerified(event, relays))
    }

    /** Returns false when the store is full and the post is older than everything that could make room. */
    suspend fun saveReceivedEvent(event: BulletinEvent): Boolean = saveVerified(event, emptyList())

    /**
     * Stores a post received from a nearby peer after [hops] transfers. With [bridgeTo] relays, it is also queued
     * (`Sending`) for automatic publishing when online. Returns false as for [saveReceivedEvent].
     */
    suspend fun saveNearbyEvent(event: BulletinEvent, hops: Int, bridgeTo: List<String> = emptyList()): Boolean {
        require(hops > 0)
        require(bridgeTo.isEmpty() || (bridgeTo.size == 2 && bridgeTo.distinct().size == 2 && bridgeTo.all { it.startsWith("wss://") }))
        return saveVerified(event, emptyList(), hops, bridgeTo)
    }

    /** Whether posts received nearby are published to relays automatically when online. On by default. */
    fun autoBridge(): Boolean = prefs.getBoolean("auto_bridge", true)
    fun setAutoBridge(enabled: Boolean) { check(prefs.edit().putBoolean("auto_bridge", enabled).commit()) }

    suspend fun hasBulletin(eventId: String): Boolean = dao.hasBulletin(eventId)

    /** Nearby transfer count of each stored post (0 = authored here or fetched from a relay). */
    suspend fun hopsById(): Map<String, Int> = dao.allHops().associate { it.id to it.hops }

    /** Relay states of every post that has delivery targets. */
    suspend fun deliveryStates(): Map<String, List<String>> = dao.allDeliveries().groupBy({ it.eventId }, { it.state })

    /** Keeps a verified profile name if it is newer than the one stored for that key. */
    suspend fun saveProfile(event: BulletinEvent): Boolean {
        val name = org.freegram.app.protocol.ProfileEvent.nameOf(event) ?: return false
        return database.withTransaction {
            val current = dao.profileTime(event.pubkey)
            if (current != null && current >= event.createdAt) return@withTransaction false
            dao.setProfile(ProfileRow(event.pubkey, event.createdAt, name))
            true
        }
    }

    suspend fun names(): Map<String, String> = dao.profiles().associate { it.pubkey to it.name }

    /** This phone's latest profile event, kept until a relay has accepted it. */
    fun pendingProfile(): String? = prefs.getString("pending_profile", null)
    fun setPendingProfile(wire: String?) { check(prefs.edit().apply { if (wire == null) remove("pending_profile") else putString("pending_profile", wire) }.commit()) }

    /** Whether new posts carry the Discover tag. On by default; posts are public either way. */
    fun showInDiscover(): Boolean = prefs.getBoolean("show_in_discover", true)
    fun setShowInDiscover(on: Boolean) { check(prefs.edit().putBoolean("show_in_discover", on).commit()) }

    /** This phone's own signed Discover suggestion list (maintainers only), or null. */
    fun mySuggestList(): String? = prefs.getString("my_suggest_list", null)
    fun setMySuggestList(wire: String) { check(prefs.edit().putString("my_suggest_list", wire).commit()) }

    /** Whether the built-in default maintainer was added once; after that the user's choice stands. */
    fun defaultMaintainerApplied(): Boolean = prefs.getBoolean("default_maintainer_applied", false)
    fun setDefaultMaintainerApplied() { check(prefs.edit().putBoolean("default_maintainer_applied", true).commit()) }

    fun onboarded(): Boolean = prefs.getBoolean("onboarded", false)
    fun setOnboarded() { check(prefs.edit().putBoolean("onboarded", true).commit()) }

    fun backupDone(): Boolean = prefs.getBoolean("backup_done", false)
    fun setBackupDone(done: Boolean = true) { check(prefs.edit().putBoolean("backup_done", done).commit()) }

    /** Removes this phone's copy and any queued relay delivery. Copies on relays or other phones are unaffected. */
    suspend fun deleteLocal(eventId: String) = database.withTransaction {
        dao.deleteDeliveries(eventId)
        dao.deleteBulletin(eventId)
    }

    /** Verified posts this phone may offer to a nearby peer, newest first, with their hop counts. */
    suspend fun nearbyOffers(minCreatedAt: Long, maxCreatedAt: Long, maxHops: Int, limit: Int): List<Pair<BulletinEvent, Int>> {
        val blocked = dao.authorPolicies().filter { it.state == AuthorState.BLOCKED.name }.map { it.pubkey }.toSet()
        val hidden = hidden()
        return dao.nearbyCandidates(minCreatedAt, maxCreatedAt, maxHops).mapNotNull { row ->
            runCatching { Nip01Protocol.fromJson(row.wire) }.getOrNull()
                ?.takeIf { it.pubkey !in blocked && !hidden.covers(it) && Nip01Protocol.verifyBulletin(it) }
                ?.let { it to row.hops }
        }.take(limit)
    }

    private suspend fun saveVerified(event: BulletinEvent, relays: List<String>, hops: Int = 0, bridgeTo: List<String> = emptyList()): Boolean {
        require(Nip01Protocol.verifyBulletin(event))
        return database.withTransaction {
            require(dao.authorState(event.pubkey) != AuthorState.BLOCKED.name) { "This author is blocked" }
            require(relays.isNotEmpty() || !hidden().covers(event)) { HIDDEN_MESSAGE }
            if (relays.isEmpty() && !dao.hasBulletin(event.id)) {
                // One author's received posts may not crowd out everyone else's.
                val oldestOfAuthor = evictionCandidates().filter { it.foreign && it.pubkey == event.pubkey }
                if (oldestOfAuthor.size >= MAX_PER_AUTHOR) {
                    if (oldestOfAuthor.first().createdAt > event.createdAt) return@withTransaction false
                    dao.deleteDeliveries(oldestOfAuthor.first().id)
                    dao.deleteBulletin(oldestOfAuthor.first().id)
                }
            }
            if (dao.bulletinCount() >= MAX_BULLETINS && !dao.hasBulletin(event.id)) {
                val victim = evictionVictim() ?: error("Local store is full of posts still waiting for relays")
                if (relays.isEmpty() && victim.foreign && !victim.blocked && victim.createdAt > event.createdAt) {
                    return@withTransaction false
                }
                dao.deleteDeliveries(victim.id)
                dao.deleteBulletin(victim.id)
            }
            dao.insertBulletin(BulletinRow(event.id, event.createdAt, Nip01Protocol.toJson(event), hops))
            relays.forEach { dao.insertRelay(RelayDeliveryRow(event.id, it, "Pending")) }
            bridgeTo.forEach { dao.insertRelay(RelayDeliveryRow(event.id, it, "Sending")) }
            true
        }
    }

    /** `foreign`: someone else's post this phone did not choose to submit (received, or carried from nearby). */
    private data class Victim(val id: String, val createdAt: Long, val foreign: Boolean, val pubkey: String?, val blocked: Boolean)

    /** Removable posts, oldest first. */
    private suspend fun evictionCandidates(): List<Victim> {
        val blocked = dao.authorPolicies().filter { it.state == AuthorState.BLOCKED.name }.map { it.pubkey }.toSet()
        return dao.evictionCandidates().map { row ->
            val pubkey = runCatching { Nip01Protocol.fromJson(row.wire).pubkey }.getOrNull()
            Victim(row.id, row.createdAt, row.targets == 0 || row.hops > 0, pubkey, pubkey == null || pubkey in blocked)
        }
    }

    /** Blocked authors first, then the oldest received post, then the oldest fully accepted outbox post. */
    private suspend fun evictionVictim(): Victim? {
        val candidates = evictionCandidates()
        return candidates.firstOrNull { it.blocked }
            ?: candidates.firstOrNull { it.foreign }
            ?: candidates.firstOrNull()
    }

    suspend fun latestEvent(): BulletinEvent? = savedEvents().firstOrNull()
    suspend fun savedEvents(): List<BulletinEvent> {
        val blocked = dao.authorPolicies().filter { it.state == AuthorState.BLOCKED.name }.map { it.pubkey }.toSet()
        val hidden = hidden()
        return dao.savedWires().mapNotNull { wire ->
            runCatching { Nip01Protocol.fromJson(wire) }.getOrNull()
                ?.takeIf { it.pubkey !in blocked && !hidden.covers(it) && Nip01Protocol.verifyBulletin(it) }
        }
    }

    /** Stored posts currently hidden by maintainers (not by this phone's own block list). */
    suspend fun hiddenCount(): Int {
        val hidden = hidden()
        return dao.savedWires().count { wire -> runCatching { Nip01Protocol.fromJson(wire) }.getOrNull()?.let(hidden::covers) == true }
    }

    /** Keeps a verified like. Likes from others are capped at [MAX_LIKES]; this phone's own are never pruned. */
    suspend fun saveLike(event: BulletinEvent, mine: Boolean = false, sent: Boolean = false): Boolean {
        val postId = org.freegram.app.protocol.Likes.likedPost(event) ?: return false
        dao.insertLike(LikeRow(event.id, postId, event.pubkey, event.createdAt, Nip01Protocol.toJson(event), mine, sent))
        if (!mine) dao.pruneLikes(MAX_LIKES)
        return true
    }

    /** Who liked each post (one count per person). */
    suspend fun likes(): Map<String, Set<String>> = dao.allLikes().groupBy({ it.postId }, { it.pubkey }).mapValues { it.value.toSet() }
    suspend fun likeBy(postId: String, pubkey: String): LikeRow? = dao.likeBy(postId, pubkey)
    suspend fun unsentLikes(): List<LikeRow> = dao.unsentLikes()
    suspend fun myLikeIds(): List<String> = dao.myLikeIds()
    suspend fun markLikeSent(id: String) = dao.markLikeSent(id)
    suspend fun deleteLike(id: String) = dao.deleteLike(id)

    suspend fun maintainers(): List<Maintainer> = dao.maintainers().map { Maintainer(it.pubkey, it.enabled) }

    suspend fun setMaintainer(pubkey: String, enabled: Boolean) {
        require(pubkey.length == 64 && pubkey.all { it in '0'..'9' || it in 'a'..'f' }) { "Use a 64-character lowercase public key" }
        database.withTransaction {
            check(dao.maintainers().any { it.pubkey == pubkey } || dao.maintainers().size < MAX_MAINTAINERS) { "At most $MAX_MAINTAINERS maintainers" }
            dao.setMaintainer(MaintainerRow(pubkey, enabled))
        }
    }

    suspend fun removeMaintainer(pubkey: String) = database.withTransaction {
        dao.removeMaintainer(pubkey)
        dao.removeHideList(pubkey)
    }

    /** Keeps a verified hide list from a followed maintainer if it is newer than the stored one. */
    suspend fun saveHideList(event: BulletinEvent): Boolean {
        if (!Nip01Protocol.verifySigned(event, HideList.MAX_BYTES)) return false
        val list = HideList.of(event) ?: return false
        return database.withTransaction {
            if (dao.maintainers().none { it.pubkey == list.maintainer }) return@withTransaction false
            val current = dao.hideLists().firstOrNull { it.maintainer == list.maintainer }
            if (current != null && current.createdAt >= list.createdAt) return@withTransaction false
            dao.setHideList(HideListRow(list.maintainer, list.createdAt, Nip01Protocol.toJson(event)))
            true
        }
    }

    suspend fun hideList(maintainer: String): HideList? = dao.hideLists().firstOrNull { it.maintainer == maintainer }
        ?.let { runCatching { HideList.of(Nip01Protocol.fromJson(it.wire, HideList.MAX_BYTES)) }.getOrNull() }

    suspend fun hidden(): Hidden {
        val enabled = dao.maintainers().filter { it.enabled }.map { it.pubkey }.toSet()
        val lists = dao.hideLists().filter { it.maintainer in enabled }
            .mapNotNull { runCatching { HideList.of(Nip01Protocol.fromJson(it.wire, HideList.MAX_BYTES)) }.getOrNull() }
        return Hidden(lists.flatMap { it.posts }.toSet(), lists.flatMap { it.authors }.toSet())
    }

    suspend fun setAuthorState(pubkey: String, state: AuthorState) {
        require(pubkey.length == 64 && pubkey.all { it in '0'..'9' || it in 'a'..'f' }) { "Use a 64-character lowercase public key" }
        database.withTransaction {
            check(dao.authorState(pubkey) != null || dao.authorCount() < 20) { "Author list is full" }
            dao.setAuthorPolicy(AuthorPolicyRow(pubkey, state.name))
        }
        // A changed author list must fetch the new author's history, not only newer posts.
        clearFeedSince()
    }

    suspend fun removeAuthor(pubkey: String) {
        dao.removeAuthorPolicy(pubkey)
        clearFeedSince()
    }
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

    /** Newest `created_at` seen by the last completed feed refresh from [relay], or null. */
    fun feedSince(relay: String): Long? = prefs.getLong("feed_since:$relay", -1).takeIf { it >= 0 }
    fun setFeedSince(relay: String, value: Long) { check(prefs.edit().putLong("feed_since:$relay", value).commit()) }
    private fun clearFeedSince() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("feed_since:") }.forEach(edit::remove)
        check(edit.commit())
    }

    fun relayUrl(index: Int): String = prefs.getString("relay_url_$index", if (index == 0) "wss://relay.damus.io" else "wss://nos.lol") ?: ""
    fun setRelayUrl(index: Int, url: String) {
        require(url.startsWith("wss://"))
        check(prefs.edit().putString("relay_url_$index", url).commit())
    }

    fun close() = database.close()

    companion object {
        const val MAX_BULLETINS = 100
        const val MAX_PER_AUTHOR = 20
        const val MAX_MAINTAINERS = 5
        const val MAX_LIKES = 5000
        const val HIDDEN_MESSAGE = "Hidden by a maintainer you follow"

        @Volatile private var shared: RoomStore? = null

        /** One database instance per process, shared by the UI and background retry. */
        fun shared(context: Context): RoomStore = shared ?: synchronized(this) {
            shared ?: RoomStore(context.applicationContext).also { shared = it }
        }
    }
}
