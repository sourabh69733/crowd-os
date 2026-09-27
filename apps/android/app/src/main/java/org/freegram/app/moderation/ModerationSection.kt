package org.freegram.app.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.freegram.app.store.Maintainer

@Composable
fun ModerationSection(
    maintainers: List<Maintainer>,
    myKey: String,
    input: String,
    onInputChange: (String) -> Unit,
    hiddenCount: Int,
    myList: HideList?,
    message: String,
    enabled: Boolean,
    onAdd: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onRefresh: () -> Unit,
    onUnhidePost: (String) -> Unit,
    onUnhideAuthor: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Maintainers", style = MaterialTheme.typography.titleMedium)
        Text("Maintainers are people you trust to hide abusive posts. Each signs their own public hide list. Turn one off to stop applying their list; hidden posts stay on this phone.")
        if (message.isNotEmpty()) Text(message)
        Text("$hiddenCount saved posts are hidden by maintainers you follow.")
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            enabled = enabled,
            label = { Text("Maintainer public key (npub1… or hex)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = enabled && input.isNotBlank(), onClick = onAdd) { Text("Follow maintainer") }
        maintainers.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = m.enabled, enabled = enabled, onCheckedChange = { onToggle(m.pubkey, it) })
                Text(m.pubkey.take(16) + "…" + if (m.pubkey == myKey) " (you)" else "")
            }
            Button(enabled = enabled, onClick = { onRemove(m.pubkey) }) { Text("Stop following ${m.pubkey.take(8)}…") }
        }
        Button(enabled = enabled && maintainers.isNotEmpty(), onClick = onRefresh) { Text("Refresh hide lists") }
        if (myList != null && (myList.posts.isNotEmpty() || myList.authors.isNotEmpty())) {
            Text("Your hide list (public): ${myList.posts.size} posts, ${myList.authors.size} authors")
            myList.posts.take(20).forEach { id -> Button(enabled = enabled, onClick = { onUnhidePost(id) }) { Text("Unhide post ${id.take(12)}…") } }
            myList.authors.take(20).forEach { key -> Button(enabled = enabled, onClick = { onUnhideAuthor(key) }) { Text("Unhide author ${key.take(12)}…") } }
        }
    }
}
