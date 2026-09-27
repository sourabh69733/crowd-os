package org.freegram.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.freegram.app.feed.FollowedFeedSection
import org.freegram.app.identity.IdentitySection
import org.freegram.app.moderation.ModerationSection
import org.freegram.app.nearby.NearbySection
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.PhotoRef

class MainActivity : ComponentActivity() {
    private val model: FreegramViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FreegramScreen(model) }
    }
}

@Composable
private fun FreegramScreen(model: FreegramViewModel) {
    val context = LocalContext.current
    val enabled = model.ready && !model.busy
    val draftBytes = model.draft.toByteArray(Charsets.UTF_8).size
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(model::pickPhoto) }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Freegram prototype", style = MaterialTheme.typography.headlineMedium)
            Text("Public text bulletins. Anyone who gets a signed post can copy it. Nearby sharing is experimental and not yet tested between real phones; do not rely on this prototype for safety-critical communication.")
            OutlinedTextField(
                value = model.draft,
                onValueChange = { model.draft = it },
                enabled = model.ready,
                label = { Text("Bulletin draft") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )
            Text("$draftBytes/2048 UTF-8 bytes")
            model.pickedPreview?.let { preview ->
                Image(bitmap = preview, contentDescription = "Photo to post", modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp))
                Button(enabled = enabled, onClick = model::removePhoto) { Text("Remove photo") }
            } ?: Button(enabled = enabled, onClick = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Add photo") }
            Button(enabled = enabled, onClick = { model.saveDraft() }) { Text("Save draft") }
            Button(enabled = enabled && (model.draft.isNotBlank() || model.pickedPhoto != null) && draftBytes <= 2048, onClick = { model.publish() }) {
                Text(if (model.busy) "Working…" else "Sign, save, and send")
            }
            OutlinedTextField(value = model.firstRelay, onValueChange = { model.firstRelay = it }, label = { Text("Relay 1") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = model.secondRelay, onValueChange = { model.secondRelay = it }, label = { Text("Relay 2") }, modifier = Modifier.fillMaxWidth())
            if (model.message.isNotEmpty()) Text(model.message)
            model.selected?.let { saved ->
                Text("Selected saved bulletin", style = MaterialTheme.typography.titleMedium)
                Text(saved.content)
                model.selectedPhoto?.let { Image(bitmap = it, contentDescription = "Post photo", modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) }
                if (model.selectedPhotoNote.isNotEmpty()) Text(model.selectedPhotoNote)
                Text("Signature valid. Author identity and report accuracy are not verified.")
                Text("Author key: ${saved.pubkey}")
                Text("Event ID: ${saved.id}")
                if (model.canDeliver) {
                    Text("Relay 1: ${model.describe(model.firstState)}")
                    Text("Relay 2: ${model.describe(model.secondState)}")
                } else {
                    Text("Verified locally; this phone has not queued relay delivery.")
                }
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Freegram signed event", Nip01Protocol.toJson(saved)))
                    model.message = "Signed public event copied. It contains no private key."
                }) { Text("Copy signed event JSON") }
                if (model.canDeliver) {
                    Button(enabled = enabled, onClick = { model.resubmit(saved) }) { Text("Submit saved event to relays") }
                }
                if (saved.pubkey != model.pubkeyHex) {
                    Text("Maintainer tools: hiding adds to your own public hide list, applied by everyone who follows you as a maintainer.")
                    Button(enabled = enabled, onClick = { model.hideSelected(author = false) }) { Text("Hide this post (maintainer)") }
                    Button(enabled = enabled, onClick = { model.hideSelected(author = true) }) { Text("Hide this author (maintainer)") }
                    Text("Report this post privately to your maintainers", style = MaterialTheme.typography.titleSmall)
                    FreegramViewModel.REPORT_REASONS.forEach { reason ->
                        Button(enabled = enabled, onClick = { model.reportReason = reason }) {
                            Text((if (model.reportReason == reason) "● " else "") + reason)
                        }
                    }
                    OutlinedTextField(value = model.reportNote, onValueChange = { model.reportNote = it }, enabled = enabled,
                        label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = enabled && model.maintainers.any { it.enabled && it.pubkey != model.pubkeyHex }, onClick = { model.sendReport() }) {
                        Text("Send private report")
                    }
                    if (model.reportStatus.isNotEmpty()) Text(model.reportStatus)
                }
                if (model.confirmingDelete) Text("This removes only this phone's copy and stops any queued sending. Copies already on relays or other phones stay.")
                Button(enabled = enabled, onClick = model::deleteSelected) {
                    Text(if (model.confirmingDelete) "Confirm: delete from this phone" else "Delete from this phone")
                }
                if (model.confirmingDelete) Button(onClick = model::cancelDelete) { Text("Cancel") }
            }
            FollowedFeedSection(
                authorInput = model.authorInput,
                onAuthorInputChange = { model.authorInput = it },
                policies = model.authorPolicies,
                events = model.feedEvents,
                relayResults = model.feedRelayResults,
                message = model.feedMessage,
                enabled = enabled,
                onFollow = { model.follow() },
                onChangeState = { pubkey, state -> model.setAuthorState(pubkey, state) },
                onRemove = { model.removeAuthor(it) },
                onRefresh = { model.refreshFeed() },
                onOpen = { model.open(it) },
            )
            NearbySection(
                running = model.nearbyRunning,
                status = model.nearbyStatus,
                log = model.nearbyLog,
                enabled = model.ready,
                autoBridge = model.autoBridge,
                onAutoBridgeChange = model::changeAutoBridge,
                onStart = model::startNearby,
                onStop = model::stopNearby,
                onPermissionDenied = model::nearbyPermissionDenied,
            )
            Text("Bridge test", style = MaterialTheme.typography.titleMedium)
            Text("Only paste a signed event copied from another phone here. For a new plain-text post, use Bulletin draft above.")
            OutlinedTextField(
                value = model.importWire,
                onValueChange = { model.importWire = it },
                enabled = enabled,
                label = { Text("Paste copied signed event JSON") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Button(enabled = enabled && model.importWire.isNotBlank() && model.importWire.toByteArray(Charsets.UTF_8).size <= 4096, onClick = { model.importCarried() }) {
                Text("Verify and carry event")
            }
            OutlinedTextField(
                value = model.lookupId,
                onValueChange = { model.lookupId = it },
                enabled = enabled,
                label = { Text("Event ID to fetch") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = enabled && model.lookupId.trim().length == 64, onClick = { model.fetch(model.firstRelay) }) { Text("Fetch from relay 1") }
            Button(enabled = enabled && model.lookupId.trim().length == 64, onClick = { model.fetch(model.secondRelay) }) { Text("Fetch from relay 2") }
            Text("Saved bulletins on this phone", style = MaterialTheme.typography.titleMedium)
            Text("Verified local copies only. New posts are not discovered automatically yet.")
            if (model.savedEvents.isEmpty()) Text("No saved bulletins yet")
            model.savedEvents.forEach { saved ->
                Text((if (PhotoRef.of(saved) != null) "[photo] " else "") + saved.content.take(160))
                Text("Author key: ${saved.pubkey.take(16)}…")
                Button(enabled = enabled, onClick = { model.open(saved) }) { Text("Open ${saved.id.take(12)}…") }
            }
            ModerationSection(
                maintainers = model.maintainers,
                myKey = model.pubkeyHex,
                input = model.maintainerInput,
                onInputChange = { model.maintainerInput = it },
                hiddenCount = model.hiddenCount,
                myList = model.myHideList,
                message = model.moderationMessage,
                enabled = enabled,
                onAdd = { model.addMaintainer() },
                onToggle = { key, on -> model.setMaintainerEnabled(key, on) },
                onRemove = { model.removeMaintainer(it) },
                onRefresh = { model.refreshHideLists() },
                onUnhidePost = { model.unhidePost(it) },
                onUnhideAuthor = { model.unhideAuthor(it) },
                appealText = model.appealText,
                onAppealTextChange = { model.appealText = it },
                appealPostId = model.appealPostId,
                onAppealPostIdChange = { model.appealPostId = it },
                onAppeal = { model.sendAppeal(it) },
                inbox = model.inbox,
                onCheckInbox = { model.checkInbox() },
                onHideReported = { model.hidePost(it) },
            )
            IdentitySection(
                npub = model.npub,
                pubkeyHex = model.pubkeyHex,
                revealedBackup = model.revealedBackup,
                restoreInput = model.restoreInput,
                onRestoreInputChange = model::changeRestoreInput,
                confirmingRestore = model.confirmingRestore,
                confirmingReplace = model.confirmingReplace,
                message = model.identityMessage,
                enabled = enabled,
                onReveal = { model.revealBackup() },
                onHide = model::hideBackup,
                onRestore = model::restore,
                onReplace = model::replaceKey,
                onCancelConfirm = model::cancelConfirm,
                backupPassword = model.backupPassword,
                onBackupPasswordChange = { model.backupPassword = it },
                backupPasswordConfirm = model.backupPasswordConfirm,
                onBackupPasswordConfirmChange = { model.backupPasswordConfirm = it },
                encryptedBackup = model.encryptedBackup,
                onCreateEncrypted = { model.createEncryptedBackup() },
                onCopyEncrypted = { backup ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Freegram encrypted key backup", backup)
                    // Keep it out of clipboard previews; it is encrypted, but still worth not displaying.
                    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    clipboard.setPrimaryClip(clip)
                    model.message = "Encrypted backup copied."
                },
                restorePassword = model.restorePassword,
                onRestorePasswordChange = { model.restorePassword = it },
            )
        }
    }
}
