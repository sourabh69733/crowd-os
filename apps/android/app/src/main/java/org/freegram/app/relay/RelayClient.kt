package org.freegram.app.relay

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed interface FetchResult {
    data class Found(val event: BulletinEvent) : FetchResult
    data object NotFound : FetchResult
    data class Failed(val reason: String) : FetchResult
}

class RelayClient(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).build()) {

    suspend fun publish(relay: String, event: BulletinEvent): String {
        require(relay.startsWith("wss://"))
        require(Nip01Protocol.verifyBulletin(event))
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

                    override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                        if (continuation.isActive) continuation.resume("Network error")
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

                    override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
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
