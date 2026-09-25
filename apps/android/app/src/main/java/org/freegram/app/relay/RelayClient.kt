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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class RelayClient {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).build()

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
}
