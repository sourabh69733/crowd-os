package org.freegram.app.nearby

import android.content.Context
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.freegram.app.protocol.PhotoRef

/** Frames use BYTES; only an authenticated media session permits requested FILE payloads. */
internal class NearbyPeerLink(
    private val context: Context,
    private val client: ConnectionsClient,
    private val endpointId: String,
    private val filesEnabled: Boolean = false,
) : FilePeerLink {
    private val inbox = Channel<String>(64)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    private data class Outgoing(val payload: Payload, val file: File)
    private val outgoing = ConcurrentHashMap<Long, Outgoing>()
    private val expected = ConcurrentHashMap<Long, Int>()
    private val incoming = IncomingPhotoFile()
    @Volatile private var disconnected = false

    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.FILE) {
                val file = payload.asFile()
                val uri = file?.asUri()
                val javaFile = if (uri == null) file?.asJavaFile() else null
                val dispose = {
                    runCatching { file?.close() }
                    runCatching { if (uri?.scheme == "file") uri.path?.let { File(it).delete() }
                        else if (uri != null) context.contentResolver.delete(uri, null, null) else javaFile?.delete() }
                    Unit
                }
                val canAccept = !disconnected && filesEnabled && file != null
                val accepted = if (canAccept) incoming.file(payload.id, {
                    if (uri != null) context.contentResolver.openInputStream(uri) ?: throw IOException("File unavailable")
                    else javaFile?.inputStream() ?: throw IOException("File unavailable")
                }, dispose) else { dispose(); false }
                if (!accepted) { client.cancelPayload(payload.id); fail() }
                return
            }
            val bytes = payload.asBytes()
            if (payload.type != Payload.Type.BYTES || bytes == null || bytes.size > NearbyFrames.MAX_CHUNK_FRAME_BYTES ||
                !inbox.trySend(String(bytes, Charsets.UTF_8)).isSuccess) fail()
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            expected[update.payloadId]?.let { size ->
                if (update.bytesTransferred > size || update.totalBytes != size.toLong()) client.cancelPayload(update.payloadId)
                incoming.update(update.payloadId, update.bytesTransferred, update.totalBytes,
                    when (update.status) {
                        PayloadTransferUpdate.Status.SUCCESS -> true
                        PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> false
                        else -> null
                    })
            }
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) return
            pending.remove(update.payloadId)?.let { done ->
                if (update.status == PayloadTransferUpdate.Status.SUCCESS) done.complete(Unit)
                else done.completeExceptionally(IOException("Payload failed"))
            }
        }
    }

    private fun fail() { disconnected(); client.disconnectFromEndpoint(endpointId) }

    private suspend fun sendPayload(payload: Payload, wait: Boolean) {
        val done = CompletableDeferred<Unit>()
        synchronized(this) {
            if (disconnected) throw IOException("Peer disconnected")
            pending[payload.id] = done
            client.sendPayload(endpointId, payload).addOnFailureListener { failure ->
                pending.remove(payload.id)?.completeExceptionally(IOException("Could not send", failure))
                fail()
            }
        }
        if (wait) withTimeout(120_000) { done.await() }
    }

    override suspend fun send(frame: String) = sendPayload(Payload.fromBytes(frame.toByteArray(Charsets.UTF_8)), true)
    override suspend fun receive(): String? = inbox.receiveCatching().getOrNull()
    override fun close() = Unit

    @Synchronized override fun prepareFile(file: File): Long {
        check(filesEnabled && !disconnected)
        require(file.length() in 1..PhotoRef.MAX_BYTES.toLong())
        val dir = File(context.cacheDir, "nearby-files").also { it.mkdirs() }
        val copy = File.createTempFile("out-", ".jpg", dir)
        try {
            file.inputStream().use { input -> copy.outputStream().use { output -> input.copyTo(output) } }
            require(copy.length() in 1..PhotoRef.MAX_BYTES.toLong())
            val payload = Payload.fromFile(copy)
            outgoing[payload.id] = Outgoing(payload, copy)
            return payload.id
        } catch (failure: Exception) { copy.delete(); throw failure }
    }
    @Synchronized override fun expectFile(id: Long, size: Int) {
        check(filesEnabled && !disconnected)
        incoming.expect(id, size)
        expected[id] = size
    }
    override suspend fun sendFile(id: Long) {
        val value = outgoing[id] ?: throw IOException("File not prepared")
        try { sendPayload(value.payload, true) }
        finally { outgoing.remove(id); value.payload.close(); value.file.delete() }
    }
    override suspend fun receiveFile(id: Long): ByteArray = try { incoming.receive(id) } finally { expected.remove(id) }

    suspend fun awaitFlushed(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { while (pending.isNotEmpty()) delay(50) }
    }
    @Synchronized fun disconnected() {
        disconnected = true
        inbox.close()
        incoming.cancel()
        expected.keys.forEach { client.cancelPayload(it) }
        expected.clear()
        pending.forEach { (id, done) -> client.cancelPayload(id); done.completeExceptionally(IOException("Peer disconnected")) }
        pending.clear()
        outgoing.values.forEach { it.payload.close(); it.file.delete() }
        outgoing.clear()
    }
}
