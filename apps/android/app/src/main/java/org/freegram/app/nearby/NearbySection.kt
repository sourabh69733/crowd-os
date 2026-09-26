package org.freegram.app.nearby

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun NearbySection(
    running: Boolean,
    status: String,
    log: List<String>,
    enabled: Boolean,
    autoBridge: Boolean,
    onAutoBridgeChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPermissionDenied: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) onStart() else onPermissionDenied()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Share nearby (experimental)", style = MaterialTheme.typography.titleMedium)
        Text("Swaps recent public posts with nearby Freegram phones over Bluetooth and Wi-Fi, without internet. Works only while this screen is open. Nearby phones can see that a Freegram phone is here and which public posts it holds. Posts older than 48 hours or passed through 6 phones are not shared.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = autoBridge, onCheckedChange = onAutoBridgeChange)
            Text("Publish posts received nearby to the relays when online")
        }
        Text("Relays and your network will see this phone's internet address uploading other people's posts. Publishing does not change a post or make you its author.")
        if (status.isNotEmpty()) Text(status)
        if (running) Button(onClick = onStop) { Text("Stop sharing nearby") }
        else Button(enabled = enabled, onClick = { launcher.launch(nearbyPermissions()) }) { Text("Start sharing nearby") }
        log.forEach { Text(it) }
    }
}
