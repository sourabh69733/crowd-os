package org.freegram.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freegram.app.feed.FollowedFeedSync
import org.freegram.app.feed.RelayRefreshResult
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import org.freegram.app.identity.ProtectedIdentity
import org.freegram.app.media.EncodedPhoto
import org.freegram.app.media.MediaStore
import org.freegram.app.media.PhotoEncoder
import org.freegram.app.protocol.PhotoRef
import org.freegram.app.nearby.ExchangeReport
import org.freegram.app.nearby.NearbySharing
import org.freegram.app.nearby.hasPlayServices
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.Nip19
import org.freegram.app.relay.FetchResult
import org.freegram.app.relay.RelayClient
import org.freegram.app.relay.RelayDelivery
import org.freegram.app.relay.RelayRetryWorker
import org.freegram.app.relay.RetryPolicy
import org.freegram.app.store.AuthorPolicy
import org.freegram.app.store.AuthorState
import org.freegram.app.store.RoomStore

/** Screen state and actions. Survives rotation; all storage, signing and network work runs off the main thread. */
class FreegramViewModel(application: Application) : AndroidViewModel(application) {
    private val store = RoomStore.shared(application)
    private val identity = ProtectedIdentity(application)
    private val relayClient = RelayClient()
    private val media = MediaStore(File(application.filesDir, "media"))

    // Inputs
    var draft by mutableStateOf("")
    var firstRelay by mutableStateOf(store.relayUrl(0))
    var secondRelay by mutableStateOf(store.relayUrl(1))
    var authorInput by mutableStateOf("")
    var importWire by mutableStateOf("")
    var lookupId by mutableStateOf("")
    var restoreInput by mutableStateOf("")
        private set

    // Photo
    var pickedPhoto by mutableStateOf<EncodedPhoto?>(null); private set
    var pickedPreview by mutableStateOf<ImageBitmap?>(null); private set
    var selectedPhoto by mutableStateOf<ImageBitmap?>(null); private set
    var selectedPhotoNote by mutableStateOf(""); private set

    // Posts and delivery
    var selected by mutableStateOf<BulletinEvent?>(null); private set
    var canDeliver by mutableStateOf(false); private set
    var firstState by mutableStateOf(""); private set
    var secondState by mutableStateOf(""); private set
    var savedEvents by mutableStateOf<List<BulletinEvent>>(emptyList()); private set
    var feedEvents by mutableStateOf<List<BulletinEvent>>(emptyList()); private set
    var authorPolicies by mutableStateOf<List<AuthorPolicy>>(emptyList()); private set
    var feedRelayResults by mutableStateOf<List<RelayRefreshResult>>(emptyList()); private set

    // Identity
    var npub by mutableStateOf(""); private set
    var pubkeyHex by mutableStateOf(""); private set
    var revealedBackup by mutableStateOf<String?>(null); private set
    var confirmingRestore by mutableStateOf(false); private set
    var confirmingReplace by mutableStateOf(false); private set
    var confirmingDelete by mutableStateOf(false); private set

    // Nearby
    var nearbyRunning by mutableStateOf(false); private set
    var nearbyStatus by mutableStateOf(""); private set
    var nearbyLog by mutableStateOf<List<String>>(emptyList()); private set
    var autoBridge by mutableStateOf(store.autoBridge()); private set
    private var nearby: NearbySharing? = null

    // Status
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf("")
    var feedMessage by mutableStateOf(""); private set
    var identityMessage by mutableStateOf(""); private set

    private val relays get() = listOf(firstRelay.trim(), secondRelay.trim())

    init {
        viewModelScope.launch {
            try {
                val (restoredDraft, saved) = io { store.initialize(); store.draft() to store.savedEvents() }
                draft = restoredDraft
                savedEvents = saved
                refreshLists()
                saved.firstOrNull()?.let { show(it) }
                refreshIdentity()
                val retryable = io { store.retryableDeliveries(nowSeconds() - RetryPolicy.MAX_AGE_SECONDS).isNotEmpty() }
                if (retryable) RelayRetryWorker.schedule(getApplication())
                ready = true
            } catch (failure: Exception) { message = failure.message ?: "Could not open local data" }
        }
    }

    fun describe(state: String): String =
        if (state != "Sending" && RetryPolicy.isRetryable(state)) "$state · will retry automatically when online" else state

    fun saveDraft() = viewModelScope.launch {
        try { io { store.saveDraft(draft) }; message = "Draft saved locally" }
        catch (failure: Exception) { message = failure.message ?: "Could not save draft" }
    }

