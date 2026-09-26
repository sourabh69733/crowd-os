package org.freegram.app

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.relay.RelayClient
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class AuthorRelayClientTest {
    @Test fun returnsOnlyVerifiedRequestedEventsAtEndOfStoredBatch() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        val wanted = Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = 3 }, "Wanted", 1_700_000_000)
        val other = Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = 4 }, "Other", 1_700_000_000)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = JSONArray(text)
                if (request.getString(0) == "CLOSE") {
                    webSocket.close(1000, null)
                    return
                }
                if (request.getString(0) != "REQ") return
                assertEquals(wanted.pubkey, request.getJSONObject(2).getJSONArray("authors").getString(0))
                val subscription = request.getString(1)
                webSocket.send("[\"EVENT\",\"$subscription\",${Nip01Protocol.toJson(other)}]")
                webSocket.send("[\"EVENT\",\"$subscription\",${Nip01Protocol.toJson(wanted)}]")
                webSocket.send("[\"EVENT\",\"$subscription\",${Nip01Protocol.toJson(wanted)}]")
                webSocket.send("[\"EOSE\",\"$subscription\"]")
            }
        }))
        server.start()
        try {
            val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
            val result = RelayClient(client).fetchAuthors("wss://localhost:${server.port}/", listOf(wanted.pubkey), 1_700_000_000)
            assertEquals("Complete", result.status)
            assertEquals(listOf(wanted), result.events)
        } finally {
            server.shutdown()
        }
    }
}
