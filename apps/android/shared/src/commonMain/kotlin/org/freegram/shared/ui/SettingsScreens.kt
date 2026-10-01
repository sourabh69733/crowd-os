package org.freegram.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import org.freegram.shared.model.FreegramUi
import org.freegram.shared.model.PersonState
import org.freegram.shared.model.PlatformActions
import org.freegram.shared.model.SettingsPage

enum class SettingsDest(val title: String) {
    Backup("Backup and key"), People("People you follow"), Maintainers("Maintainers"), Servers("Servers"), Storage("Storage"),
    Wipe("Panic wipe"),
}

private const val MIN_PASSWORD = 10

@Composable
fun SettingsScreen(ui: FreegramUi, platform: PlatformActions, extraPages: List<SettingsPage>, onBack: () -> Unit) {
    var dest by remember { mutableStateOf<SettingsDest?>(null) }
    var extra by remember { mutableStateOf<SettingsPage?>(null) }
    dest?.let { SettingsDestination(ui, platform, it) { dest = null }; return }
    extra?.let { page -> Page(page.title, onBack = { extra = null }) { page.content() }; return }
    val following = ui.people.count { it.state == PersonState.Following }
    val s = ui.storageInfo
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(Gutter).padding(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(FgIcons.Back, contentDescription = "Back", tint = Fg.colors.ink) }
            Title("Settings")
        }
        ListBox {
            ListRow("Backup and key", if (ui.me.backupDone) "✓ Encrypted backup made" else "Not backed up yet", highlight = !ui.me.backupDone) { dest = SettingsDest.Backup }
            ListRow("People you follow", "$following following · ${ui.people.size - following} muted or blocked") { dest = SettingsDest.People }
            ListRow("Maintainers", "${ui.maintainerList.count { it.enabled }} active · ${s.hiddenByMaintainers} posts hidden") { dest = SettingsDest.Maintainers }
        }
        ListBox {
            ListRow("Servers", "${ui.relayUrls.size} servers") { dest = SettingsDest.Servers }
            ListRow("Storage", "${s.posts} of ${s.maxPosts} posts · ${mb(s.photoBytes)} of ${mb(s.maxPhotoBytes)} photos") { dest = SettingsDest.Storage }
        }
        ToggleRow("Show my posts in Discover", "New posts get a #freegram tag so people can find them. They're public either way.",
            ui.discover.showMyPosts, ui::setShowInDiscover)
        ListBox { ListRow("Panic wipe", "Erase Freegram from this phone in seconds") { dest = SettingsDest.Wipe } }
        if (extraPages.isNotEmpty()) ListBox { extraPages.forEach { p -> ListRow(p.title, p.subtitle) { extra = p } } }
        Hint("Freegram prototype · not for safety-critical use yet.")
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Fg.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 14.sp)
            Text(hint, color = c.ink3, fontSize = 12.sp)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.teal))
    }
}

private fun mb(bytes: Long) = "${(bytes + 524_287) / 1_048_576} MB"

@Composable
fun SettingsDestination(ui: FreegramUi, platform: PlatformActions, dest: SettingsDest, onBack: () -> Unit) {
    Page(dest.title, onBack) {
        when (dest) {
            SettingsDest.Backup -> BackupPage(ui, platform)
            SettingsDest.People -> PeoplePage(ui)
            SettingsDest.Maintainers -> MaintainersPage(ui)
            SettingsDest.Servers -> ServersPage(ui)
            SettingsDest.Storage -> StoragePage(ui)
            SettingsDest.Wipe -> WipePage(ui)
        }
    }
}

@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val c = Fg.colors
    SystemBack(onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(Gutter).padding(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(FgIcons.Back, contentDescription = "Back", tint = c.ink) }
            Text(title, fontWeight = FontWeight.SemiBold, color = c.ink)
        }
        content()
    }
}

