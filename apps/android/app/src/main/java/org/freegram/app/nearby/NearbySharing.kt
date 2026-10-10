package org.freegram.app.nearby

import android.Manifest
import android.content.Context
import android.os.Build
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import org.freegram.app.media.sha256Hex
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
import com.google.android.gms.nearby.connection.Strategy
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.freegram.app.media.MediaStore
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

/** Asked for with [nearbyPermissions]; sharing works without it, but the ongoing notification stays hidden. */
fun optionalNearbyPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

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
    private val handoffs = MediaHandoffCoordinator()
    private var mediaLink: NearbyPeerLink? = null
    private var mediaWait: CompletableDeferred<NearbyPeerLink>? = null
    @Volatile var running = false
        private set

    fun start() {
        if (running) return
        running = true
        handoffs.start()
        localName = "fg-" + ByteArray(3).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        startCluster()
    }

    private fun startCluster() {
        if (!running || handoffs.phase != MediaHandoffCoordinator.Phase.Cluster) return
        client.startAdvertising(localName, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { listener.onStatus("Could not advertise: ${it.message}") }
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { listener.onStatus("Could not search: ${it.message}") }
        listener.onStatus("Looking for nearby phones. This phone appears as $localName.")
    }

    fun stop() {
        if (!running) return
        running = false
        handoffs.stop()
        mediaWait?.completeExceptionally(IOException("Nearby stopped"))
        mediaLink?.disconnected()
        mediaLink = null
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        links.values.forEach { it.disconnected() }
        listener.onStatus("Nearby sharing stopped")
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Only the lower name asks, so two phones do not both request the same connection.
            if (!running || handoffs.phase != MediaHandoffCoordinator.Phase.Cluster || info.serviceId != SERVICE_ID || localName >= info.endpointName) return
            client.requestConnection(localName, endpointId, lifecycle)
                .addOnFailureListener { listener.onStatus("Could not connect to ${info.endpointName}: ${it.message}") }
        }

        override fun onEndpointLost(endpointId: String) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (!running || handoffs.phase != MediaHandoffCoordinator.Phase.Cluster || !limiter.tryStart(info.endpointName)) {
                client.rejectConnection(endpointId)
                return
            }
            names[endpointId] = info.endpointName
            val link = NearbyPeerLink(context, client, endpointId)
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
                val report = try { NearbyExchange(store, bridgeTo = bridgeTo, media = media, fastMedia = mediaHandoff(endpointId)).run(link) } finally {
                    link.awaitFlushed(5_000)
                    if (links[endpointId] === link) {
                        client.disconnectFromEndpoint(endpointId)
                        release(endpointId)
                    }
                }
                if (report.photosReceived > 0 || (report.received > 0 && bridgeTo.isNotEmpty())) RelayRetryWorker.schedule(context)
                withContext(Dispatchers.Main) { if (running) listener.onExchange(peer, report) }
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

    private fun wifiEnabled(): Boolean = isWifiEnabled(context)

    private fun mediaHandoff(endpointId: String) = object : FastMediaHandoff {
        private var lease: MediaHandoffCoordinator.Lease? = null
        private var token: String? = null
        override val available: Boolean get() = wifiEnabled()
        override fun reserve(): String? {
            if (!wifiEnabled()) return null
            lease = handoffs.reserve(endpointId) ?: return null
            return ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }.also { token = it }
        }
        override suspend fun transfer(peerToken: String, exchange: suspend (FilePeerLink, Boolean) -> FastPhotoReport): FastPhotoReport {
            val own = token ?: throw IOException("Media not reserved")
            val reservation = lease ?: throw IOException("Media not reserved")
            val session = sha256Hex(listOf(own, peerToken).sorted().joinToString("").toByteArray(Charsets.UTF_8))
            return try {
                withTimeout(5 * 60_000L) {
                    links[endpointId]?.awaitFlushed(5_000)
                    withTimeoutOrNull(15_000) { while (links.keys.any { it != endpointId }) delay(50) }
                    val connection = withContext(Dispatchers.Main) {
                        if (!running || !handoffs.connecting(reservation)) throw IOException("Media cancelled")
                        stopRadioOperations()
                        connectMedia(reservation, own, peerToken)
                    }
                    withContext(Dispatchers.Main) {
                        if (!running || !handoffs.transferring(reservation)) throw IOException("Media cancelled")
                        listener.onStatus("Transferring photos with one phone. Sharing with other phones resumes afterward.")
                    }
                    connection.send(NearbyFrames.encode(NearbyFrame.MediaGo(session)))
                    val proof = withTimeout(15_000) { connection.receive() } ?: throw IOException("Media peer left")
                    check(NearbyFrames.parse(proof) == NearbyFrame.MediaGo(session)) { "Wrong media session" }
                    val result = exchange(connection, own < peerToken)
                    connection.awaitFlushed(5_000)
                    result
                }
            } catch (failure: kotlinx.coroutines.TimeoutCancellationException) { FastPhotoReport(0, 0, "Media timed out") }
            catch (failure: CancellationException) { throw failure }
            catch (failure: IOException) { FastPhotoReport(0, 0, "Media disconnected") }
            catch (failure: IllegalArgumentException) { FastPhotoReport(0, 0, "Invalid media session") }
            catch (failure: IllegalStateException) { FastPhotoReport(0, 0, "Invalid media session") }
            finally {
                // A reservation must remain active until old radio operations have been stopped.
                withContext(NonCancellable + Dispatchers.Main) {
                    if (handoffs.returning(reservation)) {
                        stopRadioOperations()
                        mediaLink?.disconnected(); mediaLink = null
                        mediaWait = null
                        if (running && handoffs.resume(reservation)) startCluster()
                    }
                }
            }
        }
        override fun release() {
            lease?.let { handoffs.resume(it) }
            lease = null; token = null
        }
    }

    private fun stopRadioOperations() {
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        links.values.forEach { it.disconnected() }
        links.keys.toList().forEach(::release)
    }

    private suspend fun connectMedia(lease: MediaHandoffCoordinator.Lease, own: String, peer: String): NearbyPeerLink {
        if (!running || !handoffs.current(lease) || handoffs.phase != MediaHandoffCoordinator.Phase.Connecting) throw IOException("Media cancelled")
        val ready = CompletableDeferred<NearbyPeerLink>()
        mediaWait = ready
        val mediaLinks = ConcurrentHashMap<String, NearbyPeerLink>()
        val callbacks = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
                if (!running || !handoffs.current(lease) || handoffs.phase != MediaHandoffCoordinator.Phase.Connecting || info.endpointName != peer || mediaLinks.isNotEmpty()) {
                    client.rejectConnection(id); return
                }
                val link = NearbyPeerLink(context, client, id, filesEnabled = true)
                mediaLinks[id] = link
                client.acceptConnection(id, link.payloadCallback).addOnFailureListener { ready.completeExceptionally(IOException("Media connection rejected", it)) }
            }
            override fun onConnectionResult(id: String, result: ConnectionResolution) {
                val link = mediaLinks[id] ?: return
                if (!running || !handoffs.current(lease) || handoffs.phase != MediaHandoffCoordinator.Phase.Connecting || !result.status.isSuccess) {
                    link.disconnected(); client.disconnectFromEndpoint(id)
                    ready.completeExceptionally(IOException("Media connection failed")); return
                }
                mediaLink = link
                ready.complete(link)
            }
            override fun onDisconnected(id: String) {
                mediaLinks[id]?.disconnected()
                ready.completeExceptionally(IOException("Media peer left"))
            }
        }
        if (own < peer) {
            client.startAdvertising(own, MEDIA_SERVICE_ID, callbacks, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
                .addOnFailureListener { ready.completeExceptionally(IOException("Could not advertise media", it)) }
        } else {
            client.startDiscovery(MEDIA_SERVICE_ID, object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
                    if (!running || !handoffs.current(lease) || handoffs.phase != MediaHandoffCoordinator.Phase.Connecting || info.endpointName != peer || info.serviceId != MEDIA_SERVICE_ID) return
                    client.requestConnection(own, id, callbacks).addOnFailureListener { ready.completeExceptionally(IOException("Could not connect media", it)) }
                }
                override fun onEndpointLost(id: String) = Unit
            }, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
                .addOnFailureListener { ready.completeExceptionally(IOException("Could not search media", it)) }
        }
        var connected = false
        return try { withTimeout(30_000) { ready.await() }.also { connected = true } }
        finally {
            if (!connected) { ready.cancel(); mediaLinks.values.forEach { it.disconnected() } }
        }
    }

    companion object {
        private const val MEDIA_SERVICE_ID = "org.freegram.media.v2"
        private const val SERVICE_ID = "org.freegram.bulletin.v1"
        private val STRATEGY = Strategy.P2P_CLUSTER
    }
}
