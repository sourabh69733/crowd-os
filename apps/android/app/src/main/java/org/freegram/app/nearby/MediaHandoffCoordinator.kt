package org.freegram.app.nearby

/** One media window per phone. Old callbacks cannot resume a stopped or newer session. */
class MediaHandoffCoordinator {
    enum class Phase { Stopped, Cluster, Preparing, Connecting, Transferring, Returning }
    data class Lease(val generation: Long, val endpoint: String, val serial: Long)
    @Volatile var phase = Phase.Stopped; private set
    private var generation = 0L
    private var serial = 0L
    private var active: Lease? = null

    @Synchronized fun start() { generation++; active = null; phase = Phase.Cluster }
    @Synchronized fun stop() { generation++; active = null; phase = Phase.Stopped }
    @Synchronized fun reserve(endpoint: String): Lease? {
        if (phase != Phase.Cluster) return null
        return Lease(generation, endpoint, ++serial).also { active = it; phase = Phase.Preparing }
    }
    @Synchronized fun current(lease: Lease): Boolean = active == lease && lease.generation == generation
    @Synchronized fun connecting(lease: Lease): Boolean {
        if (!current(lease) || phase != Phase.Preparing) return false
        phase = Phase.Connecting
        return true
    }
    @Synchronized fun transferring(lease: Lease): Boolean {
        if (!current(lease) || phase != Phase.Connecting) return false
        phase = Phase.Transferring
        return true
    }
    @Synchronized fun returning(lease: Lease): Boolean {
        if (!current(lease)) return false
        phase = Phase.Returning
        return true
    }
    @Synchronized fun resume(lease: Lease): Boolean {
        if (!current(lease)) return false
        active = null
        phase = Phase.Cluster
        return true
    }
}