@Composable
private fun Section(title: String, body: String? = null) {
    val c = Fg.colors
    Spacer(Modifier.height(4.dp))
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = c.ink)
    if (body != null) Text(body, fontSize = 13.sp, color = c.ink2)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = Fg.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun PersonRow(label: String, tag: String, hue: Float, trailing: @Composable () -> Unit) {
    val c = Fg.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar(label, hue, 34.dp)
        Column(Modifier.weight(1f)) {
            Text(label, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("· $tag", color = c.ink3, fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
private fun SmallAction(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = Fg.colors
    Text(text, color = if (danger) c.red else c.teal, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(8.dp))
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())

// ---------- Backup and key ----------

@Composable
private fun BackupPage(ui: FreegramUi, platform: PlatformActions) {
    val c = Fg.colors
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var restoreCode by remember { mutableStateOf("") }
    var restorePw by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }

    Card {
        Text(if (ui.me.backupDone) "✓ You have made an encrypted backup" else "No backup yet", fontWeight = FontWeight.SemiBold,
            color = if (ui.me.backupDone) c.teal else c.amber)
        Text("Your ID is a secret key on this phone. Without a backup, losing the phone or deleting the app loses your ID for good.",
            fontSize = 13.sp, color = c.ink2)
    }

    Section("Encrypted backup", "Protects your key with a password. The code is safe to copy or save; it's useless without the password.")
    val fresh = ui.freshBackup
    if (fresh != null) {
        Text(fresh, fontSize = 12.sp, color = c.ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(12.dp))
        FgButton("Copy backup code", onClick = { platform.copyText("Freegram backup", fresh) }, secondary = true)
    } else {
        PasswordField(pw, { pw = it }, "Password (at least $MIN_PASSWORD characters)")
        PasswordField(pw2, { pw2 = it }, "Repeat password")
        if (pw2.isNotEmpty() && pw != pw2) Text("Passwords don't match.", color = c.red, fontSize = 13.sp)
        FgButton(if (ui.busy) "Encrypting…" else "Create encrypted backup", onClick = { ui.createBackup(pw); pw = ""; pw2 = "" },
            enabled = !ui.busy && pw.length >= MIN_PASSWORD && pw == pw2)
    }

    Section("Restore an ID", "Replaces the ID on this phone with one from a backup code.")
    OutlinedTextField(restoreCode, { restoreCode = it.trim(); confirmRestore = false }, label = { Text("Backup code (ncryptsec1…)") }, modifier = Modifier.fillMaxWidth())
    PasswordField(restorePw, { restorePw = it }, "Password")
    if (confirmRestore) Text("Your current ID will be replaced. Back it up first if you still need it.", color = c.amber, fontSize = 13.sp)
    FgButton(
        if (confirmRestore) "Confirm restore" else "Restore", secondary = !confirmRestore,
        enabled = !ui.busy && restoreCode.startsWith("ncryptsec1", ignoreCase = true) && restorePw.isNotEmpty(),
        onClick = {
            if (!confirmRestore) confirmRestore = true
            else { ui.restoreBackup(restoreCode, restorePw); restoreCode = ""; restorePw = ""; confirmRestore = false }
        },
    )

    Section("If your key is stolen", "Make a new ID and tell people your new one through a channel they already trust. The old key can't be cancelled and can still sign posts.")
    if (confirmReplace) Text("This phone will switch to a brand-new ID. Posts you already made keep your old one.", color = c.amber, fontSize = 13.sp)
    FgButton(if (confirmReplace) "Confirm: make a new ID" else "Make a new ID", danger = true,
        onClick = { if (confirmReplace) { ui.replaceKeyNow(); confirmReplace = false } else confirmReplace = true })

    Section("Plain secret key", "Anyone who sees this can post as you. Use it only to move your ID to another Nostr app.")
    val key = ui.plainKey
    if (key == null) {
        FgButton("Show secret key", onClick = ui::revealPlainKey, secondary = true)
    } else {
        DisposableEffect(Unit) { platform.setSecureScreen(true); onDispose { platform.setSecureScreen(false) } }
        Text(key, fontSize = 13.sp, color = c.red, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.redSoft).padding(12.dp))
        FgButton("Hide secret key", onClick = ui::hidePlainKey, secondary = true)
    }
}

// ---------- People ----------

@Composable
private fun PeoplePage(ui: FreegramUi) {
    var id by remember { mutableStateOf("") }
    Section("Follow someone", "Paste their Freegram ID, or scan their code from your Profile.")
    OutlinedTextField(id, { id = it.trim() }, label = { Text("ID (npub1…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FgButton("Follow", onClick = { ui.followId(id); id = "" }, enabled = id.startsWith("npub1", ignoreCase = true) && !ui.busy)
    listOf(PersonState.Following to "Following", PersonState.Muted to "Muted", PersonState.Blocked to "Blocked").forEach { (state, title) ->
        val list = ui.people.filter { it.state == state }
        if (list.isNotEmpty()) {
            Section(title, when (state) {
                PersonState.Following -> null
                PersonState.Muted -> "Hidden on this phone, but still passed on nearby."
                PersonState.Blocked -> "Hidden and never passed on."
            })
            Card {
                list.forEach { p ->
                    PersonRow(p.label, p.tag, p.hue) {
                        when (state) {
                            PersonState.Following -> SmallAction("Unfollow") { ui.unfollow(p.key) }
                            PersonState.Muted -> SmallAction("Unmute") { ui.unmute(p.key) }
                            PersonState.Blocked -> SmallAction("Unblock") { ui.unblock(p.key) }
                        }
                    }
                }
            }
        }
    }
    if (ui.people.isEmpty()) Hint("You don't follow anyone yet.")
}

// ---------- Maintainers ----------

@Composable
private fun MaintainersPage(ui: FreegramUi) {
    val c = Fg.colors
    var id by remember { mutableStateOf("") }
    var appealText by remember { mutableStateOf("") }
    var appealPost by remember { mutableStateOf("") }
    Card {
        Text("Maintainers hide abusive or illegal posts for everyone who follows them. Each signs their own public list, so you can see and switch off any of them. Hidden posts are not deleted.",
            fontSize = 13.sp, color = c.ink2)
        Text("${ui.storageInfo.hiddenByMaintainers} posts on this phone are hidden right now.", fontSize = 13.sp, color = c.ink)
    }
    if (ui.maintainerList.isNotEmpty()) Card {
        ui.maintainerList.forEach { m ->
            PersonRow(if (m.isMe) "${m.label} (you)" else m.label, m.tag, m.hue) {
                Switch(m.enabled, { ui.setMaintainerOn(m.key, it) }, colors = SwitchDefaults.colors(checkedTrackColor = c.teal))
                SmallAction("Remove", danger = true) { ui.dropMaintainer(m.key) }
            }
        }
    }
    FgButton("Refresh hide lists", onClick = ui::refreshMaintainers, enabled = ui.maintainerList.isNotEmpty() && !ui.busy, secondary = true)
    Section("Add a maintainer")
    OutlinedTextField(id, { id = it.trim() }, label = { Text("Maintainer ID (npub1…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FgButton("Add", onClick = { ui.addMaintainerId(id); id = "" }, enabled = id.startsWith("npub1", ignoreCase = true) && !ui.busy)

    val others = ui.maintainerList.filter { !it.isMe }
    if (others.isNotEmpty()) {
        Section("Appeal a decision", "If a maintainer hid your post or ID, tell them privately why it should change.")
        OutlinedTextField(appealText, { appealText = it.take(1000) }, label = { Text("Why should this change?") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(appealPost, { appealPost = it.trim() }, label = { Text("Post ID (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        others.forEach { m ->
            FgButton("Send appeal to ${m.label}", onClick = { ui.appeal(m.key, appealText, appealPost); appealText = ""; appealPost = "" },
                enabled = appealText.isNotBlank() && !ui.busy, secondary = true)
        }
    }

    if (ui.isMaintainer) {
        Section("Your maintainer inbox", "Private reports and appeals sent to you. Only you can read them.")
        FgButton("Check reports and appeals", onClick = ui::loadInbox, enabled = !ui.busy)
        ui.inboxList.forEach { item ->
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(if (item.isReport) "Report" else "Appeal", if (item.isReport) c.redSoft else c.amberSoft, if (item.isReport) c.red else c.amber)
                    Text("from ${item.fromLabel} · ${item.time}", fontSize = 12.sp, color = c.ink3)
                }
                Text(item.reason, fontWeight = FontWeight.SemiBold, color = c.ink)
                if (item.note.isNotBlank()) Text(item.note, fontSize = 14.sp, color = c.ink2)
                item.postId?.let { postId ->
                    Text("Post ${postId.take(12)}…", fontSize = 12.sp, color = c.ink3)
                    if (item.isReport && !item.alreadyHidden) FgButton("Hide this post for my followers", onClick = { ui.hideReported(postId) }, danger = true)
                    if (!item.isReport && item.alreadyHidden) FgButton("Unhide this post", onClick = { ui.unhidePostId(postId) }, secondary = true)
                }
            }
        }
        if (ui.myHiddenPosts.isNotEmpty() || ui.myHiddenAuthors.isNotEmpty()) {
            Section("Your public hide list", "${ui.myHiddenPosts.size} posts and ${ui.myHiddenAuthors.size} people. Anyone can see this list.")
            Card {
                ui.myHiddenAuthors.forEach { p -> PersonRow(p.label, p.tag, p.hue) { SmallAction("Unhide") { ui.unhideAuthorKey(p.key) } } }
                ui.myHiddenPosts.take(30).forEach { postId ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Post ${postId.take(12)}…", fontSize = 13.sp, color = c.ink2, modifier = Modifier.weight(1f))
                        SmallAction("Unhide") { ui.unhidePostId(postId) }
                    }
                }
            }
        }
    }
}

// ---------- Servers and storage ----------

@Composable
private fun ServersPage(ui: FreegramUi) {
    var first by remember { mutableStateOf(ui.relayUrls.getOrElse(0) { "" }) }
    var second by remember { mutableStateOf(ui.relayUrls.getOrElse(1) { "" }) }
    Card {
        Text("Posts go to two servers (Nostr relays) run by different people, so one failing doesn't stop you. Change them only if your group runs its own.",
            fontSize = 13.sp, color = Fg.colors.ink2)
    }
    OutlinedTextField(first, { first = it.trim() }, label = { Text("Server 1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(second, { second = it.trim() }, label = { Text("Server 2") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val valid = first.startsWith("wss://") && second.startsWith("wss://") && first != second
    if (!valid) Hint("Both must start with wss:// and be different.")
    FgButton("Save servers", onClick = { ui.saveRelays(first, second) }, enabled = valid && !ui.busy)
}

@Composable
private fun StoragePage(ui: FreegramUi) {
    val c = Fg.colors
    val s = ui.storageInfo
    Card {
        Text("Posts: ${s.posts} of ${s.maxPosts}", color = c.ink, fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(progress = { (s.posts / s.maxPosts.toFloat()).coerceIn(0f, 1f) }, color = c.teal, trackColor = c.surface2, modifier = Modifier.fillMaxWidth())
        Text("Photos: ${mb(s.photoBytes)} of ${mb(s.maxPhotoBytes)}", color = c.ink, fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(progress = { (s.photoBytes / s.maxPhotoBytes.toFloat()).coerceIn(0f, 1f) }, color = c.teal, trackColor = c.surface2, modifier = Modifier.fillMaxWidth())
    }
    Card {
        Text("When full, Freegram removes posts from blocked people first, then the oldest posts from others, then your oldest posts that every server already has. Posts still waiting to be sent are never removed.",
            fontSize = 13.sp, color = c.ink2)
        Text("Each person can have at most 20 posts on your phone, so one account can't fill it.", fontSize = 13.sp, color = c.ink2)
    }
}

// ---------- Panic wipe ----------

private const val HOLD_MS = 2_000

@Composable
private fun WipePage(ui: FreegramUi) {
    val c = Fg.colors
    Card {
        Text("Erases Freegram from this phone", fontWeight = FontWeight.SemiBold, color = c.red)
        Text("Your ID key, posts, photos, the people you follow and your maintainers are deleted, and the app closes. " +
            "Next time it opens like a new install.", fontSize = 13.sp, color = c.ink2)
    }
    Section("What it can't erase", "Posts already on servers or other phones stay there; they're public. " +
        "Without an encrypted backup your ID is gone for good. With one, you can restore it on any phone.")
    Hint(if (ui.me.backupDone) "✓ You have an encrypted backup." else "You have no backup yet. Make one in Backup and key first if you want to keep your ID.")
    Spacer(Modifier.height(8.dp))
    HoldToConfirm("Hold to wipe", onConfirm = ui::panicWipe)
    Hint("Press and hold for 2 seconds. Letting go early cancels.")
}

/** A button that fires only after being held for [HOLD_MS], so a stray tap can't trigger it. */
@Composable
private fun HoldToConfirm(text: String, onConfirm: () -> Unit) {
    val c = Fg.colors
    var progress by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).background(c.redSoft)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val hold = scope.launch {
                        // Measure real time: on a slow phone, frames (and short delays) can take much longer than asked.
                        val start = TimeSource.Monotonic.markNow()
                        while (progress < 1f) {
                            delay(30)
                            progress = (start.elapsedNow().inWholeMilliseconds.toFloat() / HOLD_MS).coerceAtMost(1f)
                        }
                        onConfirm()
                    }
                    tryAwaitRelease()
                    if (progress < 1f) { hold.cancel(); progress = 0f }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).align(Alignment.CenterStart).background(c.red.copy(alpha = 0.35f)))
        Text(if (progress > 0f) "Keep holding…" else text, color = c.red, fontWeight = FontWeight.Bold)
    }
}
