package org.freegram.app.nearby

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.freegram.app.media.MediaStore
import org.freegram.app.media.PhotoEncoder
import org.freegram.app.protocol.PhotoRef
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
    val maxFrames: Int = 800,
)

/** What one exchange achieved. `acknowledged` means the peer stored it, not that anyone else received it. */
data class ExchangeReport(
    val offered: Int, val sent: Int, val acknowledged: Int, val received: Int, val rejected: Int, val outcome: String,
    val photosReceived: Int = 0, val photosSent: Int = 0,
)

/**
 * Symmetric exchange: HELLO → HAVE (recent IDs) → WANT (missing IDs) → EVENT… SENT, with ACK only after a durable commit.
 * If both phones support photos, a second round swaps missing photos the same way: BLOBHAVE → BLOBWANT →
 * BLOB + CHUNK… → BLOBSENT, with BLOBACK only after the bytes match the post's hash and are stored.
 * Either side may disconnect at any step; nothing is acknowledged that was not stored.
 */
class NearbyExchange(
    private val store: RoomStore,
    private val policy: NearbyPolicy = NearbyPolicy(),
    /** Relays to publish received posts to when online; empty disables the bridge. */
    private val bridgeTo: List<String> = emptyList(),
    /** Photo storage; null disables the photo round. */
    private val media: MediaStore? = null,
    private val photoCheck: (ByteArray, PhotoRef) -> Boolean = PhotoEncoder::isValidPhoto,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private class ProtocolError(message: String) : Exception(message)
    private class Disconnected : Exception()

    suspend fun run(link: PeerLink): ExchangeReport {
        var offered = 0; var sent = 0; var acknowledged = 0; var received = 0; var rejected = 0
        var photosReceived = 0; var photosSent = 0
        var frames = 0
        suspend fun next(): NearbyFrame {
            if (++frames > policy.maxFrames) throw ProtocolError("Frame limit reached")
            val text = withTimeout(policy.frameTimeoutMs) { link.receive() } ?: throw Disconnected()
            return try { NearbyFrames.parse(text) } catch (failure: IllegalArgumentException) {
                throw ProtocolError(failure.message ?: "Malformed frame")
            }
        }
        suspend fun send(frame: NearbyFrame) = link.send(NearbyFrames.encode(frame))
        fun report(outcome: String) = ExchangeReport(offered, sent, acknowledged, received, rejected, outcome, photosReceived, photosSent)

        return try {
            send(NearbyFrame.Hello(NearbyFrames.VERSION, media = media != null))
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

            if (media != null && hello.media) {
                val myPhotos = offers.values.mapNotNull { PhotoRef.of(it.first)?.sha256 }.distinct()
                    .filter { io { media.has(it) } }.take(NearbyFrames.MAX_BLOB_HAVE)
                send(NearbyFrame.BlobHave(myPhotos))
                val theirPhotos = next() as? NearbyFrame.BlobHave ?: throw ProtocolError("Expected BLOBHAVE")
                val missing = store.savedEvents().mapNotNull { PhotoRef.of(it) }.filterNot { io { media.has(it.sha256) } }.associateBy { it.sha256 }
                val photosWanted = theirPhotos.hashes.filter { it in missing }.take(NearbyFrames.MAX_BLOB_WANT).toMutableSet()
                send(NearbyFrame.BlobWant(photosWanted.toList()))
                val theirPhotoWant = next() as? NearbyFrame.BlobWant ?: throw ProtocolError("Expected BLOBWANT")
                if (!myPhotos.containsAll(theirPhotoWant.hashes)) throw ProtocolError("Requested a photo that was not offered")

                val awaitingPhotoAck = mutableSetOf<String>()
                for (sha in theirPhotoWant.hashes) {
                    val bytes = io { media.read(sha) } ?: continue
                    send(NearbyFrame.Blob(sha, bytes.size))
                    for ((index, start) in (bytes.indices step NearbyFrames.CHUNK_BYTES).withIndex()) {
                        send(NearbyFrame.Chunk(sha, index, bytes.copyOfRange(start, minOf(start + NearbyFrames.CHUNK_BYTES, bytes.size))))
                    }
                    awaitingPhotoAck += sha
                }
                send(NearbyFrame.BlobSent)

                var peerPhotosSent = false
                var incoming: Incoming? = null
                while (!peerPhotosSent || awaitingPhotoAck.isNotEmpty()) {
                    when (val frame = next()) {
                        is NearbyFrame.Blob -> {
                            if (incoming != null) throw ProtocolError("Overlapping photos")
                            incoming = Incoming(frame.sha256, frame.size, missing[frame.sha256].takeIf { photosWanted.remove(frame.sha256) })
                        }
                        is NearbyFrame.Chunk -> {
                            val photo = incoming ?: throw ProtocolError("Chunk without photo")
                            if (frame.sha256 != photo.sha256 || frame.index != photo.nextIndex) throw ProtocolError("Out-of-order chunk")
                            photo.add(frame.data)
                            if (photo.received > photo.size) throw ProtocolError("Photo larger than announced")
                            if (photo.received == photo.size) {
                                incoming = null
                                val reason = if (photo.ref == null) "unrequested" else storePhoto(photo.ref, photo.bytes())
                                if (reason == null) { photosReceived++; send(NearbyFrame.BlobAck(photo.sha256)) }
                                else send(NearbyFrame.BlobNack(photo.sha256, reason))
                            }
                        }
                        is NearbyFrame.BlobAck -> if (awaitingPhotoAck.remove(frame.sha256)) photosSent++
                        is NearbyFrame.BlobNack -> awaitingPhotoAck.remove(frame.sha256)
                        NearbyFrame.BlobSent -> {
                            if (incoming != null) throw ProtocolError("Incomplete photo")
                            peerPhotosSent = true
                        }
                        else -> throw ProtocolError("Unexpected frame")
                    }
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

    /** A photo being received. Bytes are kept only if it was requested ([ref] non-null). */
    private class Incoming(val sha256: String, val size: Int, val ref: PhotoRef?) {
        private val buffer = if (ref != null) ByteArrayOutputStream(size) else null
        var received = 0; private set
        var nextIndex = 0; private set
        fun add(data: ByteArray) { buffer?.write(data); received += data.size; nextIndex++ }
        fun bytes(): ByteArray = buffer!!.toByteArray()
    }

    /** Checks and stores a received photo. Returns a BLOBNACK reason, or null once stored. */
    private suspend fun storePhoto(ref: PhotoRef, bytes: ByteArray): String? {
        val store = media ?: return "photos disabled"
        if (bytes.size != ref.size) return "wrong size"
        if (!photoCheck(bytes, ref)) return "not a valid photo"
        val keep = this.store.savedEvents().mapNotNull { PhotoRef.of(it)?.sha256 }.toSet()
        return try { io { store.put(ref.sha256, bytes, keep) }; null }
        catch (_: IllegalArgumentException) { "hash mismatch" }
        catch (_: IllegalStateException) { "photo storage full" }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

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
                when (failure.message) {
                    "This author is blocked" -> "blocked author"
                    RoomStore.HIDDEN_MESSAGE -> "hidden by maintainer"
                    else -> "invalid event"
                }
            } catch (_: IllegalStateException) {
                "store full"
            }
        }
    }
}
