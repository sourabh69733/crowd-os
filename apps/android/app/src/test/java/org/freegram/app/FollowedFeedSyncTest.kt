package org.freegram.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.freegram.app.feed.FollowedFeedSync
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.relay.RelayClient
import org.freegram.app.store.AuthorState
import org.freegram.app.store.RoomStore
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FollowedFeedSyncTest {
    @Test fun twoRelaysYieldOneVerifiedSavedFeedEvent() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "feed-test-${UUID.randomUUID()}"
        context.getSharedPreferences("freegram_local", Context.MODE_PRIVATE).edit().clear().commit()
        val store = RoomStore(context, name)
        store.initialize()
        val event = Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = 3 }, "Shared post", 1_700_000_000)
        store.setAuthorState(event.pubkey, AuthorState.FOLLOWING)
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val servers = List(2) { MockWebServer().also { it.useHttps(serverCertificates.sslSocketFactory(), false) } }
        servers.forEach { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = JSONArray(text)
                    if (frame.getString(0) == "CLOSE") {
                        webSocket.close(1000, null)
                        return
                    }
                    if (frame.getString(0) != "REQ") return
                    val subscription = frame.getString(1)
                    webSocket.send("[\"EVENT\",\"$subscription\",${Nip01Protocol.toJson(event)}]")
                    webSocket.send("[\"EOSE\",\"$subscription\"]")
                }
            }))
            server.start()
        }
        try {
            val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
            val result = FollowedFeedSync(store, RelayClient(client)).refresh(servers.map { "wss://localhost:${it.port}/" }, 1_700_000_000)
            assertEquals(listOf("Complete", "Complete"), result.map { it.status })
            assertEquals(listOf(event), store.feedEvents())
            assertTrue(store.deliveryTargets(event.id).isEmpty())
        } finally {
            store.close()
            context.deleteDatabase(name)
            servers.forEach { it.shutdown() }
        }
    }
}
