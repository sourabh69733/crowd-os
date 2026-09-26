package org.freegram.app

import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.relay.AuthorFrame
import org.freegram.app.relay.AuthorRelayFrames
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class AuthorRelayFramesTest {
    private val author = Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = 3 }, "A post", 1_700_000_000)
    private val other = Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = 4 }, "Another post", 1_700_000_000)

    @Test fun requestIsBoundedToFollowedKindOneAuthors() {
        val frame = JSONArray(AuthorRelayFrames.request("feed-1", listOf(author.pubkey)))
        assertEquals("REQ", frame.getString(0))
        assertEquals("feed-1", frame.getString(1))
        val filter = frame.getJSONObject(2)
        assertEquals(author.pubkey, filter.getJSONArray("authors").getString(0))
        assertEquals(1, filter.getJSONArray("kinds").getInt(0))
        assertEquals(50, filter.getInt("limit"))
    }

    @Test fun parserAcceptsOnlySignedMatchingNonFutureEvents() {
        val requested = setOf(author.pubkey)
        val good = "[\"EVENT\",\"feed-1\",${Nip01Protocol.toJson(author)}]"
        assertEquals(AuthorFrame.Verified(author), AuthorRelayFrames.parse(good, "feed-1", requested, 1_700_000_000))
        assertEquals(AuthorFrame.Ignore, AuthorRelayFrames.parse(good, "other-sub", requested, 1_700_000_000))
        assertEquals(AuthorFrame.Ignore, AuthorRelayFrames.parse("[\"EVENT\",\"feed-1\",${Nip01Protocol.toJson(other)}]", "feed-1", requested, 1_700_000_000))
        assertEquals(AuthorFrame.Ignore, AuthorRelayFrames.parse(good.replace("A post", "Altered"), "feed-1", requested, 1_700_000_000))
        val future = Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = 3 }, "Future post", 1_700_000_601)
        assertEquals(AuthorFrame.Ignore, AuthorRelayFrames.parse("[\"EVENT\",\"feed-1\",${Nip01Protocol.toJson(future)}]", "feed-1", requested, 1_700_000_000))
        assertEquals(AuthorFrame.End, AuthorRelayFrames.parse("[\"EOSE\",\"feed-1\"]", "feed-1", requested, 1_700_000_000))
    }
}
