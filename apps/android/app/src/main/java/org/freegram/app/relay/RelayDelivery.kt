package org.freegram.app.relay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.store.RoomStore

object RetryPolicy {
    /** Background retry stops for events older than this; relays may reject stale events. */
    const val MAX_AGE_SECONDS = 48L * 3600

    /**
     * `Sending` means the user asked to submit and no outcome was recorded (e.g. the app was killed).
     * `Pending` is excluded: an imported carried event waits for an explicit submit.
     */
    fun isRetryable(state: String): Boolean =
        state == "Sending" || state == "Network error" || state == "Timed out" ||
            state == "HTTP 429" || state.matches(Regex("HTTP 5\\d\\d")) ||
            state.startsWith("Rejected: rate-limited")
}

/** Submits one event to each relay, retrying temporary failures a bounded number of times. */
class RelayDelivery(
    private val store: RoomStore,
    private val publish: suspend (String, BulletinEvent) -> String,
    private val retryDelaysMs: List<Long> = listOf(2_000, 5_000),
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun deliver(
        event: BulletinEvent,
        relays: List<String>,
        onState: suspend (String, String) -> Unit = { _, _ -> },
    ): Map<String, String> = coroutineScope {
        relays.map { relay -> async { relay to deliverOne(event, relay, onState) } }.awaitAll().toMap()
    }

    private suspend fun deliverOne(event: BulletinEvent, relay: String, onState: suspend (String, String) -> Unit): String {
        var state = store.setRelayState(event.id, relay, "Sending")
        onState(relay, state)
        if (state == "Accepted") return state
        for (attempt in 0..retryDelaysMs.size) {
            val outcome = try { publish(relay, event) } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                "Network error"
            }
            state = store.setRelayState(event.id, relay, outcome)
            onState(relay, state)
            if (!RetryPolicy.isRetryable(state) || attempt == retryDelaysMs.size) break
            pause(retryDelaysMs[attempt])
        }
        return state
    }
}

/** One background pass over all retryable deliveries. Returns true if some remain retryable. */
suspend fun retryPendingDeliveries(
    store: RoomStore,
    publish: suspend (String, BulletinEvent) -> String,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
): Boolean {
    val notBefore = nowSeconds - RetryPolicy.MAX_AGE_SECONDS
    val delivery = RelayDelivery(store, publish, retryDelaysMs = emptyList())
    for ((event, relays) in store.retryableDeliveries(notBefore)) delivery.deliver(event, relays)
    return store.retryableDeliveries(notBefore).isNotEmpty()
}
