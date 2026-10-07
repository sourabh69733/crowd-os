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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.freegram.app.identity.PanicWipe
import org.freegram.app.identity.ProtectedIdentity
import org.freegram.app.media.EncodedPhoto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.freegram.app.moderation.Discover
import org.freegram.app.moderation.HideList
import org.freegram.app.moderation.SuggestList
import org.freegram.shared.model.DiscoverUi
import org.freegram.shared.model.FoundPersonUi
import org.freegram.app.moderation.ModerationMessage
import org.freegram.app.moderation.PrivateMessages
import org.freegram.app.store.Maintainer
import org.freegram.app.media.MediaStore
import org.freegram.app.media.PhotoEncoder
import kotlinx.coroutines.withTimeoutOrNull
import org.freegram.app.protocol.AccountDeletion
import org.freegram.app.protocol.Likes
import org.freegram.app.protocol.PhotoRef
import org.freegram.app.protocol.Replies
import org.freegram.app.protocol.ProfileEvent
import org.freegram.app.nearby.NearbyService
import org.freegram.app.nearby.NearbyState
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
import org.freegram.shared.model.FreegramUi
import org.freegram.shared.model.InboxItemUi
import org.freegram.shared.model.MaintainerUi
import org.freegram.shared.model.MeUi
import org.freegram.shared.model.PersonState
import org.freegram.shared.model.PersonUi
import org.freegram.shared.model.StorageUi
import org.freegram.shared.model.NearbyActivity
import org.freegram.shared.model.NearbyUi
import org.freegram.shared.model.PhotoUi
import org.freegram.shared.model.PostSource
import org.freegram.shared.model.PostUi

/** Screen state and actions. Survives rotation; all storage, signing and network work runs off the main thread. */
class FreegramViewModel(application: Application) : AndroidViewModel(application), FreegramUi {
    private val store = RoomStore.shared(application)
    private val identity = ProtectedIdentity(application)
    private val relayClient = RelayClient()
    private val media = MediaStore.shared(application)

    // Inputs
    override var draft by mutableStateOf("")
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
    var devSelected by mutableStateOf<BulletinEvent?>(null); private set
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
    var backupPassword by mutableStateOf("")
    var backupPasswordConfirm by mutableStateOf("")
    var encryptedBackup by mutableStateOf<String?>(null); private set
    var restorePassword by mutableStateOf("")
    var confirmingRestore by mutableStateOf(false); private set
    var confirmingReplace by mutableStateOf(false); private set
    var confirmingDelete by mutableStateOf(false); private set

    // Nearby
    var nearbyRunning by mutableStateOf(false); private set
    var nearbyStatus by mutableStateOf(""); private set
    var nearbyLog by mutableStateOf<List<String>>(emptyList()); private set
    var autoBridge by mutableStateOf(store.autoBridge()); private set

    // Moderation
    var maintainers by mutableStateOf<List<Maintainer>>(emptyList()); private set
    var maintainerInput by mutableStateOf("")
    var hiddenCount by mutableStateOf(0); private set
    var myHideList by mutableStateOf<HideList?>(null); private set
    var moderationMessage by mutableStateOf(""); private set
    var reportReason by mutableStateOf(REPORT_REASONS.first())
    var reportNote by mutableStateOf("")
    var reportStatus by mutableStateOf(""); private set
    var appealText by mutableStateOf("")
    var appealPostId by mutableStateOf("")
    var inbox by mutableStateOf<List<ModerationMessage>>(emptyList()); private set

    // Status
    override var ready by mutableStateOf(false); private set
    override var busy by mutableStateOf(false); private set
    var message by mutableStateOf("")
    var feedMessage by mutableStateOf(""); private set
    var identityMessage by mutableStateOf(""); private set

    private val relays get() = listOf(firstRelay.trim(), secondRelay.trim())

    // ----- Shared screens (FreegramUi). Declared before init because init already updates them. -----
    override var online by mutableStateOf(false); private set
    override var posts by mutableStateOf<List<PostUi>>(emptyList()); private set
    private var detailId by mutableStateOf<String?>(null)
    override val selected: PostUi? get() = detailId?.let { id ->
        posts.firstOrNull { it.id == id } ?: discoverPosts.firstOrNull { it.id == id } ?: threadPosts.firstOrNull { it.id == id }
    }
    override val draftPhoto: ImageBitmap? get() = pickedPreview
    override var toast by mutableStateOf<String?>(null); private set
    override val reportReasons: List<String> get() = REPORT_REASONS
    override val canReport: Boolean get() = maintainers.any { it.enabled && it.pubkey != pubkeyHex }
    override val isMaintainer: Boolean get() = pubkeyHex.isNotEmpty() && maintainers.any { it.pubkey == pubkeyHex }
    private var backupDone by mutableStateOf(store.backupDone())
    private var names by mutableStateOf<Map<String, String>>(emptyMap())
    override val me: MeUi get() = MeUi(names[pubkeyHex] ?: shortKey(npub), names[pubkeyHex].orEmpty(), npub, npub.takeLast(4), hueOf(pubkeyHex), backupDone)
    override var needsOnboarding by mutableStateOf(!store.onboarded()); private set
    override var freshBackup by mutableStateOf<String?>(null); private set
    private var nearbyCounts by mutableStateOf(Triple(0, 0, 0))
    private var nearbyActivity by mutableStateOf<List<Pair<String, Long>>>(emptyList())
    override val nearby: NearbyUi get() = NearbyUi(
        nearbyRunning, nearbyStatus, nearbyCounts.first, nearbyCounts.second, nearbyCounts.third,
        nearbyActivity.map { (text, at) -> NearbyActivity(text, ago(at / 1000)) }, autoBridge,
    )
    private var photoBytes by mutableStateOf(0L)

    // ----- Replies and likes -----
    /** Replies fetched from servers (not stored, so they can't crowd out posts). Stored replies come from posting or Nearby. */
    private var fetchedReplies by mutableStateOf<Map<String, BulletinEvent>>(emptyMap())
    private var likeMap by mutableStateOf<Map<String, Set<String>>>(emptyMap())
    private var replyCounts by mutableStateOf<Map<String, Int>>(emptyMap())
    private var allReplies by mutableStateOf<List<BulletinEvent>>(emptyList())
    private var threadPosts by mutableStateOf<List<PostUi>>(emptyList())
    override val thread: List<PostUi> get() = threadPosts

