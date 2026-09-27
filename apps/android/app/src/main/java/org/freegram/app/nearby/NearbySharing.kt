package org.freegram.app.nearby

import android.Manifest
import android.content.Context
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.freegram.app.media.MediaStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.freegram.app.relay.RelayRetryWorker
import org.freegram.app.store.RoomStore

/** Runtime permissions Nearby Connections needs on this Android version. */
fun nearbyPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    if (Build.VERSION.SDK_INT <= 32) add(Manifest.permission.ACCESS_FINE_LOCATION)
}.toTypedArray()

fun hasPlayServices(context: Context): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

/**
 * Google Nearby Connections adapter for [NearbyExchange]. Runs only while started by the user.
 * Advertises a random per-session name, never the signing key. Connections are accepted without
 * a pairing code because every post is public and signature-checked before storage.
 */
class NearbySharing(
    private val context: Context,
    private val store: RoomStore,
    private val media: MediaStore,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String)
        fun onExchange(peer: String, report: ExchangeReport)
    }

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val limiter = PeerRateLimiter()
    private val links = ConcurrentHashMap<String, NearbyPeerLink>()
    private val names = ConcurrentHashMap<String, String>()
    private var localName = ""
    var running = false
        private set

    fun start() {
        if (running) return
        running = true
        localName = "fg-" + ByteArray(3).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        client.startAdvertising(localName, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { listener.onStatus("Could not advertise: ${it.message}") }
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { listener.onStatus("Could not search: ${it.message}") }
        listener.onStatus("Looking for nearby phones. This phone appears as $localName.")
    }

    fun stop() {
        if (!running) return
        running = false
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        links.values.forEach { it.disconnected() }
        listener.onStatus("Nearby sharing stopped")
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Only the lower name asks, so two phones do not both request the same connection.
            if (info.serviceId != SERVICE_ID || localName >= info.endpointName) return
            client.requestConnection(localName, endpointId, lifecycle)
                .addOnFailureListener { listener.onStatus("Could not connect to ${info.endpointName}: ${it.message}") }
        }

        override fun onEndpointLost(endpointId: String) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (!limiter.tryStart(info.endpointName)) {
                client.rejectConnection(endpointId)
                return
            }
            names[endpointId] = info.endpointName
            val link = NearbyPeerLink(client, endpointId)
            links[endpointId] = link
            client.acceptConnection(endpointId, link.payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val link = links[endpointId] ?: return
            val peer = names[endpointId] ?: endpointId
            if (!result.status.isSuccess) {
                release(endpointId)
                listener.onStatus("Connection to $peer failed")
                return
            }
            listener.onStatus("Exchanging with $peer…")
            scope.launch(Dispatchers.IO) {
                val bridgeTo = if (store.autoBridge()) listOf(store.relayUrl(0), store.relayUrl(1)) else emptyList()
                val report = try { NearbyExchange(store, bridgeTo = bridgeTo, media = media).run(link) } finally {
                    link.awaitFlushed(5_000)
                    client.disconnectFromEndpoint(endpointId)
                    release(endpointId)
                }
                if (report.received > 0 && bridgeTo.isNotEmpty()) RelayRetryWorker.schedule(context)
                withContext(Dispatchers.Main) { listener.onExchange(peer, report) }
            }
        }

        override fun onDisconnected(endpointId: String) {
            links[endpointId]?.disconnected()
        }
    }

    private fun release(endpointId: String) {
        if (links.remove(endpointId) != null) limiter.finish()
        names.remove(endpointId)
    }

    companion object {
        private const val SERVICE_ID = "org.freegram.bulletin.v1"
        private val STRATEGY = Strategy.P2P_CLUSTER
    }
}

/** One Nearby endpoint as a [PeerLink]. Each frame is one BYTES payload. */
private class NearbyPeerLink(private val client: ConnectionsClient, private val endpointId: String) : PeerLink {
    private val inbox = Channel<String>(64)
    private val pending = ConcurrentHashMap.newKeySet<Long>()

    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes()
            val accepted = payload.type == Payload.Type.BYTES && bytes != null && bytes.size <= NearbyFrames.MAX_CHUNK_FRAME_BYTES &&
                inbox.trySend(String(bytes, Charsets.UTF_8)).isSuccess
            // Wrong type, oversized or flooding: drop the peer rather than buffer.
            if (!accepted) {
                inbox.close()
                client.disconnectFromEndpoint(endpointId)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status != PayloadTransferUpdate.Status.IN_PROGRESS) pending.remove(update.payloadId)
        }
    }

    override suspend fun send(frame: String) {
        val payload = Payload.fromBytes(frame.toByteArray(Charsets.UTF_8))
        pending += payload.id
        client.sendPayload(endpointId, payload).addOnFailureListener { pending.remove(payload.id) }
    }

    override suspend fun receive(): String? = inbox.receiveCatching().getOrNull()

    /** The adapter disconnects after [awaitFlushed], so the last ACK or SENT is not cut off. */
    override fun close() = Unit

    suspend fun awaitFlushed(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { while (pending.isNotEmpty()) delay(50) }
    }

    fun disconnected() { inbox.close() }
}
