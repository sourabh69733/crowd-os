package org.freegram.app

import org.freegram.app.nearby.NearbyFrame
import org.freegram.app.nearby.NearbyFrames
import org.freegram.app.nearby.VideoPieceId
import org.freegram.app.nearby.VideoPieceInventory
import org.freegram.app.protocol.VideoManifest
import org.junit.Assert.*
import org.junit.Test

class VideoFramesTest {
    @Test fun optionalVideoCapabilityAndBoundedInventoryRoundTrip() {
        val hello = NearbyFrame.Hello(1, true, true, video = true)
        assertEquals(hello, NearbyFrames.parse(NearbyFrames.encode(hello)))
        assertFalse((NearbyFrames.parse("[\"HELLO\",1,{\"media\":1,\"fast\":2}]") as NearbyFrame.Hello).video)
        val inventory = NearbyFrame.PieceHave(listOf(VideoPieceInventory("a".repeat(64), (0..59).toList())))
        assertEquals(inventory, NearbyFrames.parse(NearbyFrames.encode(inventory)))
        val want = NearbyFrame.PieceWant(listOf(VideoPieceId("a".repeat(64), 59)))
        assertEquals(want, NearbyFrames.parse(NearbyFrames.encode(want)))
    }
    @Test fun manifestAndMalformedVideoFrames() {
        val manifest = VideoManifest.sign(ByteArray(32) { 3 }, ByteArray(100), 640, 480, 10, 100)
        val frame = NearbyFrame.Manifest(manifest.event)
        assertEquals(frame, NearbyFrames.parse(NearbyFrames.encode(frame)))
        for (wire in listOf("[\"VIDEOHAVE\",[\"${"A".repeat(64)}\"]]", "[\"PIECEWANT\",[[\"${"a".repeat(64)}\",60]]]", "[\"PIECEHAVE\",[[\"${"a".repeat(64)}\",[1,1]]]]")) {
            try { NearbyFrames.parse(wire); fail("Malformed video frame accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
