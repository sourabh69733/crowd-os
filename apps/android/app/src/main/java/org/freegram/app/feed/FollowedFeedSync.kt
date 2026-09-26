package org.freegram.app.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.freegram.app.relay.RelayClient
import org.freegram.app.relay.AuthorFetchResult
import org.freegram.app.store.AuthorState
import org.freegram.app.store.RoomStore

data class RelayRefreshResult(val relay: String, val status: String, val verifiedEvents: Int)

class FollowedFeedSync(private val store: RoomStore, private val relayClient: RelayClient) {
    suspend fun refresh(relays: List<String>, nowSeconds: Long = System.currentTimeMillis() / 1000): List<RelayRefreshResult> {
        require(relays.size == 2 && relays.distinct().size == 2 && relays.all { it.startsWith("wss://") }) { "Use two distinct wss:// relays" }
        val authors = store.authorPolicies().filter { it.state == AuthorState.FOLLOWING }.map { it.pubkey }
        require(authors.isNotEmpty()) { "Follow an author before refreshing" }
        val fetched = coroutineScope {
            relays.map { relay ->
                async(Dispatchers.IO) {
                    try { relayClient.fetchAuthors(relay, authors, nowSeconds) }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        AuthorFetchResult(emptyList(), "Network error")
                    }
                }
            }.awaitAll()
        }
        return relays.zip(fetched).map { (relay, result) ->
            var full = false
            for (event in result.events) {
                try {
                    store.saveReceivedEvent(event)
                } catch (_: IllegalArgumentException) {
                    // An author may have been blocked while the relay was responding.
                } catch (_: IllegalStateException) {
                    full = true
                    break
                }
            }
            RelayRefreshResult(relay, if (full) "${result.status}; local store full" else result.status, result.events.size)
        }
    }
}
