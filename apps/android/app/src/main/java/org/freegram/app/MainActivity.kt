package org.freegram.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import org.freegram.app.nearby.NearbySection
import org.freegram.app.protocol.Nip01Protocol

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
            Button(enabled = enabled, onClick = { model.saveDraft() }) { Text("Save draft") }
            Button(enabled = enabled && model.draft.isNotBlank() && draftBytes <= 2048, onClick = { model.publish() }) {
                Text(if (model.busy) "Working…" else "Sign, save, and send")
            }
            OutlinedTextField(value = model.firstRelay, onValueChange = { model.firstRelay = it }, label = { Text("Relay 1") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = model.secondRelay, onValueChange = { model.secondRelay = it }, label = { Text("Relay 2") }, modifier = Modifier.fillMaxWidth())
            if (model.message.isNotEmpty()) Text(model.message)
            model.selected?.let { saved ->
                Text("Selected saved bulletin", style = MaterialTheme.typography.titleMedium)
                Text(saved.content)
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
                Text(saved.content.take(160))
                Text("Author key: ${saved.pubkey.take(16)}…")
                Button(enabled = enabled, onClick = { model.open(saved) }) { Text("Open ${saved.id.take(12)}…") }
            }
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
            )
        }
    }
}
