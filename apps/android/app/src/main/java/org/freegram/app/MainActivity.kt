package org.freegram.app

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freegram.app.identity.ProtectedIdentity
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.relay.RelayClient
import org.freegram.app.store.LocalStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FreegramScreen(LocalStore(this), ProtectedIdentity(this), RelayClient()) }
    }
}

@Composable
private fun FreegramScreen(store: LocalStore, identity: ProtectedIdentity, relayClient: RelayClient) {
    var draft by remember { mutableStateOf(store.draft()) }
    var event by remember { mutableStateOf(store.latestEvent()) }
    var firstRelay by remember { mutableStateOf(store.relayUrl(0)) }
    var secondRelay by remember { mutableStateOf(store.relayUrl(1)) }
    var firstState by remember(event) { mutableStateOf(event?.let { store.relayState(it.id, firstRelay) } ?: "") }
    var secondState by remember(event) { mutableStateOf(event?.let { store.relayState(it.id, secondRelay) } ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun deliver(saved: BulletinEvent) {
        val relays = listOf(firstRelay.trim(), secondRelay.trim())
        require(relays.all { it.startsWith("wss://") } && relays.distinct().size == 2) { "Use two distinct wss:// relay URLs" }
        withContext(Dispatchers.IO) {
            store.setRelayUrl(0, relays[0])
            store.setRelayUrl(1, relays[1])
        }
        val first = withContext(Dispatchers.IO) { relayClient.publish(relays[0], saved) }
        withContext(Dispatchers.IO) { store.setRelayState(saved.id, relays[0], first) }
        firstState = first
        val second = withContext(Dispatchers.IO) { relayClient.publish(relays[1], saved) }
        withContext(Dispatchers.IO) { store.setRelayState(saved.id, relays[1], second) }
        secondState = second
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
                label = { Text("Bulletin draft") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )
            Text("${draft.toByteArray(Charsets.UTF_8).size}/2048 UTF-8 bytes")
            Button(enabled = !busy, onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.saveDraft(draft) }
                        error = "Draft saved locally"
                    } catch (failure: Exception) { error = failure.message ?: "Could not save draft" }
                }
            }) { Text("Save draft") }
            Button(enabled = !busy && draft.isNotBlank() && draft.toByteArray(Charsets.UTF_8).size <= 2048, onClick = {
                scope.launch {
                    busy = true
                    error = ""
                    try {
                        require(firstRelay.trim().startsWith("wss://") && secondRelay.trim().startsWith("wss://") && firstRelay.trim() != secondRelay.trim())
                        val saved = withContext(Dispatchers.IO) {
                            val signed = identity.withSecret { secret ->
                                Nip01Protocol.signBulletin(secret, draft, System.currentTimeMillis() / 1000)
                            }
                            store.saveEvent(signed)
                            store.saveDraft("")
                            signed
                        }
                        event = saved
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
                Text("Event ID: ${saved.id}")
                Text("Relay 1: $firstState")
                Text("Relay 2: $secondState")
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        error = ""
                        try { deliver(saved) }
                        catch (failure: Exception) { error = failure.message ?: "Retry failed" }
                        finally { busy = false }
                    }
                }) { Text("Retry relay delivery") }
            }
        }
    }
}
