package org.freegram.app.nearby

/**
 * Bounds exchanges per advertised peer name and in total. Names rotate per session, so a hostile
 * peer can evade the per-name limit by renaming; the concurrency cap still bounds work.
 */
class PeerRateLimiter(
    private val minIntervalMs: Long = 60_000,
    private val maxConcurrent: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastStart = LinkedHashMap<String, Long>()
    private var active = 0

    @Synchronized fun tryStart(peer: String): Boolean {
        val now = clock()
        if (active >= maxConcurrent) return false
        lastStart[peer]?.let { if (now - it < minIntervalMs) return false }
        lastStart.remove(peer)
        lastStart[peer] = now
        while (lastStart.size > 256) lastStart.remove(lastStart.keys.first())
        active++
        return true
    }

    @Synchronized fun finish() { if (active > 0) active-- }
}
