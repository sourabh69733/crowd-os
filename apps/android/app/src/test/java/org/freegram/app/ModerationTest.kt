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
import org.freegram.app.moderation.HideList
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.relay.RelayClient
import org.freegram.app.store.RoomStore
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModerationTest {
    private val now = 1_700_000_000L
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var store: RoomStore

    @Before fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        name = "mod-${UUID.randomUUID()}"
        store = RoomStore(context, name)
        store.initialize()
    }

    @After fun tearDown() { store.close(); context.deleteDatabase(name) }

    private fun key(k: Int) = ByteArray(32).also { it[31] = k.toByte() }
    private fun pub(k: Int) = Nip01Protocol.signBulletin(key(k), "x", 0).pubkey
    private fun post(k: Int, text: String) = Nip01Protocol.signBulletin(key(k), text, now - 60)
    private fun list(maintainer: Int, at: Long, posts: Set<String> = emptySet(), authors: Set<String> = emptySet()): BulletinEvent =
        Nip01Protocol.signEvent(key(maintainer), HideList.KIND, "", at, HideList(pub(maintainer), at, posts, authors).toTags())

    @Test fun followedMaintainersListHidesPostsAndAuthors() = runBlocking {
        val spam = post(5, "spam"); val abuser = post(6, "abuse"); val fine = post(7, "fine")
        listOf(spam, abuser, fine).forEach { store.saveReceivedEvent(it) }
        store.setMaintainer(pub(20), true)
        assertTrue(store.saveHideList(list(20, now, posts = setOf(spam.id), authors = setOf(abuser.pubkey))))

        assertEquals(listOf(fine), store.savedEvents())
        assertEquals(2, store.hiddenCount())
        assertEquals(listOf(fine), store.nearbyOffers(0, Long.MAX_VALUE, 6, 10).map { it.first })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.saveReceivedEvent(post(6, "more abuse")) } }

        store.setMaintainer(pub(20), false)
        assertEquals(3, store.savedEvents().size)
        store.setMaintainer(pub(20), true)
        store.removeMaintainer(pub(20))
        assertEquals(3, store.savedEvents().size)
        assertNull(store.hideList(pub(20)))
    }

    @Test fun onlyNewerSignedListsFromFollowedMaintainersCount() = runBlocking {
        val target = post(5, "target")
        store.saveReceivedEvent(target)
        assertFalse(store.saveHideList(list(21, now, posts = setOf(target.id)))) // not followed
        store.setMaintainer(pub(21), true)
        val forged = list(21, now, posts = setOf(target.id)).let { it.copy(tags = it.tags + arrayOf(arrayOf("e", "ab".repeat(32)))) }
        assertFalse(store.saveHideList(forged))
        assertFalse(store.saveHideList(Nip01Protocol.signEvent(key(21), 1, "", now, emptyArray()))) // wrong kind
        assertTrue(store.saveHideList(list(21, now, posts = setOf(target.id))))
        assertFalse(store.saveHideList(list(21, now - 10))) // older list cannot undo a newer one
        assertTrue(store.savedEvents().isEmpty())
        assertTrue(store.saveHideList(list(21, now + 10))) // newer empty list unhides
        assertEquals(listOf(target), store.savedEvents())
    }

    @Test fun ownPostsMaySaveButOthersHiddenPostsMayNot() = runBlocking {
        store.setMaintainer(pub(22), true)
        val mine = post(3, "my post")
        store.saveHideList(list(22, now, authors = setOf(mine.pubkey)))
        store.saveEvent(mine, listOf("wss://one.example", "wss://two.example")) // outbox saves still work
        assertTrue(store.savedEvents().isEmpty()) // but the followed list still hides it here
    }

    @Test fun hideListParsingIgnoresJunkAndCapsSize() {
        val e = list(20, now, posts = setOf("ab".repeat(32)))
        val junk = e.copy(tags = e.tags + arrayOf(arrayOf("e", "short"), arrayOf("t", "x"), arrayOf("p")))
        assertEquals(setOf("ab".repeat(32)), HideList.of(junk)!!.posts)
        assertNull(HideList.of(e.copy(tags = Array(HideList.MAX_ENTRIES + 1) { arrayOf("e", "ab".repeat(32)) })))
    }

    @Test fun maintainerCountIsCapped() = runBlocking {
        (30 until 35).forEach { store.setMaintainer(pub(it), true) }
        assertThrows(IllegalStateException::class.java) { runBlocking { store.setMaintainer(pub(35), true) } }
        store.setMaintainer(pub(30), false) // existing ones can still change
    }

    @Test fun relayFetchReturnsOnlyVerifiedListsFromRequestedMaintainers() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val big = list(20, now, posts = (0 until 1500).map { "%064x".format(it) }.toSet())
        val stranger = list(23, now)
        val forged = big.copy(createdAt = now + 5)
        val server = MockWebServer().apply { useHttps(serverCerts.sslSocketFactory(), false) }
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val req = JSONArray(text)
                if (req.getString(0) != "REQ") return
                val sub = req.getString(1)
                listOf(big, stranger, forged).forEach { webSocket.send("[\"EVENT\",\"$sub\",${Nip01Protocol.toJson(it)}]") }
                webSocket.send("[\"EOSE\",\"$sub\"]")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        server.start()
        try {
            val client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
            val result = RelayClient(client).fetchHideLists("wss://localhost:${server.port}/", listOf(pub(20)))
            assertEquals("Complete", result.status)
            assertEquals(listOf(big), result.events)
        } finally { server.shutdown() }
    }
}
