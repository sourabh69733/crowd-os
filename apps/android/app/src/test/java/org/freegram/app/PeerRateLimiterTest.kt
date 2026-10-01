package org.freegram.app

import org.freegram.app.nearby.PeerRateLimiter
import org.junit.Assert.*
import org.junit.Test

class PeerRateLimiterTest {
    @Test fun samePeerWaitsAndConcurrencyIsCapped() {
        var now = 0L
        val limiter = PeerRateLimiter(minIntervalMs = 60_000, maxConcurrent = 2) { now }
        assertTrue(limiter.tryStart("fg-a"))
        limiter.finish()
        now = 59_999
        assertFalse(limiter.tryStart("fg-a"))
        now = 60_000
        assertTrue(limiter.tryStart("fg-a"))
        assertTrue(limiter.tryStart("fg-b"))
        assertFalse(limiter.tryStart("fg-c"))
        limiter.finish()
        assertTrue(limiter.tryStart("fg-c"))
    }
}