    // ----- Discover -----
    private var discoverEvents by mutableStateOf<List<BulletinEvent>>(emptyList())
    private var discoverPosts by mutableStateOf<List<PostUi>>(emptyList())
    private var suggestedKeys by mutableStateOf<List<String>>(emptyList())
    private var discoverLoading by mutableStateOf(false)
    private var discoverNote by mutableStateOf("")
    private var discoverOptIn by mutableStateOf(store.showInDiscover())
    override var mySuggestions by mutableStateOf<Set<String>>(emptySet()); private set
    override val discover: DiscoverUi get() {
        val policies = authorPolicies.associate { it.pubkey to it.state }
        val suggested = suggestedKeys.filter { it != pubkeyHex && policies[it].let { s -> s != AuthorState.BLOCKED && s != AuthorState.MUTED } }
        return DiscoverUi(discoverPosts, suggested.map { found(it, policies) }, discoverLoading, discoverNote,
            maintainers.any { it.enabled }, discoverOptIn)
    }

    // ----- Settings screens (SettingsUi) -----
    override val people: List<PersonUi> get() = authorPolicies.map { p ->
        personOf(p.pubkey, when (p.state) {
            AuthorState.FOLLOWING -> PersonState.Following
            AuthorState.MUTED -> PersonState.Muted
            AuthorState.BLOCKED -> PersonState.Blocked
        })
    }
    override val maintainerList: List<MaintainerUi> get() = maintainers.map {
        MaintainerUi(it.pubkey, labelOf(it.pubkey), npubOf(it.pubkey).takeLast(4), hueOf(it.pubkey), it.enabled, it.pubkey == pubkeyHex)
    }
    override val myHiddenPosts: List<String> get() = myHideList?.posts?.sorted().orEmpty()
    override val myHiddenAuthors: List<PersonUi> get() = myHideList?.authors?.sorted()?.map { personOf(it, PersonState.Blocked) }.orEmpty()
    override val inboxList: List<InboxItemUi> get() = inbox.map { m ->
        InboxItemUi(m.type == ModerationMessage.Type.REPORT, labelOf(m.from), m.reason, m.note, m.postId, ago(m.createdAt),
            alreadyHidden = m.postId != null && myHideList?.posts?.contains(m.postId) == true)
    }
    override val storageInfo: StorageUi get() = StorageUi(savedEvents.size, RoomStore.MAX_BULLETINS, photoBytes, MediaStore.MAX_BYTES, hiddenCount)
    override val relayUrls: List<String> get() = relays
    override val plainKey: String? get() = revealedBackup

