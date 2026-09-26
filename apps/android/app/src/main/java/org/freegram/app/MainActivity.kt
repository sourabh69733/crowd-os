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
import org.freegram.app.identity.ProtectedIdentity
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.relay.RelayClient
import org.freegram.app.relay.FetchResult
import org.freegram.app.store.RoomStore

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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(store) {
        try {
            val restored = withContext(Dispatchers.IO) {
                store.initialize()
                store.draft() to store.latestEvent()
            }
            draft = restored.first
            event = restored.second
            restored.second?.let { saved ->
                firstState = withContext(Dispatchers.IO) { store.relayState(saved.id, firstRelay) }
                secondState = withContext(Dispatchers.IO) { store.relayState(saved.id, secondRelay) }
                canDeliver = withContext(Dispatchers.IO) { store.deliveryTargets(saved.id).isNotEmpty() }
            }
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
                event = result.event
                canDeliver = withContext(Dispatchers.IO) { store.deliveryTargets(result.event.id).isNotEmpty() }
                firstState = withContext(Dispatchers.IO) { store.relayState(result.event.id, firstRelay.trim()) }
                secondState = withContext(Dispatchers.IO) { store.relayState(result.event.id, secondRelay.trim()) }
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
            Text("Public text bulletins. Anyone who gets a signed post can copy it. This prototype has no nearby transport or account recovery; do not rely on it for safety-critical communication.")
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
                        event = saved
                        canDeliver = true
                        draft = ""
                        firstState = "Pending"
                        secondState = "Pending"
                        deliver(saved)
                    } catch (failure: Exception) { error = failure.message ?: "Publish failed; check saved event" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Working…" else "Sign, save, and send") }
            OutlinedTextField(value = firstRelay, onValueChange = { firstRelay = it }, label = { Text("Relay 1") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = secondRelay, onValueChange = { secondRelay = it }, label = { Text("Relay 2") }, modifier = Modifier.fillMaxWidth())
            if (error.isNotEmpty()) Text(error)
            event?.let { saved ->
                Text("Latest saved bulletin", style = MaterialTheme.typography.titleMedium)
                Text(saved.content)
                Text("Signature valid. Author identity and report accuracy are not verified.")
                Text("Author key: ${saved.pubkey}")
                Text("Event ID: ${saved.id}")
                Text("Relay 1: $firstState")
                Text("Relay 2: $secondState")
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
            Text("Bridge test", style = MaterialTheme.typography.titleMedium)
            Text("Move signed public JSON to another phone by a method you choose. Importing never uses that phone's signing key.")
            OutlinedTextField(
                value = importWire,
                onValueChange = { importWire = it },
                enabled = ready && !busy,
                label = { Text("Paste signed event JSON") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Button(enabled = ready && !busy && importWire.isNotBlank() && importWire.toByteArray(Charsets.UTF_8).size <= 4096, onClick = {
                scope.launch {
                    busy = true
                    error = ""
                    try {
                        val imported = Nip01Protocol.fromJson(importWire.trim())
                        require(Nip01Protocol.verifyBulletin(imported)) { "Invalid event ID or signature" }
                        withContext(Dispatchers.IO) { store.saveEvent(imported, listOf(firstRelay.trim(), secondRelay.trim())) }
                        event = imported
                        canDeliver = true
                        firstState = withContext(Dispatchers.IO) { store.relayState(imported.id, firstRelay.trim()) }
                        secondState = withContext(Dispatchers.IO) { store.relayState(imported.id, secondRelay.trim()) }
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
        }
    }
}
