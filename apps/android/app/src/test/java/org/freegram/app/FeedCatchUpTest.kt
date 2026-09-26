package org.freegram.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.freegram.app.feed.FollowedFeedSync
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.relay.AuthorFetchResult
import org.freegram.app.relay.AuthorRelayFrames
import org.freegram.app.store.AuthorState
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
class FeedCatchUpTest {
    private val relays = listOf("wss://one.example", "wss://two.example")
    private val now = 1_700_100_000L
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var store: RoomStore

    @Before fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        name = "catchup-${UUID.randomUUID()}"
        context.getSharedPreferences("freegram_local", Context.MODE_PRIVATE).edit().clear().commit()
        store = RoomStore(context, name)
        store.initialize()
    }

    @After fun tearDown() { store.close(); context.deleteDatabase(name) }

    private fun post(key: Int, at: Long) = Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = key.toByte() }, "post $at", at)

    /** A relay holding [posts] that honours since/until/limit like NIP-01, and records each query. */
    private class FakeRelay(var posts: List<BulletinEvent>) {
        val queries = mutableListOf<Pair<Long?, Long?>>()
        suspend fun fetch(authors: List<String>, since: Long?, until: Long?): AuthorFetchResult {
            synchronized(queries) { queries += since to until }
            val page = posts.filter { it.pubkey in authors && (since == null || it.createdAt >= since) && (until == null || it.createdAt <= until) }
                .sortedByDescending { it.createdAt }.take(AuthorRelayFrames.LIMIT)
            return AuthorFetchResult(page, if (page.size == AuthorRelayFrames.LIMIT) "Event limit reached" else "Complete")
        }
    }

    private fun sync(relay: FakeRelay, down: Boolean = false) =
        FollowedFeedSync(store) { url, authors, _, since, until ->
            if (url == relays[1] || down) AuthorFetchResult(emptyList(), "Network error") else relay.fetch(authors, since, until)
        }

    @Test fun laterRefreshFetchesOnlyNewPostsAndPagesBackWithinLimit() = runBlocking {
        val authors = (10 until 14).map { post(it, 0).pubkey }
        authors.forEach { store.setAuthorState(it, AuthorState.FOLLOWING) }
        val relay = FakeRelay((0 until 8).map { post(10 + it % 4, now - 10_000 + it) })

        sync(relay).refresh(relays, now)
        assertEquals(listOf<Pair<Long?, Long?>>(null to null), relay.queries)
        assertEquals(now - 10_000 + 7, store.feedSince(relays[0]))
        assertNull(store.feedSince(relays[1])) // failed relay keeps no mark

        // While offline, 70 new posts appeared: more than one batch.
        relay.posts = relay.posts + (0 until 70).map { post(10 + it % 4, now - 5_000 + it) }
        relay.queries.clear()
        val result = sync(relay).refresh(relays, now)
        assertEquals(now - 10_000 + 7 - 600, relay.queries[0].first)
        assertEquals(2, relay.queries.size)
        assertEquals("Complete", result[0].status)
        assertEquals(78, store.savedEvents().size)
    }

    @Test fun catchUpStopsAfterThreePagesAndSaysSo() = runBlocking {
        val authors = (10 until 20).map { post(it, 0).pubkey }
        authors.forEach { store.setAuthorState(it, AuthorState.FOLLOWING) }
        val relay = FakeRelay(listOf(post(10, now - 20_000)))
        sync(relay).refresh(relays, now)
        relay.posts = relay.posts + (0 until 200).map { post(10 + it % 10, now - 10_000 + it) }
        relay.queries.clear()
        val result = sync(relay).refresh(relays, now)
        assertEquals(3, relay.queries.size)
        assertTrue(result[0].status.contains("more missed posts than fetched"))
    }

    @Test fun followingSomeoneNewResetsTheMark() = runBlocking {
        val first = post(10, now - 100)
        store.setAuthorState(first.pubkey, AuthorState.FOLLOWING)
        val relay = FakeRelay(listOf(first))
        sync(relay).refresh(relays, now)
        assertNotNull(store.feedSince(relays[0]))
        store.setAuthorState(post(11, 0).pubkey, AuthorState.FOLLOWING)
        assertNull(store.feedSince(relays[0]))
    }

    @Test fun requestCarriesSinceAndUntil() {
        val key = post(10, 0).pubkey
        val filter = JSONArray(AuthorRelayFrames.request("s", listOf(key), since = 5, until = 9)).getJSONObject(2)
        assertEquals(5, filter.getLong("since"))
        assertEquals(9, filter.getLong("until"))
        assertFalse(JSONArray(AuthorRelayFrames.request("s", listOf(key))).getJSONObject(2).has("since"))
    }
}
