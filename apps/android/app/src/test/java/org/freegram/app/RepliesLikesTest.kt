package org.freegram.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.freegram.app.protocol.Likes
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.Replies
import org.freegram.app.store.RoomStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepliesLikesTest {
    private val alice = ByteArray(32).also { it[31] = 3 }
    private val bob = ByteArray(32).also { it[31] = 4 }
    private val carol = ByteArray(32).also { it[31] = 5 }
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databaseName = "freegram-test-${UUID.randomUUID()}"

    @After fun tearDown() { context.deleteDatabase(databaseName) }

    @Test fun repliesPointAtTheirRootPost() {
        val post = Nip01Protocol.signBulletin(alice, "Water at Gate 3?", 100)
        assertNull(Replies.rootOf(post))

        val reply = Nip01Protocol.signBulletin(bob, "Yes, open", 110, Replies.tags(post, post.pubkey))
        assertEquals(post.id, Replies.rootOf(reply))
        assertTrue(reply.tags.any { it[0] == "p" && it[1] == post.pubkey })

        // A reply to a reply stays in the same thread and names both authors.
        val nested = Nip01Protocol.signBulletin(carol, "Still open?", 120, Replies.tags(reply, post.pubkey))
        assertEquals(post.id, Replies.rootOf(nested))
        assertTrue(nested.tags.any { it.contentEquals(arrayOf("e", reply.id, "", "reply")) })
        assertEquals(setOf(reply.pubkey, post.pubkey), nested.tags.filter { it[0] == "p" }.map { it[1] }.toSet())
    }

    @Test fun otherAppsReplyFormatsAreUnderstood() {
        val root = "a".repeat(64)
        val parent = "b".repeat(64)
        // Older positional style: first e tag is the root.
        val positional = Nip01Protocol.signBulletin(bob, "x", 1, arrayOf(arrayOf("e", root), arrayOf("e", parent)))
        assertEquals(root, Replies.rootOf(positional))
        // A mention is not a reply.
        val mention = Nip01Protocol.signBulletin(bob, "see this", 1, arrayOf(arrayOf("e", root, "", "mention")))
        assertNull(Replies.rootOf(mention))
    }

    @Test fun likesAreVerifiedAndDislikesIgnored() {
        val post = Nip01Protocol.signBulletin(alice, "Post", 100)
        val like = Likes.sign(bob, post, 110)
        assertEquals(post.id, Likes.likedPost(like))
        assertNull(Likes.likedPost(like.copy(content = "-")))
        val dislike = Nip01Protocol.signEvent(bob, Likes.KIND, "-", 110, arrayOf(arrayOf("e", post.id)))
        assertNull(Likes.likedPost(dislike))
        val unlike = Likes.signUnlike(bob, like, 120)
        assertTrue(Likes.isUnlike(unlike))
        assertEquals(like.id, unlike.tags.first { it[0] == "e" }[1])
    }

    @Test fun storeCountsOneLikePerPersonAndTracksOwnUnsent() = runBlocking {
        val store = RoomStore(context, databaseName)
        val post = Nip01Protocol.signBulletin(alice, "Post", 100)
        assertTrue(store.saveLike(Likes.sign(bob, post, 110)))
        assertTrue(store.saveLike(Likes.sign(bob, post, 111)))
        val mine = Likes.sign(carol, post, 112)
        assertTrue(store.saveLike(mine, mine = true))
        assertFalse(store.saveLike(post))

        assertEquals(setOf(post.pubkey.let { Likes.sign(bob, post, 1).pubkey }, mine.pubkey), store.likes()[post.id])
        assertEquals(listOf(mine.id), store.unsentLikes().map { it.id })
        store.markLikeSent(mine.id)
        assertTrue(store.unsentLikes().isEmpty())
        store.deleteLike(mine.id)
        assertNull(store.likeBy(post.id, mine.pubkey))
        store.close()
    }
}
