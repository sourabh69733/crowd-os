package org.freegram.app.relay

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.freegram.app.moderation.Discover
import org.freegram.app.moderation.HideList
import org.freegram.app.moderation.SuggestList
import org.freegram.app.moderation.PrivateMessages
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Likes
import org.freegram.app.protocol.Replies
import org.json.JSONObject
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.ProfileEvent
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed interface FetchResult {
    data class Found(val event: BulletinEvent) : FetchResult
    data object NotFound : FetchResult
    data class Failed(val reason: String) : FetchResult
}

data class AuthorFetchResult(val events: List<BulletinEvent>, val status: String)

class RelayClient(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).build()) {

    suspend fun fetchAuthors(
        relay: String,
        authors: List<String>,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        since: Long? = null,
        until: Long? = null,
    ): AuthorFetchResult {
        require(relay.startsWith("wss://"))
        val subscription = "freegram-feed-${UUID.randomUUID()}"
        val request = AuthorRelayFrames.request(subscription, authors, since, until)
        val requestedAuthors = authors.toSet()
        val events = LinkedHashMap<String, BulletinEvent>()
        val status = withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<String> { continuation ->
                var frames = 0
                val socket = client.newWebSocket(Request.Builder().url(relay).build(), object : WebSocketListener() {
                    private fun finish(webSocket: WebSocket, result: String) {
                        if (continuation.isActive) continuation.resume(result)
                        webSocket.send("[\"CLOSE\",\"$subscription\"]")
                        webSocket.close(1000, null)
                    }

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (!webSocket.send(request)) finish(webSocket, "Could not send request")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (!continuation.isActive) return
                        if (++frames > 200) return finish(webSocket, "Frame limit reached")
                        when (val frame = AuthorRelayFrames.parse(text, subscription, requestedAuthors, nowSeconds)) {
                            is AuthorFrame.Verified -> {
                                val reachedLimit = synchronized(events) {
                                    events.putIfAbsent(frame.event.id, frame.event)
                                    events.size >= AuthorRelayFrames.LIMIT
                                }
                                if (reachedLimit) finish(webSocket, "Event limit reached")
                            }
                            is AuthorFrame.Closed -> finish(webSocket, "Relay closed: ${frame.reason}")
                            AuthorFrame.End -> finish(webSocket, "Complete")
                            AuthorFrame.Ignore -> Unit
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (continuation.isActive) continuation.resume(response?.let { "HTTP ${it.code}" } ?: "Network error")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (continuation.isActive) continuation.resume("Connection closed")
                    }
                })
                continuation.invokeOnCancellation { socket.cancel() }
            }
        } ?: "Timed out"
        return AuthorFetchResult(synchronized(events) { events.values.toList() }, status)
    }

    /** Newest hide list per maintainer from [relay]; each is signature-checked before it is returned. */
    suspend fun fetchHideLists(relay: String, maintainers: List<String>): AuthorFetchResult {
        require(maintainers.isNotEmpty())
        val wanted = maintainers.toSet()
        val filter = JSONObject().put("kinds", JSONArray().put(HideList.KIND)).put("authors", JSONArray(maintainers)).put("limit", maintainers.size)
        return fetchSigned(relay, filter, HideList.MAX_BYTES, maintainers.size * 4) { it.pubkey in wanted && HideList.of(it) != null }
    }

    /** Profile names (kind 0) of [authors]; each is signature-checked. */
    suspend fun fetchProfiles(relay: String, authors: List<String>): AuthorFetchResult {
        require(authors.isNotEmpty())
        val wanted = authors.toSet()
        val filter = JSONObject().put("kinds", JSONArray().put(ProfileEvent.KIND)).put("authors", JSONArray(authors)).put("limit", authors.size * 2)
        return fetchSigned(relay, filter, ProfileEvent.MAX_BYTES, authors.size * 3) { it.pubkey in wanted && it.kind == ProfileEvent.KIND }
    }

    /** Newest public posts tagged for Discover, each signature-checked and not dated in the future. */
    suspend fun fetchDiscover(relay: String, limit: Int = 50, nowSeconds: Long = System.currentTimeMillis() / 1000): AuthorFetchResult {
        val filter = JSONObject().put("kinds", JSONArray().put(1)).put("#t", JSONArray().put(Discover.TAG)).put("limit", limit)
        return fetchSigned(relay, filter, 4096, limit) {
            Nip01Protocol.verifyBulletin(it) && Discover.isTagged(it) && it.createdAt <= nowSeconds + 600
        }
    }

    /** Newest Discover suggestion list per maintainer; each is signature-checked. */
    suspend fun fetchSuggestions(relay: String, maintainers: List<String>): AuthorFetchResult {
        require(maintainers.isNotEmpty())
        val wanted = maintainers.toSet()
        val filter = JSONObject().put("kinds", JSONArray().put(SuggestList.KIND)).put("authors", JSONArray(maintainers))
            .put("#d", JSONArray().put(SuggestList.D_TAG)).put("limit", maintainers.size * 2)
        return fetchSigned(relay, filter, SuggestList.MAX_BYTES, maintainers.size * 4) { it.pubkey in wanted && SuggestList.of(it) != null }
    }

    /** Replies (kind 1) and likes (kind 7) pointing at [postIds]; each is signature-checked. */
    suspend fun fetchThreads(relay: String, postIds: List<String>, limit: Int = 500): AuthorFetchResult {
        require(postIds.isNotEmpty())
        val wanted = postIds.toSet()
        val filter = JSONObject().put("kinds", JSONArray().put(1).put(Likes.KIND)).put("#e", JSONArray(postIds)).put("limit", limit)
        return fetchSigned(relay, filter, 4096, limit) { event ->
            when (event.kind) {
                1 -> Nip01Protocol.verifyBulletin(event) && Replies.rootOf(event) in wanted
                Likes.KIND -> Likes.likedPost(event) in wanted
                else -> false
            }
        }
    }

    /** Gift-wrapped private messages addressed to [recipient]; only the outer signature is checked here. */
    suspend fun fetchWraps(relay: String, recipient: String, limit: Int = 100): AuthorFetchResult {
        val filter = JSONObject().put("kinds", JSONArray().put(PrivateMessages.WRAP_KIND))
            .put("#p", JSONArray().put(recipient)).put("limit", limit)
        return fetchSigned(relay, filter, PrivateMessages.MAX_WRAP_BYTES, limit) { it.kind == PrivateMessages.WRAP_KIND }
    }

    private suspend fun fetchSigned(
        relay: String,
        filter: JSONObject,
        maxBytes: Int,
        maxEvents: Int,
        accept: (BulletinEvent) -> Boolean,
    ): AuthorFetchResult {
        require(relay.startsWith("wss://"))
        val subscription = "freegram-${UUID.randomUUID()}"
        val request = JSONArray().put("REQ").put(subscription).put(filter).toString()
        val events = mutableListOf<BulletinEvent>()
        val status = withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<String> { continuation ->
                val socket = client.newWebSocket(Request.Builder().url(relay).build(), object : WebSocketListener() {
                    private fun finish(webSocket: WebSocket, result: String) {
                        if (continuation.isActive) continuation.resume(result)
                        webSocket.close(1000, null)
                    }
                    override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(request) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.length > maxBytes + 1024) return
                        try {
                            val message = JSONArray(text)
                            if (message.optString(1) != subscription) return
                            when (message.optString(0)) {
                                "EOSE" -> finish(webSocket, "Complete")
                                "CLOSED" -> finish(webSocket, "Relay closed: ${message.optString(2).take(120)}")
                                "EVENT" -> {
                                    val event = Nip01Protocol.fromJson(message.getJSONObject(2).toString(), maxBytes)
                                    if (Nip01Protocol.verifySigned(event, maxBytes) && accept(event)) {
                                        val full = synchronized(events) { if (events.size < maxEvents) events += event; events.size >= maxEvents }
                                        if (full) finish(webSocket, "Event limit reached")
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (continuation.isActive) continuation.resume(response?.let { "HTTP ${it.code}" } ?: "Network error")
                    }
                })
                continuation.invokeOnCancellation { socket.cancel() }
            }
        } ?: "Timed out"
        return AuthorFetchResult(synchronized(events) { events.toList() }, status)
    }

    suspend fun publish(relay: String, event: BulletinEvent): String {
        require(relay.startsWith("wss://"))
        require(
            Nip01Protocol.verifyBulletin(event) ||
                (HideList.of(event) != null && Nip01Protocol.verifySigned(event, HideList.MAX_BYTES)) ||
                (event.kind == PrivateMessages.WRAP_KIND && Nip01Protocol.verifySigned(event, PrivateMessages.MAX_WRAP_BYTES)) ||
                (SuggestList.of(event) != null && Nip01Protocol.verifySigned(event, SuggestList.MAX_BYTES)) ||
                Likes.likedPost(event) != null || Likes.isUnlike(event) ||
                ProfileEvent.nameOf(event) != null
        )
        return withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { continuation ->
                val socket = client.newWebSocket(Request.Builder().url(relay).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("[\"EVENT\",${Nip01Protocol.toJson(event)}]")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val message = JSONArray(text)
                            if (message.optString(0) == "OK" && message.optString(1) == event.id && continuation.isActive) {
                                val accepted = message.optBoolean(2)
                                val reason = message.optString(3).take(120)
                                continuation.resume(if (accepted) "Accepted" else "Rejected: $reason")
                                webSocket.close(1000, null)
                            }
                        } catch (_: Exception) {
                            // An unrelated or malformed relay frame does not establish delivery.
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (continuation.isActive) continuation.resume(response?.let { "HTTP ${it.code}" } ?: "Network error")
                    }
                })
                continuation.invokeOnCancellation { socket.cancel() }
            }
        } ?: "Timed out"
    }

    suspend fun fetch(relay: String, eventId: String): FetchResult {
        require(relay.startsWith("wss://"))
        val subscription = "freegram-${UUID.randomUUID()}"
        val request = RelayFrames.request(subscription, eventId)
        return withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<FetchResult> { continuation ->
                val socket = client.newWebSocket(Request.Builder().url(relay).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (!webSocket.send(request)) {
                            if (continuation.isActive) continuation.resume(FetchResult.Failed("Could not send request"))
                            webSocket.close(1000, null)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val result = when (val frame = RelayFrames.parse(text, subscription, eventId)) {
                            is RelayFrame.Verified -> FetchResult.Found(frame.event)
                            is RelayFrame.Closed -> FetchResult.Failed("Relay closed: ${frame.reason}")
                            RelayFrame.End -> FetchResult.NotFound
                            RelayFrame.Ignore -> return
                        }
                        if (continuation.isActive) continuation.resume(result)
                        webSocket.close(1000, null)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (continuation.isActive) continuation.resume(FetchResult.Failed("Network error"))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (continuation.isActive) continuation.resume(FetchResult.Failed("Connection closed"))
                    }
                })
                continuation.invokeOnCancellation { socket.cancel() }
            }
        } ?: FetchResult.Failed("Timed out")
    }
}