    private val thumbnails = HashMap<String, ImageBitmap>()
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { viewModelScope.launch { online = true } }
        override fun onLost(network: Network) { viewModelScope.launch { online = hasInternet() } }
    }

    init {
        online = hasInternet()
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        observeNearby()
        viewModelScope.launch {
            try {
                val (restoredDraft, saved) = io { store.initialize(); store.draft() to store.savedEvents() }
                draft = restoredDraft
                savedEvents = saved
                refreshLists()
                saved.firstOrNull()?.let { show(it) }
                refreshIdentity()
                applyDefaultMaintainer()
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

    fun pickPhoto(uri: Uri) = busyAction({ toast = it ?: "Could not use that photo" }) {
        val encoded = withContext(Dispatchers.Default) { PhotoEncoder.encode(getApplication<Application>().contentResolver, uri) }
        pickedPhoto = encoded
        pickedPreview = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size).asImageBitmap() }
        message = "Photo ready: ${encoded.ref.width}×${encoded.ref.height}, ${encoded.bytes.size / 1024} KB. Location and camera details removed."
    }

    override fun removePhoto() { pickedPhoto = null; pickedPreview = null }

    override fun publish() { publishJob() }

    private fun publishJob() = busyAction({ toast = it ?: "Could not post. Your text is still in the draft." }) {
        message = ""
        require(relays.all { it.startsWith("wss://") } && relays.distinct().size == 2) { "Use two distinct wss:// relay URLs" }
        val text = draft
        val photo = pickedPhoto
        val signed = io {
            if (photo != null) media.put(photo.ref.sha256, photo.bytes, referencedPhotos() + photo.ref.sha256)
            val tags = buildList {
                if (photo != null) add(photo.ref.toTag())
                if (discoverOptIn) add(Discover.tag())
            }.toTypedArray()
            identity.withSecret { Nip01Protocol.signBulletin(it, text, nowSeconds(), tags) }.also {
                store.saveEvent(it, relays)
                store.saveDraft("")
            }
        }
        refreshLists()
        show(signed)
        draft = ""
        removePhoto()
        toast = if (online) "Posted. Sending to your servers." else "Saved. Nearby phones can get it now; servers once you're online."
        deliver(signed)
        refreshLists()
    }

    fun resubmit(event: BulletinEvent) = busyAction({ message = it ?: "Relay delivery failed" }) {
        message = ""
        deliver(event)
    }

    fun deleteSelected() {
        val event = devSelected ?: return
        if (!confirmingDelete) { confirmingDelete = true; return }
        busyAction({ message = it ?: "Could not delete" }, after = { confirmingDelete = false }) {
            io {
                store.deleteLocal(event.id)
                PhotoRef.of(event)?.let { media.removeIfUnused(it.sha256, referencedPhotos()) }
            }
            devSelected = null
            selectedPhoto = null
            refreshLists()
            message = "Deleted from this phone. Copies already on relays or other phones remain."
        }
    }

    fun cancelDelete() { confirmingDelete = false }

    fun addMaintainer() = busyAction({ moderationMessage = it ?: "Could not add maintainer" }) {
        io { store.setMaintainer(parseKey(maintainerInput), true) }
        maintainerInput = ""
        refreshLists()
        moderationMessage = "Maintainer followed. Refresh hide lists to fetch their list."
    }

    fun setMaintainerEnabled(pubkey: String, enabled: Boolean) = busyAction({ moderationMessage = it ?: "Could not update" }) {
        io { store.setMaintainer(pubkey, enabled) }
        refreshLists()
    }

    fun removeMaintainer(pubkey: String) = busyAction({ moderationMessage = it ?: "Could not remove" }) {
        io { store.removeMaintainer(pubkey) }
        refreshLists()
        moderationMessage = "Stopped following that maintainer; their hidden posts show again."
    }

    fun refreshHideLists() = busyAction({ moderationMessage = it ?: "Refresh failed" }) {
        val results = syncHideLists()
        refreshLists()
        moderationMessage = results.joinToString("\n")
    }

    /** Maintainer action: adds the selected post or its author to this phone's own public hide list. */
    fun hideSelected(author: Boolean) = busyAction({ message = it ?: "Could not hide" }) {
        val event = devSelected ?: return@busyAction
        editMyHideList { list -> if (author) list.copy(authors = list.authors + event.pubkey) else list.copy(posts = list.posts + event.id) }
        devSelected = null
        selectedPhoto = null
        refreshLists()
        message = if (author) "Author added to your public hide list." else "Post added to your public hide list."
    }

    /** Maintainer action from the inbox: hides a reported post by ID, even if this phone does not hold it. */
    fun hidePost(id: String) = busyAction({ moderationMessage = it ?: "Could not hide" }) {
        editMyHideList { it.copy(posts = it.posts + id) }
        refreshLists()
    }

    fun unhidePost(id: String) = busyAction({ moderationMessage = it ?: "Could not unhide" }) {
        editMyHideList { it.copy(posts = it.posts - id) }
        refreshLists()
    }

    fun unhideAuthor(key: String) = busyAction({ moderationMessage = it ?: "Could not unhide" }) {
        editMyHideList { it.copy(authors = it.authors - key) }
        refreshLists()
    }

    /** Sends a private report on the selected post to every enabled maintainer except this phone's own key. */
    fun sendReport() = busyAction({ reportStatus = it ?: "Report failed" }) {
        val event = devSelected ?: return@busyAction
        val targets = maintainers.filter { it.enabled && it.pubkey != pubkeyHex }.map { it.pubkey }
        require(targets.isNotEmpty()) { "Follow at least one maintainer first" }
        val sent = sendPrivate(targets, ModerationMessage(ModerationMessage.Type.REPORT, pubkeyHex, nowSeconds(), event.id, reportReason, reportNote.trim()))
        reportNote = ""
        reportStatus = "Private report sent to ${targets.size} maintainer(s). $sent Only they can read it; they will know it came from your key."
    }

    fun sendAppeal(maintainer: String) = busyAction({ moderationMessage = it ?: "Appeal failed" }) {
        val postId = appealPostId.trim().lowercase().takeIf { it.isNotEmpty() }
        require(postId == null || (postId.length == 64 && postId.all { it in '0'..'9' || it in 'a'..'f' })) { "Post ID must be 64 hex characters" }
        require(appealText.isNotBlank()) { "Write why the decision should change" }
        val sent = sendPrivate(listOf(maintainer), ModerationMessage(ModerationMessage.Type.APPEAL, pubkeyHex, nowSeconds(), postId, "appeal", appealText.trim()))
        appealText = ""; appealPostId = ""
        moderationMessage = "Private appeal sent. $sent"
    }

    /** Fetches and decrypts reports and appeals addressed to this phone's key, as a maintainer. */
    fun checkInbox() = busyAction({ moderationMessage = it ?: "Could not check messages" }) {
        val wraps = relays.flatMap { relay -> io { relayClient.fetchWraps(relay, pubkeyHex) }.events }.distinctBy { it.id }
        val opened = io { identity.withSecret { secret -> wraps.mapNotNull { PrivateMessages.unwrap(secret, it) } } }
        inbox = opened.distinctBy { listOf(it.from, it.createdAt, it.postId, it.reason, it.note) }.sortedByDescending { it.createdAt }
        moderationMessage = "${inbox.size} reports and appeals (${wraps.size - opened.size} unreadable or not for this app)."
    }

    private suspend fun sendPrivate(recipients: List<String>, message: ModerationMessage): String {
        val wraps = io { identity.withSecret { secret -> recipients.map { PrivateMessages.wrap(secret, it, message, nowSeconds()) } } }
        val accepted = wraps.sumOf { wrap -> relays.count { relay -> io { runCatching { relayClient.publish(relay, wrap) }.getOrDefault("Network error") } == "Accepted" } }
        val total = wraps.size * relays.size
        return "Relays accepted $accepted of $total copies." + if (accepted < total) " Try again when online if none were accepted." else ""
    }

    /** Signs a new version of this phone's hide list, applies it here, and sends it to both relays. */
    private suspend fun editMyHideList(change: (HideList) -> HideList) {
        val me = pubkeyHex
        val signed = io {
            if (store.maintainers().none { it.pubkey == me }) store.setMaintainer(me, true)
            val current = store.hideList(me) ?: HideList(me, 0, emptySet(), emptySet())
            val next = change(current)
            require(next.posts.size + next.authors.size <= HideList.MAX_ENTRIES) { "Hide list is full" }
            val at = maxOf(nowSeconds(), current.createdAt + 1)
            identity.withSecret { Nip01Protocol.signEvent(it, HideList.KIND, "", at, next.toTags()) }
                .also { check(store.saveHideList(it)) }
        }
        val outcomes = relays.map { relay -> relay to io { runCatching { relayClient.publish(relay, signed) }.getOrElse { "Network error" } } }
        moderationMessage = "Hide list published: " + outcomes.joinToString("; ") { (relay, state) -> "$relay $state" } +
            if (outcomes.any { it.second != "Accepted" }) ". Edit again when online to resend." else ""
    }

    override fun updateDraft(text: String) { draft = text }
    override fun open(postId: String) {
        detailId = postId
        viewModelScope.launch {
            rebuildThread()
            val root = findEvent(postId)?.let { Replies.rootOf(it) } ?: postId
            if (online) runCatching { fetchThreadsFor(listOf(root)) }
        }
    }

    /** A post this phone knows: stored, in Discover, or a fetched reply. */
    private suspend fun findEvent(id: String): BulletinEvent? =
        savedEvents.firstOrNull { it.id == id } ?: discoverEvents.firstOrNull { it.id == id } ?: fetchedReplies[id]
            ?: io { store.savedEvents().firstOrNull { it.id == id } }

    /** Fetches replies and likes for [postIds] from both servers, then refreshes counts and names. */
    private suspend fun fetchThreadsFor(postIds: List<String>) {
        if (postIds.isEmpty()) return
        val found = postIds.distinct().chunked(100).flatMap { ids ->
            relays.flatMap { relay -> io { relayClient.fetchThreads(relay, ids) }.events }
        }.distinctBy { it.id }
        io { found.filter { it.kind == Likes.KIND }.forEach { store.saveLike(it) } }
        val replies = found.filter { it.kind == 1 }
        if (replies.isNotEmpty()) {
            fetchedReplies = (fetchedReplies + replies.associateBy { it.id }).values
                .sortedByDescending { it.createdAt }.take(MAX_FETCHED_REPLIES).associateBy { it.id }
        }
        val unnamed = replies.map { it.pubkey }.distinct().filter { it !in names && it != pubkeyHex }.take(50)
        if (unnamed.isNotEmpty()) for (relay in relays) {
            val profiles = io { relayClient.fetchProfiles(relay, unnamed) }.events
            io { profiles.forEach { store.saveProfile(it) } }
        }
        refreshLists()
    }

    /** Replies under the open post (or, for an open reply, nothing: replies show only under the top-level post). */
    private suspend fun rebuildThread() {
        val id = detailId ?: run { threadPosts = emptyList(); return }
        val followed = authorPolicies.filter { it.state == AuthorState.FOLLOWING }.map { it.pubkey }.toSet()
        val hops = io { store.hopsById() }
        val deliveries = io { store.deliveryStates() }
        threadPosts = io {
            allReplies.filter { Replies.rootOf(it) == id }.sortedBy { it.createdAt }
                .map { toPostUi(it, hops[it.id] ?: 0, deliveries[it.id].orEmpty(), followed) }
        }
    }

    override fun like(postId: String, on: Boolean) {
        viewModelScope.launch {
            try {
                val me = pubkeyHex
                if (on) {
                    val post = findEvent(postId) ?: return@launch
                    if (io { store.likeBy(postId, me) } != null) return@launch
                    val like = io { identity.withSecret { Likes.sign(it, post, nowSeconds()) } }
                    io { store.saveLike(like, mine = true) }
                    refreshLists()
                    if (online && publishEverywhere(like)) io { store.markLikeSent(like.id) }
                } else {
                    val row = io { store.likeBy(postId, me) } ?: return@launch
                    io { store.deleteLike(row.id) }
                    refreshLists()
                    // A like a server already has needs a signed removal; one never sent just disappears.
                    if (row.sent && online) {
                        val like = Nip01Protocol.fromJson(row.wire, Likes.MAX_BYTES)
                        publishEverywhere(io { identity.withSecret { Likes.signUnlike(it, like, nowSeconds()) } })
                    }
                }
            } catch (failure: Exception) {
                toast = failure.message ?: "Could not update like"
            }
        }
    }

    /** Sends likes made while offline. */
    private suspend fun retryLikes() {
        for (row in io { store.unsentLikes() }) {
            val like = runCatching { Nip01Protocol.fromJson(row.wire, Likes.MAX_BYTES) }.getOrNull() ?: continue
            if (publishEverywhere(like)) io { store.markLikeSent(row.id) }
        }
    }

    /** True if at least one server accepted [event]. */
    private suspend fun publishEverywhere(event: BulletinEvent): Boolean =
        relays.count { relay -> io { runCatching { relayClient.publish(relay, event) }.getOrDefault("Network error") } == "Accepted" } > 0

    override fun reply(postId: String, text: String) {
        busyAction({ toast = it ?: "Could not reply" }) {
            require(text.isNotBlank()) { "Write something first" }
            require(relays.all { it.startsWith("wss://") } && relays.distinct().size == 2) { "Use two distinct wss:// relay URLs" }
            val parent = findEvent(postId) ?: error("That post isn't on this phone anymore")
            val rootAuthor = (Replies.rootOf(parent)?.let { findEvent(it) } ?: parent).pubkey
            val signed = io {
                identity.withSecret { Nip01Protocol.signBulletin(it, text, nowSeconds(), Replies.tags(parent, rootAuthor)) }
                    .also { store.saveEvent(it, relays) }
            }
            refreshLists()
            toast = if (online) "Reply posted." else "Reply saved. It goes to nearby phones now and to servers when you're online."
            deliver(signed)
            refreshLists()
        }
    }
    override fun closeDetail() { detailId = null }
    override fun dismissToast() { toast = null }
    fun showToast(text: String) { toast = text }

    override fun refresh() {
        busyAction({ toast = it ?: "Refresh failed" }) {
            val following = authorPolicies.any { it.state == AuthorState.FOLLOWING }
            if (online && following) syncFeed()
            if (online && maintainers.isNotEmpty()) syncHideLists()
            if (online) syncProfiles()
            if (online) {
                retryLikes()
                runCatching { fetchThreadsFor(posts.filter { it.replyTo == null }.map { it.id }.take(100)) }
            }
            if (io { store.retryableDeliveries(nowSeconds() - RetryPolicy.MAX_AGE_SECONDS).isNotEmpty() }) RelayRetryWorker.schedule(getApplication())
            refreshLists()
            toast = when {
                !online -> "No internet. Showing what's on this phone."
                !following -> "Follow someone to get their posts here."
                else -> "Up to date."
            }
        }
    }

    override fun follow(authorKey: String) = changePolicy(authorKey, AuthorState.FOLLOWING, "Following. Their posts appear in Home after the next refresh.")
    override fun mute(authorKey: String) = changePolicy(authorKey, AuthorState.MUTED, "Muted. Their posts are hidden on this phone.")
    override fun block(authorKey: String) = changePolicy(authorKey, AuthorState.BLOCKED, "Blocked. Their posts are hidden and not passed on.")

    private fun changePolicy(key: String, state: AuthorState, done: String) {
        busyAction({ toast = it ?: "Could not update" }) {
            io { store.setAuthorState(key, state) }
            if (state != AuthorState.FOLLOWING) detailId = null
            refreshLists()
            toast = done
        }
    }

    override fun deleteLocal(postId: String) {
        busyAction({ toast = it ?: "Could not delete" }) {
            val event = io { store.savedEvents() }.firstOrNull { it.id == postId }
            if (event == null) { toast = "This post isn't saved on this phone."; return@busyAction }
            io {
                store.deleteLocal(event.id)
                PhotoRef.of(event)?.let { media.removeIfUnused(it.sha256, referencedPhotos()) }
            }
            detailId = null
            refreshLists()
            toast = "Deleted from this phone. Copies on servers and other phones stay."
        }
    }

    override fun report(postId: String, reason: String, note: String) {
        busyAction({ toast = it ?: "Report failed" }) {
            val targets = maintainers.filter { it.enabled && it.pubkey != pubkeyHex }.map { it.pubkey }
            require(targets.isNotEmpty()) { "Follow a maintainer first (Settings → Maintainers)" }
            val sent = sendPrivate(targets, ModerationMessage(ModerationMessage.Type.REPORT, pubkeyHex, nowSeconds(), postId, reason, note.trim()))
            toast = "Private report to ${targets.size} maintainer(s): $sent"
        }
    }

    override fun hideForFollowers(postId: String) {
        busyAction({ toast = it ?: "Could not hide" }) {
            editMyHideList { it.copy(posts = it.posts + postId) }
            detailId = null
            refreshLists()
            toast = "Hidden for everyone who follows you as a maintainer."
        }
    }

    override fun setNearby(on: Boolean) { if (on) startNearby() else stopNearby() }
    override fun setAutoPublish(on: Boolean) = changeAutoBridge(on)

    // ----- Settings actions -----

    override fun followId(text: String) {
        val key = runCatching { parseKey(text) }.getOrNull()
        when {
            key == null || key.length != 64 -> toast = "That isn't a valid Freegram ID."
            key == pubkeyHex -> toast = "That's your own ID."
            else -> follow(key)
        }
    }

    override fun unfollow(key: String) = removePolicy(key, "Unfollowed.")
    override fun unblock(key: String) = removePolicy(key, "Unblocked.")
    override fun unmute(key: String) = changePolicy(key, AuthorState.FOLLOWING, "Unmuted. You follow them again.")

    private fun removePolicy(key: String, done: String) {
        busyAction({ toast = it ?: "Could not update" }) {
            io { store.removeAuthor(key) }
            refreshLists()
            toast = done
        }
    }

    override fun addMaintainerId(text: String) {
        val key = runCatching { parseKey(text) }.getOrNull()?.takeIf { it.length == 64 }
        if (key == null) { toast = "That isn't a valid Freegram ID."; return }
        busyAction({ toast = it ?: "Could not add maintainer" }) {
            io { store.setMaintainer(key, true) }
            refreshLists()
            if (online) { syncHideLists(); refreshLists() }
            toast = "Maintainer added. Their hide list now applies on this phone."
        }
    }

    override fun setMaintainerOn(key: String, on: Boolean) {
        busyAction({ toast = it ?: "Could not update" }) {
            io { store.setMaintainer(key, on) }
            refreshLists()
            toast = if (on) "Maintainer on." else "Maintainer off. Posts they hid show again."
        }
    }

    override fun dropMaintainer(key: String) {
        busyAction({ toast = it ?: "Could not remove" }) {
            io { store.removeMaintainer(key) }
            refreshLists()
            toast = "Maintainer removed."
        }
    }

    override fun refreshMaintainers() {
        busyAction({ toast = it ?: "Could not refresh" }) {
            require(online) { "No internet. Hide lists refresh when you're online." }
            syncHideLists()
            refreshLists()
            toast = "Hide lists updated."
        }
    }

    override fun appeal(maintainer: String, text: String, postId: String) {
        busyAction({ toast = it ?: "Appeal failed" }) {
            val id = postId.trim().lowercase().takeIf { it.isNotEmpty() }
            require(id == null || (id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' })) { "Post ID must be 64 characters (0-9, a-f)" }
            require(text.isNotBlank()) { "Write why the decision should change" }
            val sent = sendPrivate(listOf(maintainer), ModerationMessage(ModerationMessage.Type.APPEAL, pubkeyHex, nowSeconds(), id, "appeal", text.trim()))
            toast = "Private appeal: $sent"
        }
    }

    override fun loadInbox() {
        busyAction({ toast = it ?: "Could not check messages" }) {
            require(online) { "No internet. Check again when you're online." }
            val wraps = relays.flatMap { relay -> io { relayClient.fetchWraps(relay, pubkeyHex) }.events }.distinctBy { it.id }
            val opened = io { identity.withSecret { secret -> wraps.mapNotNull { PrivateMessages.unwrap(secret, it) } } }
            inbox = opened.distinctBy { listOf(it.from, it.createdAt, it.postId, it.reason, it.note) }.sortedByDescending { it.createdAt }
            toast = if (inbox.isEmpty()) "No reports or appeals." else "${inbox.size} reports and appeals."
        }
    }

    override fun hideReported(postId: String) {
        busyAction({ toast = it ?: "Could not hide" }) {
            editMyHideList { it.copy(posts = it.posts + postId) }
            refreshLists()
            toast = "Hidden for everyone who follows you as a maintainer."
        }
    }

    override fun unhidePostId(id: String) {
        busyAction({ toast = it ?: "Could not unhide" }) {
            editMyHideList { it.copy(posts = it.posts - id) }
            refreshLists()
            toast = "Post unhidden."
        }
    }

    override fun unhideAuthorKey(key: String) {
        busyAction({ toast = it ?: "Could not unhide" }) {
            editMyHideList { it.copy(authors = it.authors - key) }
            refreshLists()
            toast = "Person unhidden."
        }
    }

    override fun saveRelays(first: String, second: String) {
        runCatching {
            store.setRelayUrl(0, first.trim())
            store.setRelayUrl(1, second.trim())
            firstRelay = first.trim()
            secondRelay = second.trim()
        }.onSuccess { toast = "Servers saved. New posts go to these servers." }
            .onFailure { toast = "Server addresses must start with wss://" }
    }

    override fun revealPlainKey() { revealBackup() }
    override fun hidePlainKey() = hideBackup()

    override fun replaceKeyNow() {
        busyAction({ toast = it ?: "Could not make a new ID" }) {
            io { identity.replaceWithNewKey(); store.setBackupDone(false) }
            revealedBackup = null
            backupDone = false
            refreshIdentity()
            refreshLists()
            toast = "New ID made. Back it up now and share your new ID."
        }
    }

    override fun loadDiscover() {
        if (discoverLoading) return
        if (!online) { discoverNote = "No internet. Discover needs internet; Nearby works without it."; return }
        viewModelScope.launch {
            discoverLoading = true
            try {
                val now = nowSeconds()
                val results = relays.map { relay -> async { io { relayClient.fetchDiscover(relay, nowSeconds = now) } } }.awaitAll()
                val events = results.flatMap { it.events }.distinctBy { it.id }.sortedByDescending { it.createdAt }.take(DISCOVER_LIMIT)
                val keys = maintainers.filter { it.enabled }.map { it.pubkey }
                val suggestions = if (keys.isEmpty()) emptyList() else fetchSuggestions(keys)
                discoverEvents = events
                suggestedKeys = suggestions
                // Names for new faces, so cards don't show only IDs.
                val unnamed = (events.map { it.pubkey } + suggestions).distinct().filter { it !in names }.take(50)
                if (unnamed.isNotEmpty()) for (relay in relays) {
                    val found = io { relayClient.fetchProfiles(relay, unnamed) }.events
                    io { found.forEach { store.saveProfile(it) } }
                }
                names = io { store.names() }
                rebuildDiscover()
                runCatching { fetchThreadsFor(events.map { it.id }) }
                val reached = results.any { it.status == "Complete" || it.status == "Event limit reached" }
                discoverNote = if (reached) "" else "Couldn't reach the servers. Pull down to try again."
            } catch (failure: Exception) {
                discoverNote = failure.message ?: "Couldn't load Discover"
            } finally { discoverLoading = false }
        }
    }

    /** People suggested by [maintainers], newest list per maintainer, in maintainer order. */
    private suspend fun fetchSuggestions(maintainers: List<String>): List<String> {
        val lists = relays.flatMap { relay -> io { relayClient.fetchSuggestions(relay, maintainers) }.events }
            .mapNotNull { SuggestList.of(it) }
            .groupBy { it.maintainer }.mapValues { (_, all) -> all.maxBy { it.createdAt } }
        return maintainers.flatMap { lists[it]?.people?.sorted().orEmpty() }.distinct().take(MAX_SUGGESTED)
    }

    /** Re-applies hide lists and mute/block to the fetched Discover posts. */
    private suspend fun rebuildDiscover() {
        if (discoverEvents.isEmpty()) { discoverPosts = emptyList(); return }
        val hidden = io { store.hidden() }
        val policies = authorPolicies.associate { it.pubkey to it.state }
        val followed = policies.filterValues { it == AuthorState.FOLLOWING }.keys
        discoverPosts = io {
            discoverEvents.filter { e ->
                Replies.rootOf(e) == null && !hidden.covers(e) && policies[e.pubkey].let { it != AuthorState.BLOCKED && it != AuthorState.MUTED }
            }.map { toPostUi(it, 0, emptyList(), followed) }
        }
    }

    override fun searchPeople(query: String): List<FoundPersonUi> {
        val q = query.trim()
        val policies = authorPolicies.associate { it.pubkey to it.state }
        if (q.startsWith("npub1", ignoreCase = true)) {
            return listOfNotNull(runCatching { parseKey(q) }.getOrNull()?.takeIf { it != pubkeyHex }?.let { found(it, policies) })
        }
        if (q.length < 2) return emptyList()
        return names.filter { (key, name) -> key != pubkeyHex && policies[key] != AuthorState.BLOCKED && name.contains(q, ignoreCase = true) }
            .keys.take(20).map { found(it, policies) }
    }

    private fun found(key: String, policies: Map<String, AuthorState>): FoundPersonUi {
        val latest = (discoverEvents.asSequence() + savedEvents.asSequence()).firstOrNull { it.pubkey == key && it.content.isNotBlank() }
        return FoundPersonUi(key, labelOf(key), npubOf(key).takeLast(4), hueOf(key), policies[key] == AuthorState.FOLLOWING,
            latest?.content?.take(140))
    }

    override fun setShowInDiscover(on: Boolean) {
        store.setShowInDiscover(on)
        discoverOptIn = on
        toast = if (on) "New posts will show in Discover." else "New posts won't be tagged for Discover. They're still public."
    }

    override fun setSuggested(authorKey: String, on: Boolean) {
        busyAction({ toast = it ?: "Could not update suggestions" }) {
            val me = pubkeyHex
            val signed = io {
                val current = mySuggestList()
                val people = current?.people.orEmpty().let { if (on) it + authorKey else it - authorKey }
                require(people.size <= SuggestList.MAX_PEOPLE) { "Suggestion list is full" }
                val at = maxOf(nowSeconds(), (current?.createdAt ?: 0) + 1)
                identity.withSecret { Nip01Protocol.signEvent(it, SuggestList.KIND, "", at, SuggestList(me, at, people).toTags()) }
                    .also { store.setMySuggestList(Nip01Protocol.toJson(it)) }
            }
            mySuggestions = SuggestList.of(signed)?.people.orEmpty()
            val accepted = relays.count { relay -> io { runCatching { relayClient.publish(relay, signed) }.getOrDefault("Network error") } == "Accepted" }
            toast = (if (on) "Suggested in Discover" else "No longer suggested") +
                if (accepted > 0) "." else ". No server reached yet; change it again when online to resend."
        }
    }

    private fun mySuggestList(): SuggestList? = store.mySuggestList()
        ?.let { wire -> runCatching { SuggestList.of(Nip01Protocol.fromJson(wire, SuggestList.MAX_BYTES)) }.getOrNull() }

    /** Adds [FreegramConfig.DEFAULT_MAINTAINER_NPUB] once per install, unless it is this phone's own ID. */
    private suspend fun applyDefaultMaintainer() {
        val npub = FreegramConfig.DEFAULT_MAINTAINER_NPUB.takeIf { it.isNotEmpty() } ?: return
        if (store.defaultMaintainerApplied()) return
        val key = parseKey(npub)
        io {
            if (key != pubkeyHex) store.setMaintainer(key, true)
            store.setDefaultMaintainerApplied()
        }
        refreshLists()
    }

    override fun deleteAccount() {
        viewModelScope.launch {
            busy = true
            // Best effort: whatever the servers accept within the time limit, then the phone is erased regardless.
            if (online) runCatching {
                withTimeoutOrNull(DELETE_ACCOUNT_TIMEOUT_MS) {
                    val me = pubkeyHex
                    val ids = io { store.savedEvents().filter { it.pubkey == me }.map { it.id } + store.myLikeIds() }
                    val requests = io { identity.withSecret { AccountDeletion.requests(it, ids, nowSeconds()) } }
                    for (request in requests) publishEverywhere(request)
                }
            }
            panicWipe()
        }
    }

    override fun panicWipe() {
        // Stop radio first so nothing is being written while data is cleared.
        runCatching { NearbyService.stop(getApplication()) }
        PanicWipe.wipe(getApplication())
    }

    private fun personOf(key: String, state: PersonState) = PersonUi(key, labelOf(key), npubOf(key).takeLast(4), hueOf(key), state)
    private fun labelOf(key: String) = names[key] ?: shortKey(npubOf(key))

    override fun onCleared() { runCatching { connectivity.unregisterNetworkCallback(networkCallback) } }

    private fun hasInternet(): Boolean = connectivity.activeNetwork?.let {
        connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } == true

    private fun toPostUi(event: BulletinEvent, hops: Int, states: List<String>, followed: Set<String>): PostUi {
        val ref = PhotoRef.of(event)
        val photo = when {
            ref == null -> PhotoUi.None
            else -> thumbnail(ref.sha256)?.let { PhotoUi.Loaded(it) } ?: PhotoUi.Missing
        }
        val accepted = states.count { it == "Accepted" }
        val source = when {
            states.isEmpty() -> if (hops > 0) PostSource.Nearby(hops) else PostSource.Server
            hops > 0 && accepted == 0 -> PostSource.Nearby(hops)
            "Sending" in states -> PostSource.Sending
            accepted > 0 -> PostSource.Sent(accepted, states.size)
            states.any(RetryPolicy::isRetryable) -> PostSource.WaitingForInternet
            states.any { it.startsWith("Rejected") } -> PostSource.Rejected
            else -> PostSource.OnlyHere
        }
        val authorNpub = npubOf(event.pubkey)
        val label = names[event.pubkey] ?: shortKey(authorNpub)
        return PostUi(event.id, event.pubkey, label, authorNpub.takeLast(4), hueOf(event.pubkey), ago(event.createdAt), event.content, photo, source,
            mine = event.pubkey == pubkeyHex, followed = event.pubkey in followed,
            replyTo = Replies.rootOf(event), replies = replyCounts[event.id] ?: 0,
            likes = likeMap[event.id]?.size ?: 0, likedByMe = likeMap[event.id]?.contains(pubkeyHex) == true)
    }

    private fun thumbnail(sha: String): ImageBitmap? = synchronized(thumbnails) {
        thumbnails[sha] ?: media.path(sha)?.let { file ->
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()?.also { thumbnails[sha] = it }
        }
    }

    private fun ago(epochSeconds: Long): String {
        val d = (nowSeconds() - epochSeconds).coerceAtLeast(0)
        return when { d < 60 -> "now"; d < 3600 -> "${d / 60} min"; d < 86_400 -> "${d / 3600} h"; else -> "${d / 86_400} d" }
    }

    private fun npubOf(hex: String) = runCatching {
        Nip19.encodePublicKey(ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }.getOrDefault(hex)

    override fun setName(name: String) {
        busyAction({ toast = it ?: "Could not save name" }) {
            val event = io { identity.withSecret { ProfileEvent.sign(it, name, nowSeconds()) } }
            io { store.saveProfile(event); store.setPendingProfile(Nip01Protocol.toJson(event)) }
            refreshLists()
            toast = if (online && publishPendingProfile()) "Name saved and shared." else "Name saved. It's shared with servers when you're online."
        }
    }

    /** Sends this phone's latest profile to the relays; true once any relay accepted it. */
    private suspend fun publishPendingProfile(): Boolean {
        val wire = store.pendingProfile() ?: return true
        val event = runCatching { Nip01Protocol.fromJson(wire, ProfileEvent.MAX_BYTES) }.getOrNull() ?: return true
        val accepted = relays.count { relay -> io { runCatching { relayClient.publish(relay, event) }.getOrDefault("Network error") } == "Accepted" }
        if (accepted > 0) io { store.setPendingProfile(null) }
        return accepted > 0
    }

    private suspend fun syncProfiles() {
        publishPendingProfile()
        val authors = posts.map { it.authorKey }.filter { it != pubkeyHex }.distinct().take(50)
        if (authors.isEmpty()) return
        for (relay in relays) {
            val found = io { relayClient.fetchProfiles(relay, authors) }.events
            io { found.forEach { store.saveProfile(it) } }
        }
    }

    override fun createBackup(password: String) {
        busyAction({ toast = it ?: "Could not create backup" }) {
            freshBackup = withContext(Dispatchers.Default) { identity.exportEncrypted(password) }
            io { store.setBackupDone() }
            backupDone = true
        }
    }

    override fun restoreBackup(backup: String, password: String) {
        busyAction({ toast = it ?: "Could not restore" }) {
            withContext(Dispatchers.Default) { identity.restoreEncrypted(backup, password) }
            refreshIdentity()
            io { store.setBackupDone() }
            backupDone = true
            finishOnboarding()
            refreshLists()
            toast = "Your ID is restored."
        }
    }

    override fun finishOnboarding() {
        store.setOnboarded()
        needsOnboarding = false
        freshBackup = null
    }

    /** Follows a scanned code: "nostr:npub1…" or a bare npub. */
    fun followScanned(raw: String?) {
        val text = raw?.trim()?.removePrefix("nostr:").orEmpty()
        val key = runCatching { Nip19.decodePublicKey(text).joinToString("") { "%02x".format(it) } }.getOrNull()
        if (key == null) { toast = "That code isn't a Freegram ID."; return }
        if (key == pubkeyHex) { toast = "That's your own ID."; return }
        follow(key)
    }

    private fun shortKey(npub: String) = if (npub.length > 16) npub.take(10) + "…" + npub.takeLast(4) else npub
    private fun hueOf(hex: String) = if (hex.length >= 4) (hex.take(4).toInt(16) % 360).toFloat() else 200f

    companion object {
        private const val DISCOVER_LIMIT = 50
        private const val MAX_SUGGESTED = 30
        private const val MAX_FETCHED_REPLIES = 500
        private const val DELETE_ACCOUNT_TIMEOUT_MS = 30_000L
        val REPORT_REASONS = listOf("Spam", "Harassment or abuse", "False or dangerous information", "Illegal content", "Other")
    }

    private fun parseKey(input: String): String = input.trim().let {
        if (it.startsWith("npub1", ignoreCase = true)) Nip19.decodePublicKey(it).joinToString("") { b -> "%02x".format(b) } else it.lowercase()
    }

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
        if (state == AuthorState.BLOCKED && devSelected?.pubkey == pubkey) devSelected = null
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
        syncFeed()
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

    fun createEncryptedBackup() = busyAction({ identityMessage = it ?: "Could not create backup" }) {
        require(backupPassword == backupPasswordConfirm) { "Passwords do not match" }
        identityMessage = "Encrypting… this takes a few seconds."
        encryptedBackup = withContext(Dispatchers.Default) { identity.exportEncrypted(backupPassword) }
        backupPassword = ""; backupPasswordConfirm = ""
        io { store.setBackupDone() }
        backupDone = true
        identityMessage = "Encrypted backup ready. Keep the password separately; without it the backup cannot be opened."
    }

    fun restore() {
        if (!confirmingRestore) { confirmingRestore = true; confirmingReplace = false; return }
        busyAction({ identityMessage = it ?: "Restore failed; current key kept" }, after = { confirmingRestore = false }) {
            val input = restoreInput.trim()
            if (input.startsWith("ncryptsec1", ignoreCase = true)) withContext(Dispatchers.Default) { identity.restoreEncrypted(input, restorePassword) }
            else io { identity.restore(input) }
            restoreInput = ""
            restorePassword = ""
            revealedBackup = null
            encryptedBackup = null
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
        NearbyService.start(getApplication())
    }

    fun stopNearby() = NearbyService.stop(getApplication())

    fun changeAutoBridge(enabled: Boolean) {
        store.setAutoBridge(enabled)
        autoBridge = enabled
    }

    fun nearbyPermissionDenied() { nearbyStatus = "Nearby sharing needs Bluetooth and nearby-device permissions." }

    private fun observeNearby() {
        viewModelScope.launch { NearbyState.running.collect { nearbyRunning = it } }
        viewModelScope.launch { NearbyState.status.collect { nearbyStatus = it } }
        viewModelScope.launch { NearbyState.log.collect { nearbyLog = it } }
        viewModelScope.launch { NearbyState.activity.collect { nearbyActivity = it } }
        viewModelScope.launch {
            NearbyState.exchanges.collect { count -> nearbyCounts = Triple(count, NearbyState.postsReceived.value, NearbyState.postsPassed.value) }
        }
        viewModelScope.launch {
            NearbyState.exchanges.collect { if (ready) { refreshLists(); devSelected?.let { show(it) } } }
        }
    }

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
                    if (devSelected?.id == event.id) { if (relay == targets[0]) firstState = state else secondState = state }
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
        devSelected = event
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
        maintainers = io { store.maintainers() }
        hiddenCount = io { store.hiddenCount() }
        myHideList = io { pubkeyHex.takeIf { it.isNotEmpty() }?.let { store.hideList(it) } }
        names = io { store.names() }
        photoBytes = io { media.totalBytes() }
        val hops = io { store.hopsById() }
        val deliveries = io { store.deliveryStates() }
        val followed = policies.filter { it.state == AuthorState.FOLLOWING }.map { it.pubkey }.toSet()
        val hiddenNow = io { store.hidden() }
        val silenced = policies.filter { it.state != AuthorState.FOLLOWING }.map { it.pubkey }.toSet()
        likeMap = io { store.likes() }.mapValues { (_, who) -> who - silenced }
        allReplies = (saved + fetchedReplies.values).distinctBy { it.id }
            .filter { Replies.rootOf(it) != null && it.pubkey !in silenced && !hiddenNow.covers(it) }
        replyCounts = allReplies.groupingBy { Replies.rootOf(it)!! }.eachCount()
        posts = io { saved.map { toPostUi(it, hops[it.id] ?: 0, deliveries[it.id].orEmpty(), followed) } }
        mySuggestions = io { mySuggestList()?.people.orEmpty() }
        rebuildDiscover()
        rebuildThread()
    }

    private suspend fun syncFeed() {
        feedRelayResults = io { FollowedFeedSync(store, relayClient).refresh(relays) }
    }

    private suspend fun syncHideLists(): List<String> {
        val keys = maintainers.map { it.pubkey }
        if (keys.isEmpty()) return emptyList()
        return relays.map { relay ->
            val result = io { relayClient.fetchHideLists(relay, keys) }
            val updated = io { result.events.count { store.saveHideList(it) } }
            "$relay: ${result.status}, $updated updated"
        }
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
