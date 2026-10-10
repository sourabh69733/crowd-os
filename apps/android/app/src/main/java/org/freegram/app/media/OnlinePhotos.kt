package org.freegram.app.media

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.freegram.app.identity.ProtectedIdentity
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.PhotoRef
import org.freegram.app.store.RoomStore

/** Runs on IO. Upload receipts are local and never alter a carried signed post. */
class OnlinePhotos(context: Context, private val store: RoomStore, private val media: MediaStore,
    private val identity: ProtectedIdentity, private val client: BlossomClient = BlossomClient()) {
    private val prefs = context.getSharedPreferences("freegram_media_uploads", Context.MODE_PRIVATE)

    suspend fun sync(): Boolean {
        val allRefs = store.savedEvents().mapNotNull(PhotoRef::of).distinctBy { it.sha256 }
        val last = prefs.getString("cursor", null)
        val start = (allRefs.indexOfFirst { it.sha256 == last } + 1).let { if (allRefs.isEmpty()) 0 else it % allRefs.size }
        val refs = allRefs.drop(start) + allRefs.take(start)
        val uploaded = prefs.getStringSet("uploaded", emptySet()).orEmpty().intersect(refs.map { it.sha256 }.toSet()).toMutableSet()
        var remaining = false
        var attempted = 0
        var cursor = last
        for (ref in refs) {
            if (ref.sha256 in uploaded && media.has(ref.sha256)) continue
            if (attempted++ >= 8) { remaining = true; break }
            cursor = ref.sha256
            try {
                if (!store.savedEvents().any { PhotoRef.of(it) == ref }) continue
                val local = media.read(ref.sha256)
                if (local != null) {
                    if (client.upload(ref, local) { tags -> identity.withSecret { Nip01Protocol.signEvent(it, 24242, "Upload photo", System.currentTimeMillis()/1000, tags) } }) uploaded.add(ref.sha256)
                    else remaining = true
                } else {
                    val bytes = client.download(ref)
                    if (bytes == null) { remaining = true; continue }
                    if (PhotoEncoder.isValidPhoto(bytes, ref) && store.savedEvents().any { PhotoRef.of(it) == ref }) {
                        media.put(ref.sha256, bytes, refs.map { it.sha256 }.toSet())
                        PhotoChanges.changed()
                        uploaded.add(ref.sha256)
                    } else remaining = true
                }
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { remaining = true }
        }
        check(prefs.edit().putStringSet("uploaded", uploaded).putString("cursor", cursor).commit())
        return remaining
    }
}

object PhotoChanges {
    private val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val updates: kotlinx.coroutines.flow.StateFlow<Long> = revision
    fun changed() { revision.value += 1 }
}
