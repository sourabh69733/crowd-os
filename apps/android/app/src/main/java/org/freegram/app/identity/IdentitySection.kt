package org.freegram.app.identity

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun IdentitySection(
    npub: String,
    pubkeyHex: String,
    revealedBackup: String?,
    restoreInput: String,
    onRestoreInputChange: (String) -> Unit,
    confirmingRestore: Boolean,
    confirmingReplace: Boolean,
    message: String,
    enabled: Boolean,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onRestore: () -> Unit,
    onReplace: () -> Unit,
    onCancelConfirm: () -> Unit,
    backupPassword: String,
    onBackupPasswordChange: (String) -> Unit,
    backupPasswordConfirm: String,
    onBackupPasswordConfirmChange: (String) -> Unit,
    encryptedBackup: String?,
    onCreateEncrypted: () -> Unit,
    onCopyEncrypted: (String) -> Unit,
    restorePassword: String,
    onRestorePasswordChange: (String) -> Unit,
) {
    val activity = LocalContext.current as? Activity
    if (revealedBackup != null && activity != null) {
        // Blocks screenshots and the recent-apps preview while the secret is visible.
        DisposableEffect(activity) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your signing identity", style = MaterialTheme.typography.titleMedium)
        Text("Share your public key so others can follow you: $npub")
        Text("Hex public key: $pubkeyHex")
        if (message.isNotEmpty()) Text(message)

        Text("Plain backup: anyone with the secret key can post as you. Write it on paper and keep it offline. Without any backup, uninstall or phone loss loses this identity.")
        if (revealedBackup == null) {
            Button(enabled = enabled, onClick = onReveal) { Text("Show secret backup key") }
        } else {
            Text(revealedBackup, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onHide) { Text("Hide secret key") }
        }

        Text("Encrypted backup (recommended)", style = MaterialTheme.typography.titleSmall)
        Text("Protect the key with a password. The encrypted backup can be copied or saved; it needs the password to restore.")
        OutlinedTextField(value = backupPassword, onValueChange = onBackupPasswordChange, enabled = enabled, singleLine = true,
            label = { Text("Password (at least ${ProtectedIdentity.MIN_PASSWORD} characters)") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = backupPasswordConfirm, onValueChange = onBackupPasswordConfirmChange, enabled = enabled, singleLine = true,
            label = { Text("Repeat password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = enabled && backupPassword.length >= ProtectedIdentity.MIN_PASSWORD && backupPassword == backupPasswordConfirm, onClick = onCreateEncrypted) {
            Text("Create encrypted backup")
        }
        encryptedBackup?.let { backup ->
            Text(backup)
            Button(onClick = { onCopyEncrypted(backup) }) { Text("Copy encrypted backup") }
        }
        OutlinedTextField(
            value = restoreInput,
            onValueChange = onRestoreInputChange,
            enabled = enabled,
            label = { Text("Restore from backup (ncryptsec1… or nsec1…)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (restoreInput.trim().startsWith("ncryptsec1", ignoreCase = true)) {
            OutlinedTextField(value = restorePassword, onValueChange = onRestorePasswordChange, enabled = enabled, singleLine = true,
                label = { Text("Backup password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
        if (confirmingRestore) Text("This replaces the key on this phone. Back up the current key first if you still need it.")
        Button(enabled = enabled && (restoreInput.trim().startsWith("nsec1", ignoreCase = true) ||
            (restoreInput.trim().startsWith("ncryptsec1", ignoreCase = true) && restorePassword.isNotEmpty())), onClick = onRestore) {
            Text(if (confirmingRestore) "Confirm restore" else "Restore identity")
        }

        Text("If this key may be stolen, create a new one and tell followers the new public key through a channel they already trust. The old key cannot be revoked and can still sign posts.")
        if (confirmingReplace) Text("The current key will be removed from this phone. Posts already signed stay valid.")
        Button(enabled = enabled, onClick = onReplace) {
            Text(if (confirmingReplace) "Confirm: create new key" else "Replace with new key")
        }
        if (confirmingRestore || confirmingReplace) Button(onClick = onCancelConfirm) { Text("Cancel") }
    }
}
