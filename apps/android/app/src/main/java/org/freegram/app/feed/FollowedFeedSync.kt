package org.freegram.app.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.relay.AuthorFetchResult
import org.freegram.app.relay.AuthorRelayFrames
import org.freegram.app.store.AuthorState
import org.freegram.app.store.RoomStore

data class RelayRefreshResult(val relay: String, val status: String, val verifiedEvents: Int)

/**
 * Manual followed-author refresh. After a completed refresh, the next one asks each relay only for posts
 * since then (with 10 minutes of overlap for clock skew) and pages back up to [MAX_PAGES] batches.
 */
class FollowedFeedSync(
    private val store: RoomStore,
    private val fetch: suspend (relay: String, authors: List<String>, now: Long, since: Long?, until: Long?) -> AuthorFetchResult,
) {
    constructor(store: RoomStore, relayClient: org.freegram.app.relay.RelayClient) :
        this(store, { relay, authors, now, since, until -> relayClient.fetchAuthors(relay, authors, now, since, until) })

    suspend fun refresh(relays: List<String>, nowSeconds: Long = System.currentTimeMillis() / 1000): List<RelayRefreshResult> {
        require(relays.size == 2 && relays.distinct().size == 2 && relays.all { it.startsWith("wss://") }) { "Use two distinct wss:// relays" }
        val authors = store.authorPolicies().filter { it.state == AuthorState.FOLLOWING }.map { it.pubkey }
        require(authors.isNotEmpty()) { "Follow an author before refreshing" }
        val fetched = coroutineScope {
            relays.map { relay -> async(Dispatchers.IO) { catchUp(relay, authors, nowSeconds) } }.awaitAll()
        }
        return relays.zip(fetched).map { (relay, result) ->
            val (events, status, truncated) = result
            var full = false
            var older = 0
            for (event in events.sortedByDescending { it.createdAt }) {
                try {
                    if (!store.saveReceivedEvent(event)) older++
                } catch (_: IllegalArgumentException) {
                    // An author may have been blocked while the relay was responding.
                } catch (_: IllegalStateException) {
                    full = true
                    break
                }
            }
            val completed = status == "Complete" || status == LIMIT_STATUS
            if (completed && !full) {
                val newest = events.maxOfOrNull { it.createdAt }
                val previous = store.feedSince(relay)
                if (newest != null || previous == null) store.setFeedSince(relay, maxOf(newest ?: 0, previous ?: 0))
            }
            val shown = if (status == LIMIT_STATUS) "Complete" else status
            val notes = listOfNotNull(
                "local store full of posts waiting for relays".takeIf { full },
                "$older older posts not kept (store full)".takeIf { older > 0 },
                "more missed posts than fetched; oldest skipped".takeIf { truncated },
            )
            RelayRefreshResult(relay, (listOf(shown) + notes).joinToString("; "), events.size)
        }
    }

    private data class CatchUp(val events: List<BulletinEvent>, val status: String, val truncated: Boolean)

    private suspend fun catchUp(relay: String, authors: List<String>, now: Long): CatchUp {
        val since = store.feedSince(relay)?.let { maxOf(0, it - CLOCK_OVERLAP_SECONDS) }
        val events = LinkedHashMap<String, BulletinEvent>()
        var until: Long? = null
        var pages = 0
        var status: String
        while (true) {
            val page = try { fetch(relay, authors, now, since, until) } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                AuthorFetchResult(emptyList(), "Network error")
            }
            status = page.status
            val added = page.events.count { events.putIfAbsent(it.id, it) == null }
            pages++
            // First refresh takes only the newest batch; later ones page back to `since`.
            if (status != LIMIT_STATUS || since == null || added == 0 || pages >= MAX_PAGES) break
            until = page.events.minOf { it.createdAt }
        }
        return CatchUp(events.values.toList(), status, since != null && status == LIMIT_STATUS && pages >= MAX_PAGES)
    }

    companion object {
        const val MAX_PAGES = 3
        private const val CLOCK_OVERLAP_SECONDS = 600L
        private const val LIMIT_STATUS = "Event limit reached"
    }
}
