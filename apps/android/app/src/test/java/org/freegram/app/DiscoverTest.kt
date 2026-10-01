package org.freegram.app

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.freegram.app.moderation.Discover
import org.freegram.app.moderation.SuggestList
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.relay.RelayClient
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class DiscoverTest {
    private val secret = ByteArray(32).also { it[31] = 3 }
    private val other = ByteArray(32).also { it[31] = 4 }

    @Test fun suggestionListRoundTripsAndRejectsOtherSets() {
        val person = Nip01Protocol.signBulletin(other, "x", 1).pubkey
        val list = SuggestList("ignored", 10, setOf(person))
        val signed = Nip01Protocol.signEvent(secret, SuggestList.KIND, "", 10, list.toTags())
        val parsed = SuggestList.of(signed)!!
        assertEquals(signed.pubkey, parsed.maintainer)
        assertEquals(setOf(person), parsed.people)
        // Another app's follow set (different d tag) is not ours.
        val foreign = Nip01Protocol.signEvent(secret, SuggestList.KIND, "", 10, arrayOf(arrayOf("d", "friends"), arrayOf("p", person)))
        assertNull(SuggestList.of(foreign))
        // Uppercase or short keys are dropped.
        val messy = Nip01Protocol.signEvent(secret, SuggestList.KIND, "", 10, arrayOf(arrayOf("d", SuggestList.D_TAG), arrayOf("p", person.uppercase()), arrayOf("p", "ab")))
        assertTrue(SuggestList.of(messy)!!.people.isEmpty())
    }

    @Test fun fetchesOnlyVerifiedTaggedPostsAndAsksForTheTag() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val now = 1_700_000_000L
        val tagged = Nip01Protocol.signBulletin(secret, "Tagged", now, arrayOf(Discover.tag()))
        val untagged = Nip01Protocol.signBulletin(secret, "Untagged", now)
        val future = Nip01Protocol.signBulletin(other, "Future", now + 3600, arrayOf(Discover.tag()))
        val forged = tagged.copy(content = "Changed")
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = JSONArray(text)
                if (request.getString(0) != "REQ") return
                assertEquals(Discover.TAG, request.getJSONObject(2).getJSONArray("#t").getString(0))
                val sub = request.getString(1)
                listOf(untagged, future, forged, tagged).forEach { webSocket.send("[\"EVENT\",\"$sub\",${Nip01Protocol.toJson(it)}]") }
                webSocket.send("[\"EOSE\",\"$sub\"]")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        server.start()
        try {
            val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
            val result = RelayClient(client).fetchDiscover("wss://localhost:${server.port}/", nowSeconds = now)
            assertEquals("Complete", result.status)
            assertEquals(listOf(tagged), result.events)
        } finally {
            server.shutdown()
        }
    }
}
