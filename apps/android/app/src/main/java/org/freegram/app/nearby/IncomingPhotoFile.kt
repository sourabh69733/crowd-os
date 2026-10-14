package org.freegram.app.nearby

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import org.freegram.app.protocol.PhotoRef

/** A bounded requested file, completed only after both FILE and SUCCESS callbacks. */
class IncomingPhotoFile {
    private data class Slot(val id: Long, val size: Int, val done: CompletableDeferred<Unit> = CompletableDeferred(),
        var open: (() -> InputStream)? = null, var dispose: (() -> Unit)? = null, var success: Boolean = false)
    private var slot: Slot? = null
    private val used = mutableSetOf<Long>()

    @Synchronized fun expect(id: Long, size: Int) {
        require(size in 1..PhotoRef.MAX_BYTES)
        check(slot == null && used.size < NearbyFrames.MAX_FILES && used.add(id)) { "Repeated or overlapping file" }
        slot = Slot(id, size)
    }
    @Synchronized fun file(id: Long, open: () -> InputStream, dispose: () -> Unit): Boolean {
        val s = slot
        if (s == null || s.id != id || s.open != null || s.done.isCompleted) { dispose(); return false }
        s.open = open; s.dispose = dispose
        if (s.success) s.done.complete(Unit)
        return true
    }
    @Synchronized fun update(id: Long, transferred: Long, total: Long, success: Boolean?) {
        val s = slot?.takeIf { it.id == id } ?: return
        if (transferred > s.size || total != s.size.toLong() || success == false) {
            s.done.completeExceptionally(IOException("Invalid or failed file transfer"))
        } else if (success == true) {
            if (transferred != s.size.toLong()) s.done.completeExceptionally(IOException("Incomplete file"))
            else { s.success = true; if (s.open != null) s.done.complete(Unit) }
        }
    }
    suspend fun receive(id: Long): ByteArray {
        val s = synchronized(this) { slot?.takeIf { it.id == id } } ?: throw IOException("File not expected")
        return try {
            s.done.await()
            s.open!!.invoke().use { input ->
                val bytes = ByteArray(s.size)
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read <= 0) throw IOException("Incomplete file")
                    offset += read
                }
                if (input.read() != -1) throw IOException("File too large")
                bytes
            }
        } finally {
            synchronized(this) {
                s.dispose?.invoke(); s.dispose = null
                if (slot === s) slot = null
            }
        }
    }
    @Synchronized fun cancel() {
        slot?.let { s ->
            s.done.completeExceptionally(IOException("File transfer cancelled"))
            s.dispose?.invoke(); s.dispose = null
        }
    }
}
