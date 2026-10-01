package org.freegram.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.pm.ApplicationInfo
import android.os.PersistableBundle
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.freegram.app.nearby.nearbyPermissions
import org.freegram.app.nearby.optionalNearbyPermissions
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.PhotoRef
import org.freegram.shared.model.PlatformActions
import org.freegram.shared.model.SettingsPage
import org.freegram.shared.ui.FreegramApp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : ComponentActivity() {
    private val model: FreegramViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FreegramHost(model) }
    }
}

/** Connects the shared screens to Android: photo picker, permissions, clipboard, and the Settings pages. */
@Composable
private fun FreegramHost(model: FreegramViewModel) {
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(model::pickPhoto) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (nearbyPermissions().all { granted[it] == true }) model.startNearby() else model.nearbyPermissionDenied()
    }
    val platform = remember {
        PlatformActions(
            pickPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            startNearby = { permissions.launch(nearbyPermissions() + optionalNearbyPermissions()) },
            copyText = { label, text ->
                val clip = ClipData.newPlainText(label, text)
                // Keep backup codes out of the clipboard preview and keyboard suggestions.
                if (label.contains("backup", ignoreCase = true)) {
                    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                }
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                model.showToast("Copied.")
            },
            scanToFollow = {
                GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                    .startScan()
                    .addOnSuccessListener { model.followScanned(it.rawValue) }
                    .addOnFailureListener { model.showToast("Scanner unavailable on this phone. Paste the ID in Settings → People you follow.") }
            },
            setSecureScreen = { on ->
                val window = (context as Activity).window
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            },
        )
    }
    val pages = remember {
        // Test tools stay out of release builds.
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) emptyList()
        else listOf(SettingsPage("Developer tools", "Raw post data, bridge test, fetch by ID") { DeveloperPage(model) })
    }
    FreegramApp(model, platform, pages)
}

/** The original test screen, kept for debugging: raw IDs, per-relay states, bridge test and fetch by ID. */
@Composable
private fun DeveloperPage(model: FreegramViewModel) {
    val context = LocalContext.current
    val enabled = model.ready && !model.busy
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (model.message.isNotEmpty()) Text(model.message)
            model.devSelected?.let { saved ->
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
    }
}
