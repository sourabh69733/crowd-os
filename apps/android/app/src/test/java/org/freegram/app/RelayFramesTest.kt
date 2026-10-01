package org.freegram.app

import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.relay.RelayFrame
import org.freegram.app.relay.RelayFrames
import org.junit.Assert.*
import org.junit.Test

class RelayFramesTest {
    private val event = Nip01Protocol.signBulletin(ByteArray(32).also { it[31] = 3 }, "Bridge test", 1_700_000_000)
    private val subscription = "freegram-fetch"

    @Test fun requestFetchesOnlyRequestedId() {
        val request = org.json.JSONArray(RelayFrames.request(subscription, event.id))
        assertEquals("REQ", request.getString(0))
        assertEquals(subscription, request.getString(1))
        assertEquals(event.id, request.getJSONObject(2).getJSONArray("ids").getString(0))
        assertEquals(1, request.getJSONObject(2).getInt("limit"))
    }

    @Test fun acceptsOnlyVerifiedEventForMatchingRequest() {
        val frame = "[\"EVENT\",\"$subscription\",${Nip01Protocol.toJson(event)}]"
        assertEquals(event, (RelayFrames.parse(frame, subscription, event.id) as RelayFrame.Verified).event)
        assertEquals(RelayFrame.Ignore, RelayFrames.parse(frame, subscription, "00".repeat(32)))
        assertEquals(RelayFrame.Ignore, RelayFrames.parse(frame, "other-sub", event.id))
        assertEquals(RelayFrame.Ignore, RelayFrames.parse(frame.replace("Bridge test", "Changed"), subscription, event.id))
    }

    @Test fun boundsFramesAndRecognizesEndOfStoredEvents() {
        assertEquals(RelayFrame.Ignore, RelayFrames.parse("x".repeat(8193), subscription, event.id))
        assertEquals(RelayFrame.End, RelayFrames.parse("[\"EOSE\",\"$subscription\"]", subscription, event.id))
        assertEquals(RelayFrame.Ignore, RelayFrames.parse("[\"EOSE\",\"other-sub\"]", subscription, event.id))
        assertEquals(RelayFrame.Closed("blocked"), RelayFrames.parse("[\"CLOSED\",\"$subscription\",\"blocked\"]", subscription, event.id))
    }
}
