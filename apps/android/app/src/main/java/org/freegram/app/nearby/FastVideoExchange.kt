package org.freegram.app.nearby

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.freegram.app.media.PhotoChanges
import org.freegram.app.media.VideoRetention
import org.freegram.app.media.VideoStore
import org.freegram.app.protocol.VideoManifest
import org.freegram.app.store.RoomStore

/** A bounded batch of requested pieces. Saved pieces survive a failed or interrupted batch. */
class FastVideoExchange(private val store: RoomStore, private val videos: VideoStore, private val pubkey: String) {
    private suspend fun eligible(manifest: VideoManifest): Boolean = store.savedEvents().any(manifest::matches)
    private suspend fun <T> io(action: () -> T): T = withContext(Dispatchers.IO) { action() }

    suspend fun run(link: FilePeerLink, sendPieces: List<VideoPieceId>, receivePieces: List<VideoPieceId>, sendsFirst: Boolean): FastPhotoReport {
        require(sendPieces.size <= NearbyFrames.MAX_VIDEO_PIECES && receivePieces.size <= NearbyFrames.MAX_VIDEO_PIECES)
        var sent = 0; var received = 0
        suspend fun send(frame: NearbyFrame) = link.send(NearbyFrames.encode(frame))
        suspend fun next(): NearbyFrame = withTimeout(15_000) { NearbyFrames.parse(link.receive() ?: throw IOException("Media disconnected")) }
        suspend fun sendBatch() {
            for (request in sendPieces) {
                val manifest = io { videos.manifest(request.manifestId) } ?: continue
                if (request.index !in manifest.pieces.indices || !eligible(manifest)) continue
                val hash = manifest.pieces[request.index]
                val file = io { videos.piece(hash) } ?: continue
                if (file.length() != manifest.pieceSize(request.index).toLong()) continue
                val id = io { link.prepareFile(file) }
                send(NearbyFrame.FileOffer(id, hash, manifest.pieceSize(request.index)))
                when (val ready = next()) {
                    is NearbyFrame.FileReady -> check(ready.payloadId == id)
                    is NearbyFrame.BlobNack -> { check(ready.sha256 == hash); continue }
                    else -> error("Expected piece readiness")
                }
                check(eligible(manifest)) { "Video hidden during transfer" }
                withTimeout(120_000) { link.sendFile(id) }
                when (val ack = next()) {
                    is NearbyFrame.BlobAck -> { check(ack.sha256 == hash); sent++ }
                    is NearbyFrame.BlobNack -> check(ack.sha256 == hash)
                    else -> error("Expected piece acknowledgement")
                }
            }
            send(NearbyFrame.VideosSent)
        }
        suspend fun receiveBatch() {
            val wanted = receivePieces.mapNotNull { request -> io { videos.manifest(request.manifestId) }?.let { manifest ->
                if (request.index in manifest.pieces.indices) manifest.pieces[request.index] to (request to manifest) else null
            } }.toMap().toMutableMap()
            val ids = mutableSetOf<Long>()
            while (true) {
                val frame = next()
                if (frame == NearbyFrame.VideosSent) return
                check(frame is NearbyFrame.FileOffer && ids.add(frame.payloadId)) { "Invalid piece descriptor" }
                val planned = wanted.remove(frame.sha256)
                if (planned == null || frame.size != planned.second.pieceSize(planned.first.index) || !eligible(planned.second)) {
                    send(NearbyFrame.BlobNack(frame.sha256, "unrequested or hidden video piece")); continue
                }
                link.expectFile(frame.payloadId, frame.size)
                send(NearbyFrame.FileReady(frame.payloadId))
                val bytes = withTimeout(120_000) { link.receiveFile(frame.payloadId) }
                val reason = if (!eligible(planned.second)) "hidden video" else try {
                    val retention = VideoRetention.load(store, videos, pubkey)
                    io {
                        videos.putPiece(planned.second, planned.first.index, bytes, retention.keep, retention.own, retention.protected)
                        videos.assemble(planned.second, retention.keep, retention.own, retention.protected)
                    }
                    null
                } catch (_: IllegalArgumentException) { "video fingerprint mismatch" }
                catch (_: IllegalStateException) { "video storage full" }
                if (reason == null) { received++; PhotoChanges.changed(); send(NearbyFrame.BlobAck(frame.sha256)) }
                else send(NearbyFrame.BlobNack(frame.sha256, reason))
            }
        }
        return try {
            withTimeout(5 * 60_000L) {
                if (sendsFirst) { sendBatch(); receiveBatch() } else { receiveBatch(); sendBatch() }
            }
            FastPhotoReport(0, 0, piecesSent = sent, piecesReceived = received)
        } catch (_: TimeoutCancellationException) { FastPhotoReport(0, 0, "Video timed out", sent, received) }
        catch (failure: CancellationException) { throw failure }
        catch (_: IOException) { FastPhotoReport(0, 0, "Video disconnected", sent, received) }
        catch (_: IllegalArgumentException) { FastPhotoReport(0, 0, "Invalid video frame", sent, received) }
        catch (_: IllegalStateException) { FastPhotoReport(0, 0, "Video protocol error", sent, received) }
    }
}
