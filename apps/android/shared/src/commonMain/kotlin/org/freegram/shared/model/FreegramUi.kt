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

/** A page reached from Settings. Its content comes from the platform app. */
class SettingsPage(val title: String, val subtitle: String, val content: @Composable () -> Unit)

/**
 * Everything the shared screens show and can do. The Android app implements it with real storage,
 * keys, relays and nearby radio; an iOS app would implement the same interface.
 */
interface FreegramUi {
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
)
