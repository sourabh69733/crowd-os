package org.freegram.app.nearby

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.freegram.app.store.RoomStore

/** A connected peer, e.g. a Nearby Connections endpoint. Frames arrive in order; `receive` returns null after disconnect. */
interface PeerLink {
    suspend fun send(frame: String)
    suspend fun receive(): String?
    fun close()
}

/** Pilot limits from the Phase 0 contract. Hop and age limits bind this client only, not a malicious peer. */
data class NearbyPolicy(
    val maxHops: Int = 6,
    val maxAgeSeconds: Long = 48L * 3600,
    val maxFutureSeconds: Long = 600,
    val frameTimeoutMs: Long = 15_000,
    val maxFrames: Int = 200,
)

/** What one exchange achieved. `acknowledged` means the peer stored it, not that anyone else received it. */
data class ExchangeReport(val offered: Int, val sent: Int, val acknowledged: Int, val received: Int, val rejected: Int, val outcome: String)

/**
 * Symmetric exchange: HELLO → HAVE (recent IDs) → WANT (missing IDs) → EVENT… SENT, with ACK only after a durable commit.
 * Either side may disconnect at any step; nothing is acknowledged that was not stored.
 */
class NearbyExchange(
    private val store: RoomStore,
    private val policy: NearbyPolicy = NearbyPolicy(),
    /** Relays to publish received posts to when online; empty disables the bridge. */
    private val bridgeTo: List<String> = emptyList(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private class ProtocolError(message: String) : Exception(message)
    private class Disconnected : Exception()

    suspend fun run(link: PeerLink): ExchangeReport {
        var offered = 0; var sent = 0; var acknowledged = 0; var received = 0; var rejected = 0
        var frames = 0
        suspend fun next(): NearbyFrame {
            if (++frames > policy.maxFrames) throw ProtocolError("Frame limit reached")
            val text = withTimeout(policy.frameTimeoutMs) { link.receive() } ?: throw Disconnected()
            return try { NearbyFrames.parse(text) } catch (failure: IllegalArgumentException) {
                throw ProtocolError(failure.message ?: "Malformed frame")
            }
        }
        suspend fun send(frame: NearbyFrame) = link.send(NearbyFrames.encode(frame))
        fun report(outcome: String) = ExchangeReport(offered, sent, acknowledged, received, rejected, outcome)

        return try {
            send(NearbyFrame.Hello(NearbyFrames.VERSION))
            val hello = next() as? NearbyFrame.Hello ?: throw ProtocolError("Expected HELLO")
            if (hello.version != NearbyFrames.VERSION) throw ProtocolError("Unsupported version ${hello.version}")

            val clock = now()
            val offers = store.nearbyOffers(clock - policy.maxAgeSeconds, clock + policy.maxFutureSeconds, policy.maxHops, NearbyFrames.MAX_HAVE)
                .associateBy { it.first.id }
            offered = offers.size
            send(NearbyFrame.Have(offers.keys.toList()))
            val theirs = next() as? NearbyFrame.Have ?: throw ProtocolError("Expected HAVE")

            val wanted = theirs.ids.filterNot { store.hasBulletin(it) }.take(NearbyFrames.MAX_WANT).toMutableSet()
            send(NearbyFrame.Want(wanted.toList()))
            val theirWant = next() as? NearbyFrame.Want ?: throw ProtocolError("Expected WANT")
            if (!offers.keys.containsAll(theirWant.ids)) throw ProtocolError("Requested an ID that was not offered")

            val awaitingAck = mutableSetOf<String>()
            for (id in theirWant.ids) {
                val (event, hops) = offers.getValue(id)
                send(NearbyFrame.Event(hops, event))
                awaitingAck += id
                sent++
            }
            send(NearbyFrame.Sent)

            var peerSent = false
            while (!peerSent || awaitingAck.isNotEmpty()) {
                when (val frame = next()) {
                    is NearbyFrame.Event -> {
                        val id = frame.event.id
                        val reason = if (!wanted.remove(id)) "unrequested" else accept(frame)
                        if (reason == null) { received++; send(NearbyFrame.Ack(id)) }
                        else { rejected++; send(NearbyFrame.Nack(id, reason)) }
                    }
                    is NearbyFrame.Ack -> if (awaitingAck.remove(frame.id)) acknowledged++
                    is NearbyFrame.Nack -> awaitingAck.remove(frame.id)
                    NearbyFrame.Sent -> peerSent = true
                    else -> throw ProtocolError("Unexpected frame")
                }
            }
            report("Complete")
        } catch (_: TimeoutCancellationException) {
            report("Timed out")
        } catch (_: Disconnected) {
            report("Disconnected")
        } catch (failure: ProtocolError) {
            report("Protocol error: ${failure.message}")
        } catch (failure: CancellationException) {
            throw failure
        } finally {
            link.close()
        }
    }

    /** Validates and durably stores one received post. Returns a NACK reason, or null once committed. */
    private suspend fun accept(frame: NearbyFrame.Event): String? {
        val clock = now()
        val event = frame.event
        return when {
            frame.hops < 0 || frame.hops >= policy.maxHops -> "hop limit"
            event.createdAt > clock + policy.maxFutureSeconds -> "future timestamp"
            event.createdAt < clock - policy.maxAgeSeconds -> "too old"
            else -> try {
                if (store.saveNearbyEvent(event, frame.hops + 1, bridgeTo)) null else "store full"
            } catch (failure: IllegalArgumentException) {
                if (failure.message == "This author is blocked") "blocked author" else "invalid event"
            } catch (_: IllegalStateException) {
                "store full"
            }
        }
    }
}