    fun pickPhoto(uri: Uri) = busyAction({ message = it ?: "Could not use that photo" }) {
        val encoded = withContext(Dispatchers.Default) { PhotoEncoder.encode(getApplication<Application>().contentResolver, uri) }
        pickedPhoto = encoded
        pickedPreview = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size).asImageBitmap() }
        message = "Photo ready: ${encoded.ref.width}×${encoded.ref.height}, ${encoded.bytes.size / 1024} KB. Location and camera details removed."
    }

    fun removePhoto() { pickedPhoto = null; pickedPreview = null }

    fun publish() = busyAction({ message = it ?: "Publish failed; check saved event" }) {
        message = ""
        require(relays.all { it.startsWith("wss://") } && relays.distinct().size == 2) { "Use two distinct wss:// relay URLs" }
        val text = draft
        val photo = pickedPhoto
        val signed = io {
            if (photo != null) media.put(photo.ref.sha256, photo.bytes, referencedPhotos() + photo.ref.sha256)
            val tags = if (photo != null) arrayOf(photo.ref.toTag()) else emptyArray()
            identity.withSecret { Nip01Protocol.signBulletin(it, text, nowSeconds(), tags) }.also {
                store.saveEvent(it, relays)
                store.saveDraft("")
            }
        }
        refreshLists()
        show(signed)
        draft = ""
        removePhoto()
        deliver(signed)
    }

    fun resubmit(event: BulletinEvent) = busyAction({ message = it ?: "Relay delivery failed" }) {
        message = ""
        deliver(event)
    }

    fun deleteSelected() {
        val event = selected ?: return
        if (!confirmingDelete) { confirmingDelete = true; return }
        busyAction({ message = it ?: "Could not delete" }, after = { confirmingDelete = false }) {
            io {
                store.deleteLocal(event.id)
                PhotoRef.of(event)?.let { media.removeIfUnused(it.sha256, referencedPhotos()) }
            }
            selected = null
            selectedPhoto = null
            refreshLists()
            message = "Deleted from this phone. Copies already on relays or other phones remain."
        }
    }

    fun cancelDelete() { confirmingDelete = false }

    fun open(event: BulletinEvent) = viewModelScope.launch {
        confirmingDelete = false
        try { show(event) } catch (failure: Exception) { message = failure.message ?: "Could not open post" }
    }

    fun follow() = busyAction({ feedMessage = it ?: "Could not follow key" }) {
        val input = authorInput.trim()
        val key = if (input.startsWith("npub1", ignoreCase = true)) Nip19.decodePublicKey(input).joinToString("") { "%02x".format(it) } else input.lowercase()
        io { store.setAuthorState(key, AuthorState.FOLLOWING) }
        authorInput = ""
        refreshLists()
        feedMessage = "Following key. Refresh to fetch its posts."
    }

    fun setAuthorState(pubkey: String, state: AuthorState) = busyAction({ feedMessage = it ?: "Could not update author" }) {
        io { store.setAuthorState(pubkey, state) }
        if (state == AuthorState.BLOCKED && selected?.pubkey == pubkey) selected = null
        refreshLists()
        feedMessage = "Author ${state.name.lowercase()} locally"
    }

    fun removeAuthor(pubkey: String) = busyAction({ feedMessage = it ?: "Could not remove author" }) {
        io { store.removeAuthor(pubkey) }
        refreshLists()
        feedMessage = "Local author control removed"
    }

    fun refreshFeed() = busyAction({ feedMessage = it ?: "Refresh failed" }) {
        feedMessage = "Refreshing…"
        feedRelayResults = io { FollowedFeedSync(store, relayClient).refresh(relays) }
        refreshLists()
        feedMessage = "Refresh finished. Check each relay result below."
    }

    fun importCarried() = busyAction({ message = it ?: "Import failed" }) {
        message = ""
        val imported = Nip01Protocol.parseImportedBulletin(importWire)
        io { store.saveEvent(imported, relays) }
        refreshLists()
        show(imported)
        importWire = ""
        message = "Verified and saved for explicit relay submission"
    }

    fun fetch(relay: String) = busyAction({ message = it ?: "Fetch failed" }) {
        message = ""
        when (val result = io { relayClient.fetch(relay.trim(), lookupId.trim().lowercase()) }) {
            is FetchResult.Found -> {
                val kept = io { store.saveReceivedEvent(result.event) }
                refreshLists()
                show(result.event)
                message = "Fetched and verified ${result.event.id.take(12)}… from $relay" +
                    if (kept) "" else ". Not saved: store is full of newer posts."
            }
            FetchResult.NotFound -> message = "Relay has no stored event for that ID"
            is FetchResult.Failed -> message = "Fetch failed: ${result.reason}"
        }
    }

    fun changeRestoreInput(value: String) { restoreInput = value; confirmingRestore = false }

    fun revealBackup() = viewModelScope.launch {
        try { revealedBackup = io { identity.exportNsec() } }
        catch (failure: Exception) { identityMessage = failure.message ?: "Could not read key" }
    }

    fun hideBackup() { revealedBackup = null }

    fun restore() {
        if (!confirmingRestore) { confirmingRestore = true; confirmingReplace = false; return }
        busyAction({ identityMessage = it ?: "Restore failed; current key kept" }, after = { confirmingRestore = false }) {
            io { identity.restore(restoreInput) }
            restoreInput = ""
            revealedBackup = null
            refreshIdentity()
            identityMessage = "Identity restored. New posts are signed with this key."
        }
    }

    fun replaceKey() {
        if (!confirmingReplace) { confirmingReplace = true; confirmingRestore = false; return }
        busyAction({ identityMessage = it ?: "Could not create key" }, after = { confirmingReplace = false }) {
            io { identity.replaceWithNewKey() }
            revealedBackup = null
            refreshIdentity()
            identityMessage = "New key created. Back it up and share the new public key."
        }
    }

    fun cancelConfirm() { confirmingRestore = false; confirmingReplace = false }

    fun startNearby() {
        if (!hasPlayServices(getApplication())) {
            nearbyStatus = "Nearby sharing needs Google Play services, which this phone does not have."
            return
        }
        val sharing = nearby ?: NearbySharing(getApplication(), store, media, viewModelScope, object : NearbySharing.Listener {
            override fun onStatus(text: String) { nearbyStatus = text }
            override fun onExchange(peer: String, report: ExchangeReport) {
                val line = "$peer: ${report.outcome}. Received ${report.received} posts and ${report.photosReceived} photos, " +
                    "rejected ${report.rejected}; sent ${report.sent} posts (${report.acknowledged} confirmed stored) and ${report.photosSent} photos."
                nearbyLog = (listOf(line) + nearbyLog).take(10)
                viewModelScope.launch { refreshLists(); selected?.let { show(it) } }
            }
        }).also { nearby = it }
        sharing.start()
        nearbyRunning = true
    }

    fun stopNearby() {
        nearby?.stop()
        nearbyRunning = false
    }

    fun changeAutoBridge(enabled: Boolean) {
        store.setAutoBridge(enabled)
        autoBridge = enabled
    }

    fun nearbyPermissionDenied() { nearbyStatus = "Nearby sharing needs Bluetooth and nearby-device permissions." }

    override fun onCleared() { nearby?.stop() }

    private suspend fun deliver(event: BulletinEvent) {
        val targets = relays
        require(targets.all { it.startsWith("wss://") } && targets.distinct().size == 2) { "Use two distinct wss:// relay URLs" }
        io {
            store.saveEvent(event, targets)
            store.setRelayUrl(0, targets[0])
            store.setRelayUrl(1, targets[1])
        }
        val results = io {
            RelayDelivery(store, relayClient::publish).deliver(event, targets) { relay, state ->
                withContext(Dispatchers.Main) {
                    if (selected?.id == event.id) { if (relay == targets[0]) firstState = state else secondState = state }
                }
            }
        }
        if (results.values.any(RetryPolicy::isRetryable)) RelayRetryWorker.schedule(getApplication())
    }

    private suspend fun show(event: BulletinEvent) {
        val targets = relays
        val (deliverable, first, second) = io {
            Triple(store.deliveryTargets(event.id).isNotEmpty(), store.relayState(event.id, targets[0]), store.relayState(event.id, targets[1]))
        }
        selected = event
        canDeliver = deliverable
        firstState = first
        secondState = second
        val ref = PhotoRef.of(event)
        selectedPhoto = ref?.let { r -> io { media.path(r.sha256)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } } }
        selectedPhotoNote = when {
            ref == null -> ""
            selectedPhoto != null -> "Photo matches its fingerprint ${ref.sha256.take(12)}…"
            else -> "This post has a photo that is not on this phone yet. It can arrive from a nearby phone."
        }
    }

    private suspend fun referencedPhotos(): Set<String> = io { store.savedEvents().mapNotNull { PhotoRef.of(it)?.sha256 }.toSet() }

    /** Reloads lists after any local change, e.g. a nearby exchange. */
    suspend fun refreshLists() {
        val (saved, feed, policies) = io { Triple(store.savedEvents(), store.feedEvents(), store.authorPolicies()) }
        savedEvents = saved
        feedEvents = feed
        authorPolicies = policies
    }

    private suspend fun refreshIdentity() {
        val (bech, hex) = io { identity.npub() to identity.publicKeyHex() }
        npub = bech
        pubkeyHex = hex
    }

    private fun busyAction(onError: (String?) -> Unit, after: () -> Unit = {}, action: suspend () -> Unit) = viewModelScope.launch {
        busy = true
        try { action() } catch (failure: Exception) { onError(failure.message) }
        finally { after(); busy = false }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun nowSeconds() = System.currentTimeMillis() / 1000
}
