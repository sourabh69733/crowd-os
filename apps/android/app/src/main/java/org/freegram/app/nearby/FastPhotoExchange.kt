package org.freegram.app.nearby

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.freegram.app.media.MediaStore
import org.freegram.app.media.PhotoEncoder
import org.freegram.app.protocol.PhotoRef
import org.freegram.app.store.RoomStore

interface FilePeerLink : PeerLink {
    fun prepareFile(file: File): Long
    fun expectFile(id: Long, size: Int)
    suspend fun sendFile(id: Long)
    suspend fun receiveFile(id: Long): ByteArray
}

data class FastPhotoReport(val sent: Int, val received: Int, val outcome: String = "Complete", val piecesSent: Int = 0, val piecesReceived: Int = 0)

/** Reservation lasts through negotiation and is always released by NearbyExchange. */
interface FastMediaHandoff {
    val available: Boolean
    fun reserve(): String?
    suspend fun transfer(peerToken: String, exchange: suspend (FilePeerLink, Boolean) -> FastPhotoReport): FastPhotoReport
    fun release()
}

/** Files follow text. Direction ordering keeps only one file in flight and bounds callback state. */
class FastPhotoExchange(
    private val store: RoomStore,
    private val media: MediaStore,
    private val photoCheck: (ByteArray, PhotoRef) -> Boolean = PhotoEncoder::isValidPhoto,
) {
    suspend fun run(link: FilePeerLink, sendRefs: List<PhotoRef>, receiveRefs: List<PhotoRef>, sendsFirst: Boolean): FastPhotoReport {
        require(sendRefs.size <= NearbyFrames.MAX_BLOB_WANT && receiveRefs.size <= NearbyFrames.MAX_BLOB_WANT)
        var sent = 0; var received = 0; var frames = 0
        suspend fun send(frame: NearbyFrame) = link.send(NearbyFrames.encode(frame))
        suspend fun next(): NearbyFrame = withTimeout(15_000) {
            check(++frames <= 100) { "File frame limit reached" }
            NearbyFrames.parse(link.receive() ?: throw IOException("Media peer disconnected"))
        }
        suspend fun eligible(ref: PhotoRef) = store.savedEvents().any { PhotoRef.of(it) == ref }
        suspend fun sendPhotos() {
            for (ref in sendRefs) {
                if (!eligible(ref)) continue
                val file = media.path(ref.sha256) ?: continue
                val id = link.prepareFile(file)
                send(NearbyFrame.FileOffer(id, ref.sha256, ref.size))
                when (val ready = next()) {
                    is NearbyFrame.BlobNack -> { check(ready.sha256 == ref.sha256); continue }
                    is NearbyFrame.FileReady -> check(ready.payloadId == id)
                    else -> error("Expected file readiness")
                }
                check(eligible(ref)) { "Photo hidden during transfer" }
                withTimeout(120_000) { link.sendFile(id) }
                when (val ack = next()) {
                    is NearbyFrame.BlobAck -> { check(ack.sha256 == ref.sha256); sent++ }
                    is NearbyFrame.BlobNack -> check(ack.sha256 == ref.sha256)
                    else -> error("Expected file acknowledgement")
                }
            }
            send(NearbyFrame.BlobSent)
        }
        suspend fun receivePhotos() {
            val wanted = receiveRefs.associateBy { it.sha256 }.toMutableMap()
            val seenIds = mutableSetOf<Long>()
            while (true) {
                val frame = next()
                if (frame == NearbyFrame.BlobSent) return
                check(frame is NearbyFrame.FileOffer) { "Expected file descriptor" }
                check(seenIds.add(frame.payloadId)) { "Repeated file payload" }
                val ref = wanted.remove(frame.sha256)
                if (ref == null || frame.size != ref.size || !eligible(ref)) {
                    send(NearbyFrame.BlobNack(frame.sha256, "unrequested or hidden photo"))
                    continue
                }
                link.expectFile(frame.payloadId, frame.size)
                send(NearbyFrame.FileReady(frame.payloadId))
                val bytes = withTimeout(120_000) { link.receiveFile(frame.payloadId) }
                val reason = when {
                    !eligible(ref) -> "hidden photo"
                    bytes.size != ref.size -> "wrong size"
                    !photoCheck(bytes, ref) -> "not a valid photo"
                    else -> try {
                        val keep = store.savedEvents().mapNotNull { PhotoRef.of(it)?.sha256 }.toSet()
                        media.put(ref.sha256, bytes, keep)
                        null
                    } catch (_: IllegalArgumentException) { "hash mismatch" }
                    catch (_: IllegalStateException) { "photo storage full" }
                }
                if (reason == null) { received++; send(NearbyFrame.BlobAck(ref.sha256)) }
                else send(NearbyFrame.BlobNack(ref.sha256, reason))
            }
        }
        return try {
            withTimeout(5 * 60_000L) {
                if (sendsFirst) { sendPhotos(); receivePhotos() } else { receivePhotos(); sendPhotos() }
            }
            FastPhotoReport(sent, received)
        } catch (_: TimeoutCancellationException) { FastPhotoReport(sent, received, "Media timed out") }
        catch (failure: CancellationException) { throw failure }
        catch (failure: IOException) { FastPhotoReport(sent, received, "Media disconnected") }
        catch (failure: IllegalArgumentException) { FastPhotoReport(sent, received, "Invalid media frame") }
        catch (failure: IllegalStateException) { FastPhotoReport(sent, received, "Media protocol error") }
    }
}
