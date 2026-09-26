package org.freegram.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freegram.app.identity.IdentitySection
import org.freegram.app.identity.ProtectedIdentity
import org.freegram.app.protocol.Nip19
import org.freegram.app.feed.FollowedFeedSection
import org.freegram.app.feed.FollowedFeedSync
import org.freegram.app.feed.RelayRefreshResult
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.relay.RelayClient
import org.freegram.app.relay.FetchResult
import org.freegram.app.store.RoomStore
import org.freegram.app.store.AuthorPolicy
import org.freegram.app.store.AuthorState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = RoomStore(applicationContext)
        val identity = ProtectedIdentity(applicationContext)
        val relayClient = RelayClient()
        setContent { FreegramScreen(store, identity, relayClient) }
    }
}

@Composable
private fun FreegramScreen(store: RoomStore, identity: ProtectedIdentity, relayClient: RelayClient) {
    var draft by remember { mutableStateOf("") }
    var event by remember { mutableStateOf<BulletinEvent?>(null) }
    var savedEvents by remember { mutableStateOf<List<BulletinEvent>>(emptyList()) }
    var feedEvents by remember { mutableStateOf<List<BulletinEvent>>(emptyList()) }
    var authorPolicies by remember { mutableStateOf<List<AuthorPolicy>>(emptyList()) }
    var authorInput by remember { mutableStateOf("") }
    var feedMessage by remember { mutableStateOf("") }
    var feedRelayResults by remember { mutableStateOf<List<RelayRefreshResult>>(emptyList()) }
    var canDeliver by remember { mutableStateOf(false) }
    var importWire by remember { mutableStateOf("") }
    var lookupId by remember { mutableStateOf("") }
    var firstRelay by remember { mutableStateOf(store.relayUrl(0)) }
    var secondRelay by remember { mutableStateOf(store.relayUrl(1)) }
    var firstState by remember { mutableStateOf("") }
    var secondState by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var npub by remember { mutableStateOf("") }
    var pubkeyHex by remember { mutableStateOf("") }
    var revealedBackup by remember { mutableStateOf<String?>(null) }
    var restoreInput by remember { mutableStateOf("") }
    var confirmingRestore by remember { mutableStateOf(false) }
    var confirmingReplace by remember { mutableStateOf(false) }
    var identityMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    suspend fun refreshSavedEvents() {
        val (saved, feed) = withContext(Dispatchers.IO) { store.savedEvents() to store.feedEvents() }
        savedEvents = saved
        feedEvents = feed
    }

    suspend fun refreshAuthorPolicies() {
        authorPolicies = withContext(Dispatchers.IO) { store.authorPolicies() }
    }

    suspend fun showEvent(saved: BulletinEvent) {
        val details = withContext(Dispatchers.IO) {
            Triple(
                store.deliveryTargets(saved.id).isNotEmpty(),
                store.relayState(saved.id, firstRelay.trim()),
                store.relayState(saved.id, secondRelay.trim()),
            )
        }
        event = saved
        canDeliver = details.first
        firstState = details.second
        secondState = details.third
    }

    suspend fun refreshIdentity() {
        val keys = withContext(Dispatchers.IO) { identity.npub() to identity.publicKeyHex() }
        npub = keys.first
        pubkeyHex = keys.second
    }

    LaunchedEffect(store) {
        try {
            val restored = withContext(Dispatchers.IO) {
                store.initialize()
                store.draft() to store.savedEvents()
            }
            draft = restored.first
            savedEvents = restored.second
            refreshAuthorPolicies()
            feedEvents = withContext(Dispatchers.IO) { store.feedEvents() }
            restored.second.firstOrNull()?.let { showEvent(it) }
            refreshIdentity()
            ready = true
        } catch (failure: Exception) { error = failure.message ?: "Could not open local data" }
    }

    suspend fun deliver(saved: BulletinEvent) {
        val relays = listOf(firstRelay.trim(), secondRelay.trim())
        require(relays.all { it.startsWith("wss://") } && relays.distinct().size == 2) { "Use two distinct wss:// relay URLs" }
        withContext(Dispatchers.IO) {
            store.saveEvent(saved, relays)
            store.setRelayUrl(0, relays[0])
            store.setRelayUrl(1, relays[1])
        }
        val first = withContext(Dispatchers.IO) {
            if (store.relayState(saved.id, relays[0]) == "Accepted") "Accepted" else relayClient.publish(relays[0], saved).also { store.setRelayState(saved.id, relays[0], it) }
        }
        firstState = first
        val second = withContext(Dispatchers.IO) {
            if (store.relayState(saved.id, relays[1]) == "Accepted") "Accepted" else relayClient.publish(relays[1], saved).also { store.setRelayState(saved.id, relays[1], it) }
        }
        secondState = second
    }

    suspend fun fetchFrom(relay: String) {
        val id = lookupId.trim().lowercase()
        val result = withContext(Dispatchers.IO) { relayClient.fetch(relay.trim(), id) }
        when (result) {
            is FetchResult.Found -> {
                withContext(Dispatchers.IO) { store.saveReceivedEvent(result.event) }
                refreshSavedEvents()
                showEvent(result.event)
                error = "Fetched and verified ${result.event.id.take(12)}… from $relay"
            }
            FetchResult.NotFound -> error = "Relay has no stored event for that ID"
            is FetchResult.Failed -> error = "Fetch failed: ${result.reason}"
        }
    }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Freegram prototype", style = MaterialTheme.typography.headlineMedium)
            Text("Public text bulletins. Anyone who gets a signed post can copy it. This prototype has no nearby transport; do not rely on it for safety-critical communication.")
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                enabled = ready,
                label = { Text("Bulletin draft") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )
            Text("${draft.toByteArray(Charsets.UTF_8).size}/2048 UTF-8 bytes")
            Button(enabled = ready && !busy, onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.saveDraft(draft) }
                        error = "Draft saved locally"
                    } catch (failure: Exception) { error = failure.message ?: "Could not save draft" }
                }
            }) { Text("Save draft") }
            Button(enabled = ready && !busy && draft.isNotBlank() && draft.toByteArray(Charsets.UTF_8).size <= 2048, onClick = {
                scope.launch {
                    busy = true
                    error = ""
                    try {
                        require(firstRelay.trim().startsWith("wss://") && secondRelay.trim().startsWith("wss://") && firstRelay.trim() != secondRelay.trim())
                        val saved = withContext(Dispatchers.IO) {
                            val signed = identity.withSecret { secret ->
                                Nip01Protocol.signBulletin(secret, draft, System.currentTimeMillis() / 1000)
                            }
                            store.saveEvent(signed, listOf(firstRelay.trim(), secondRelay.trim()))
                            store.saveDraft("")
                            signed
                        }
                        refreshSavedEvents()
                        showEvent(saved)
                        draft = ""
                        deliver(saved)
                    } catch (failure: Exception) { error = failure.message ?: "Publish failed; check saved event" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Working…" else "Sign, save, and send") }
            OutlinedTextField(value = firstRelay, onValueChange = { firstRelay = it }, label = { Text("Relay 1") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = secondRelay, onValueChange = { secondRelay = it }, label = { Text("Relay 2") }, modifier = Modifier.fillMaxWidth())
            if (error.isNotEmpty()) Text(error)
            event?.let { saved ->
                Text("Selected saved bulletin", style = MaterialTheme.typography.titleMedium)
                Text(saved.content)
                Text("Signature valid. Author identity and report accuracy are not verified.")
                Text("Author key: ${saved.pubkey}")
                Text("Event ID: ${saved.id}")
                if (canDeliver) {
                    Text("Relay 1: $firstState")
                    Text("Relay 2: $secondState")
                } else {
                    Text("Verified locally; this phone has not queued relay delivery.")
                }
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Freegram signed event", Nip01Protocol.toJson(saved)))
                    error = "Signed public event copied. It contains no private key."
                }) { Text("Copy signed event JSON") }
                if (canDeliver) {
                    Button(enabled = ready && !busy, onClick = {
                        scope.launch {
                            busy = true
                            error = ""
                            try { deliver(saved) }
                            catch (failure: Exception) { error = failure.message ?: "Relay delivery failed" }
                            finally { busy = false }
                        }
                    }) { Text("Submit saved event to relays") }
                }
            }
            FollowedFeedSection(
                authorInput = authorInput,
                onAuthorInputChange = { authorInput = it },
                policies = authorPolicies,
                events = feedEvents,
                relayResults = feedRelayResults,
                message = feedMessage,
                enabled = ready && !busy,
                onFollow = {
                    scope.launch {
                        busy = true
                        try {
                            val key = authorInput.trim().let { input ->
                                if (input.startsWith("npub1", ignoreCase = true)) Nip19.decodePublicKey(input).joinToString("") { "%02x".format(it) }
                                else input.lowercase()
                            }
                            withContext(Dispatchers.IO) { store.setAuthorState(key, AuthorState.FOLLOWING) }
                            authorInput = ""
                            refreshAuthorPolicies()
                            refreshSavedEvents()
                            feedMessage = "Following key. Refresh to fetch its posts."
                        } catch (failure: Exception) { feedMessage = failure.message ?: "Could not follow key" }
                        finally { busy = false }
                    }
                },
                onChangeState = { pubkey, state ->
                    scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) { store.setAuthorState(pubkey, state) }
                            if (state == AuthorState.BLOCKED && event?.pubkey == pubkey) event = null
                            refreshAuthorPolicies()
                            refreshSavedEvents()
                            feedMessage = "Author ${state.name.lowercase()} locally"
                        } catch (failure: Exception) { feedMessage = failure.message ?: "Could not update author" }
                        finally { busy = false }
                    }
                },
                onRemove = { pubkey ->
                    scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) { store.removeAuthor(pubkey) }
                            refreshAuthorPolicies()
                            refreshSavedEvents()
                            feedMessage = "Local author control removed"
                        } catch (failure: Exception) { feedMessage = failure.message ?: "Could not remove author" }
                        finally { busy = false }
                    }
                },
                onRefresh = {
                    scope.launch {
                        busy = true
                        feedMessage = "Refreshing…"
                        try {
                            feedRelayResults = withContext(Dispatchers.IO) {
                                FollowedFeedSync(store, relayClient).refresh(listOf(firstRelay.trim(), secondRelay.trim()))
                            }
                            refreshSavedEvents()
                            feedMessage = "Refresh finished. Check each relay result below."
                        } catch (failure: Exception) { feedMessage = failure.message ?: "Refresh failed" }
                        finally { busy = false }
                    }
                },
                onOpen = { saved ->
                    scope.launch {
                        try { showEvent(saved) }
                        catch (failure: Exception) { feedMessage = failure.message ?: "Could not open post" }
                    }
                },
            )
            Text("Bridge test", style = MaterialTheme.typography.titleMedium)
            Text("Only paste a signed event copied from another phone here. For a new plain-text post, use Bulletin draft above.")
            OutlinedTextField(
                value = importWire,
                onValueChange = { importWire = it },
                enabled = ready && !busy,
                label = { Text("Paste copied signed event JSON") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Button(enabled = ready && !busy && importWire.isNotBlank() && importWire.toByteArray(Charsets.UTF_8).size <= 4096, onClick = {
                scope.launch {
                    busy = true
                    error = ""
                    try {
                        val imported = Nip01Protocol.parseImportedBulletin(importWire)
                        withContext(Dispatchers.IO) { store.saveEvent(imported, listOf(firstRelay.trim(), secondRelay.trim())) }
                        refreshSavedEvents()
                        showEvent(imported)
                        importWire = ""
                        error = "Verified and saved for explicit relay submission"
                    } catch (failure: Exception) { error = failure.message ?: "Import failed" }
                    finally { busy = false }
                }
            }) { Text("Verify and carry event") }
            OutlinedTextField(
                value = lookupId,
                onValueChange = { lookupId = it },
                enabled = ready && !busy,
                label = { Text("Event ID to fetch") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = ready && !busy && lookupId.trim().length == 64, onClick = {
                scope.launch {
                    busy = true
                    error = ""
                    try { fetchFrom(firstRelay) }
                    catch (failure: Exception) { error = failure.message ?: "Fetch failed" }
                    finally { busy = false }
                }
            }) { Text("Fetch from relay 1") }
            Button(enabled = ready && !busy && lookupId.trim().length == 64, onClick = {
                scope.launch {
                    busy = true
                    error = ""
                    try { fetchFrom(secondRelay) }
                    catch (failure: Exception) { error = failure.message ?: "Fetch failed" }
                    finally { busy = false }
                }
            }) { Text("Fetch from relay 2") }
            Text("Saved bulletins on this phone", style = MaterialTheme.typography.titleMedium)
            Text("Verified local copies only. New posts are not discovered automatically yet.")
            if (savedEvents.isEmpty()) Text("No saved bulletins yet")
            savedEvents.forEach { saved ->
                Text(saved.content.take(160))
                Text("Author key: ${saved.pubkey.take(16)}…")
                Button(enabled = ready && !busy, onClick = {
                    scope.launch {
                        try { showEvent(saved) }
                        catch (failure: Exception) { error = failure.message ?: "Could not open saved bulletin" }
                    }
                }) { Text("Open ${saved.id.take(12)}…") }
            }
            IdentitySection(
                npub = npub,
                pubkeyHex = pubkeyHex,
                revealedBackup = revealedBackup,
                restoreInput = restoreInput,
                onRestoreInputChange = { restoreInput = it; confirmingRestore = false },
                confirmingRestore = confirmingRestore,
                confirmingReplace = confirmingReplace,
                message = identityMessage,
                enabled = ready && !busy,
                onReveal = {
                    scope.launch {
                        try { revealedBackup = withContext(Dispatchers.IO) { identity.exportNsec() } }
                        catch (failure: Exception) { identityMessage = failure.message ?: "Could not read key" }
                    }
                },
                onHide = { revealedBackup = null },
                onRestore = {
                    if (!confirmingRestore) { confirmingRestore = true; confirmingReplace = false }
                    else scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) { identity.restore(restoreInput) }
                            restoreInput = ""
                            revealedBackup = null
                            refreshIdentity()
                            identityMessage = "Identity restored. New posts are signed with this key."
                        } catch (failure: Exception) { identityMessage = failure.message ?: "Restore failed; current key kept" }
                        finally { confirmingRestore = false; busy = false }
                    }
                },
                onReplace = {
                    if (!confirmingReplace) { confirmingReplace = true; confirmingRestore = false }
                    else scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) { identity.replaceWithNewKey() }
                            revealedBackup = null
                            refreshIdentity()
                            identityMessage = "New key created. Back it up and share the new public key."
                        } catch (failure: Exception) { identityMessage = failure.message ?: "Could not create key" }
                        finally { confirmingReplace = false; busy = false }
                    }
                },
                onCancelConfirm = { confirmingRestore = false; confirmingReplace = false },
            )
        }
    }
}
