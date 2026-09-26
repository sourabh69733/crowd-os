package org.freegram.app.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.store.AuthorPolicy
import org.freegram.app.store.AuthorState

@Composable
fun FollowedFeedSection(
    authorInput: String,
    onAuthorInputChange: (String) -> Unit,
    policies: List<AuthorPolicy>,
    events: List<BulletinEvent>,
    relayResults: List<RelayRefreshResult>,
    message: String,
    enabled: Boolean,
    onFollow: () -> Unit,
    onChangeState: (String, AuthorState) -> Unit,
    onRemove: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (BulletinEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Followed-author feed", style = MaterialTheme.typography.titleMedium)
        Text("Follow a public key, then refresh manually. Each relay can see which keys you request. A valid signature does not verify a person's identity or the report.")
        if (message.isNotEmpty()) Text(message)
        OutlinedTextField(
            value = authorInput,
            onValueChange = onAuthorInputChange,
            enabled = enabled,
            label = { Text("Author public key (64 hex characters)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = enabled && authorInput.trim().length == 64, onClick = onFollow) { Text("Follow key") }
        policies.forEach { policy ->
            Text("${policy.pubkey} · ${policy.state.name.lowercase()}")
            when (policy.state) {
                AuthorState.FOLLOWING -> {
                    Button(enabled = enabled, onClick = { onChangeState(policy.pubkey, AuthorState.MUTED) }) { Text("Mute") }
                    Button(enabled = enabled, onClick = { onChangeState(policy.pubkey, AuthorState.BLOCKED) }) { Text("Block") }
                    Button(enabled = enabled, onClick = { onRemove(policy.pubkey) }) { Text("Unfollow") }
                }
                AuthorState.MUTED -> {
                    Button(enabled = enabled, onClick = { onChangeState(policy.pubkey, AuthorState.FOLLOWING) }) { Text("Unmute") }
                    Button(enabled = enabled, onClick = { onChangeState(policy.pubkey, AuthorState.BLOCKED) }) { Text("Block") }
                    Button(enabled = enabled, onClick = { onRemove(policy.pubkey) }) { Text("Unfollow") }
                }
                AuthorState.BLOCKED -> Button(enabled = enabled, onClick = { onRemove(policy.pubkey) }) { Text("Unblock") }
            }
        }
        Button(enabled = enabled && policies.any { it.state == AuthorState.FOLLOWING }, onClick = onRefresh) {
            Text("Refresh from both relays")
        }
        relayResults.forEach { result -> Text("${result.relay}: ${result.status}; ${result.verifiedEvents} verified") }
        Text("Recent verified posts (${events.size} saved from followed keys)")
        if (events.isEmpty()) Text("No posts from followed keys saved yet")
        events.take(20).forEach { event ->
            Text(event.content.take(200))
            Text("Signed by ${event.pubkey.take(16)}… · identity and report unverified")
            Button(enabled = enabled, onClick = { onOpen(event) }) { Text("Open ${event.id.take(12)}…") }
        }
    }
}
