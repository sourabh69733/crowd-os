package org.freegram.shared.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** How a post reached this phone, or where the user's own post stands. */
sealed interface PostSource {
    data class Nearby(val hops: Int) : PostSource
    data object Server : PostSource
    data object Sending : PostSource
    data class Sent(val accepted: Int, val total: Int) : PostSource
    data object WaitingForInternet : PostSource
    data object Rejected : PostSource
    /** Saved on this phone but never queued for servers (e.g. carried without auto-publish). */
    data object OnlyHere : PostSource
}

sealed interface PhotoUi {
    data object None : PhotoUi
    data class Loaded(val image: ImageBitmap) : PhotoUi
    /** The post has a photo that has not reached this phone yet. */
    data object Missing : PhotoUi
}

data class PostUi(
    val id: String,
    val authorKey: String,
    /** The author's chosen name, or a shortened ID if they have none. Names are not verified. */
    val authorLabel: String,
    /** Last characters of the author's key, always shown so two people can't look identical by name. */
    val authorTag: String,
    val authorHue: Float,
    val time: String,
    val text: String,
    val photo: PhotoUi,
    val source: PostSource,
    val mine: Boolean,
    val followed: Boolean,
)

data class NearbyActivity(val text: String, val time: String)

data class NearbyUi(
    val running: Boolean,
    val status: String,
    val exchanges: Int,
    val postsReceived: Int,
    val postsPassed: Int,
    val activity: List<NearbyActivity>,
    val autoPublish: Boolean,
)

data class MeUi(val label: String, val name: String, val npub: String, val tag: String, val hue: Float, val backupDone: Boolean)

data class PersonUi(val key: String, val label: String, val tag: String, val hue: Float, val state: PersonState)
enum class PersonState { Following, Muted, Blocked }

data class MaintainerUi(val key: String, val label: String, val tag: String, val hue: Float, val enabled: Boolean, val isMe: Boolean)

data class InboxItemUi(val isReport: Boolean, val fromLabel: String, val reason: String, val note: String, val postId: String?, val time: String, val alreadyHidden: Boolean)

data class StorageUi(val posts: Int, val maxPosts: Int, val photoBytes: Long, val maxPhotoBytes: Long, val hiddenByMaintainers: Int)

/**
 * Settings screens: people, maintainers, reports, servers, storage and key management.
 * Part of [FreegramUi]; split out only to keep each list readable.
 */
interface SettingsUi {
    val people: List<PersonUi>
    val maintainerList: List<MaintainerUi>
    val myHiddenPosts: List<String>
    val myHiddenAuthors: List<PersonUi>
    val inboxList: List<InboxItemUi>
    val storageInfo: StorageUi
    val relayUrls: List<String>
    /** The plain secret key while the user has chosen to show it; null otherwise. */
    val plainKey: String?

    fun followId(text: String)
    fun unfollow(key: String)
    fun unmute(key: String)
    fun unblock(key: String)
    fun addMaintainerId(text: String)
    fun setMaintainerOn(key: String, on: Boolean)
    fun dropMaintainer(key: String)
    fun refreshMaintainers()
    fun appeal(maintainer: String, text: String, postId: String)
    fun loadInbox()
    fun hideReported(postId: String)
    fun unhidePostId(id: String)
    fun unhideAuthorKey(key: String)
    fun saveRelays(first: String, second: String)
    fun revealPlainKey()
    fun hidePlainKey()
    fun replaceKeyNow()
    /** Erases this phone's ID, posts, photos and lists, then closes the app. Cannot be undone without a backup. */
    fun panicWipe()
}

/** A page reached from Settings. Its content comes from the platform app. */
class SettingsPage(val title: String, val subtitle: String, val content: @Composable () -> Unit)

/**
 * Everything the shared screens show and can do. The Android app implements it with real storage,
 * keys, relays and nearby radio; an iOS app would implement the same interface.
 */
interface FreegramUi : SettingsUi {
    val ready: Boolean
    val busy: Boolean
    val online: Boolean
    val posts: List<PostUi>
    val selected: PostUi?
    val draft: String
    val draftPhoto: ImageBitmap?
    val nearby: NearbyUi
    val me: MeUi
    val toast: String?
    val reportReasons: List<String>
    val canReport: Boolean
    val isMaintainer: Boolean
    val needsOnboarding: Boolean
    /** Password-encrypted backup text right after it was created, so the user can copy it; null otherwise. */
    val freshBackup: String?

    fun updateDraft(text: String)
    fun removePhoto()
    fun publish()
    fun open(postId: String)
    fun closeDetail()
    fun refresh()
    fun follow(authorKey: String)
    fun mute(authorKey: String)
    fun block(authorKey: String)
    fun deleteLocal(postId: String)
    fun report(postId: String, reason: String, note: String)
    fun hideForFollowers(postId: String)
    fun setNearby(on: Boolean)
    fun setAutoPublish(on: Boolean)
    fun dismissToast()
    fun setName(name: String)
    fun createBackup(password: String)
    fun restoreBackup(backup: String, password: String)
    fun finishOnboarding()
}

/** Things only the platform can do (system pickers, permissions, clipboard). */
class PlatformActions(
    val pickPhoto: () -> Unit,
    val startNearby: () -> Unit,
    val copyText: (label: String, text: String) -> Unit,
    /** Opens the camera code scanner; a scanned Freegram ID is followed. */
    val scanToFollow: () -> Unit,
    /** Blocks screenshots and the recent-apps preview while a secret is on screen. */
    val setSecureScreen: (Boolean) -> Unit,
)
