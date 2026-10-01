package org.freegram.app

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.relay.FetchResult
import org.freegram.app.relay.RelayClient
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class RelayBridgeTest {
    @Test fun carrierPublishesAuthorsUnchangedEventAndReaderFetchesIt() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        val event = Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = 3 }, "Bridge test", 1_700_000_000)
        var carriedWire = ""
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JSONArray(text)
                assertEquals("EVENT", frame.getString(0))
                carriedWire = frame.getJSONObject(1).toString()
                webSocket.send("[\"OK\",\"${event.id}\",true,\"\"]")
                webSocket.close(1000, null)
            }
        }))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = JSONArray(text)
                assertEquals("REQ", request.getString(0))
                assertEquals(event.id, request.getJSONObject(2).getJSONArray("ids").getString(0))
                webSocket.send("[\"EVENT\",\"${request.getString(1)}\",$carriedWire]")
                webSocket.send("[\"EOSE\",\"${request.getString(1)}\"]")
                webSocket.close(1000, null)
            }
        }))
        server.start()
        try {
            val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
            val relay = RelayClient(client)
            val url = "wss://localhost:${server.port}/"
            assertEquals("Accepted", relay.publish(url, event))
            assertEquals(event, (relay.fetch(url, event.id) as FetchResult.Found).event)
            assertEquals(event.sig, Nip01Protocol.fromJson(carriedWire).sig)
        } finally {
            server.shutdown()
        }
    }
}
