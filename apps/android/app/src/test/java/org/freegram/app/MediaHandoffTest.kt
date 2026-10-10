package org.freegram.app

import org.freegram.app.nearby.MediaHandoffCoordinator
import org.freegram.app.nearby.NearbyFrame
import org.freegram.app.nearby.NearbyFrames
import org.junit.Assert.*
import org.junit.Test

class MediaHandoffTest {
    @Test fun oldHelloAndNewCapabilitiesRoundTrip() {
        assertEquals(NearbyFrame.Hello(1, media = true), NearbyFrames.parse("[\"HELLO\",1,{\"media\":1}]"))
        val hello = NearbyFrame.Hello(1, media = true, fastMedia = true)
        assertEquals(hello, NearbyFrames.parse(NearbyFrames.encode(hello)))
        val frames = listOf(NearbyFrame.MediaReady("ab".repeat(32)), NearbyFrame.MediaReady(null),
            NearbyFrame.MediaGo("cd".repeat(32)), NearbyFrame.FileOffer(42, "ef".repeat(32), 1024), NearbyFrame.FileReady(42))
        frames.forEach { assertEquals(it, NearbyFrames.parse(NearbyFrames.encode(it))) }
    }

    @Test fun newFramesRejectUnboundedOrInvalidFields() {
        listOf("[\"MEDIAREADY\",\"ABC\"]", "[\"MEDIAGO\",\"x\"]", "[\"FILE\",42,\"${"ab".repeat(32)}\",1048577]").forEach { wire ->
            assertThrows(IllegalArgumentException::class.java) { NearbyFrames.parse(wire) }
        }
    }

    @Test fun onlyOneHandoffCanReserveAndStopInvalidatesLease() {
        val c = MediaHandoffCoordinator()
        assertNull(c.reserve("a"))
        c.start()
        val lease = c.reserve("a")!!
        assertNull(c.reserve("b"))
        assertTrue(c.connecting(lease))
        assertTrue(c.transferring(lease))
        c.stop()
        assertFalse(c.resume(lease))
        assertEquals(MediaHandoffCoordinator.Phase.Stopped, c.phase)
        c.start()
        val next = c.reserve("b")!!
        assertFalse(c.resume(lease))
        assertTrue(c.resume(next))
        assertEquals(MediaHandoffCoordinator.Phase.Cluster, c.phase)
    }
    @Test fun stoppingAtAnyStagePreventsLateRestart() {
        for (stage in 0..3) {
            val c = MediaHandoffCoordinator(); c.start()
            val lease = c.reserve("peer")!!
            if (stage >= 1) assertTrue(c.connecting(lease))
            if (stage >= 2) assertTrue(c.transferring(lease))
            if (stage >= 3) assertTrue(c.returning(lease))
            c.stop()
            assertFalse(c.connecting(lease))
            assertFalse(c.transferring(lease))
            assertFalse(c.resume(lease))
            assertEquals(MediaHandoffCoordinator.Phase.Stopped, c.phase)
        }
    }

}
