package org.freegram.shared.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.freegram.shared.model.FreegramUi
import org.freegram.shared.model.PersonState
import org.freegram.shared.model.PlatformActions
import org.freegram.shared.model.PostUi
import org.freegram.shared.model.SettingsPage
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

internal val Gutter = Modifier.padding(horizontal = 16.dp)

@Composable
internal fun Title(text: String) = Text(text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Fg.colors.ink)

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) =
    Text(text, fontSize = 13.sp, color = Fg.colors.ink3, modifier = modifier, textAlign = align)

// ---------- Home ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(ui: FreegramUi) {
    val c = Fg.colors
    var followingOnly by rememberSaveable { mutableStateOf(true) }
    val posts = if (followingOnly) ui.posts.filter { it.followed || it.mine } else ui.posts
    PullToRefreshBox(isRefreshing = ui.busy, onRefresh = ui::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Free", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = c.ink)
                    Text("gram", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = c.teal)
                    Spacer(Modifier.weight(1f))
                    NetPill(ui.online, ui.nearby.running)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(3.dp)) {
                    listOf(true to "Following", false to "Everything on this phone").forEach { (value, label) ->
                        val on = followingOnly == value
                        Text(label, textAlign = TextAlign.Center, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = if (on) c.ink else c.ink2,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) c.surface else Color.Transparent)
                                .selectable(on, onClick = { followingOnly = value }).padding(vertical = 8.dp))
                    }
                }
            }
            if (posts.isEmpty()) item {
                Hint(if (followingOnly) "No posts from people you follow yet. Follow someone from a post, or switch to Everything on this phone."
                else "No posts yet. Write one, or start Nearby sharing to receive posts from phones around you.", Modifier.padding(top = 24.dp), TextAlign.Center)
            }
            items(posts, key = { it.id }) { post -> PostCard(post, onClick = { ui.open(post.id) }) }
            item { Hint("Pull down to check servers for new posts.", Modifier.fillMaxWidth(), TextAlign.Center) }
        }
    }
}

// ---------- Post detail ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDetailScreen(ui: FreegramUi, post: PostUi) {
    val c = Fg.colors
    var sheet by remember(post.id) { mutableStateOf<String?>(null) }
    SystemBack(onBack = ui::closeDetail)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(Gutter).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ui::closeDetail) { Icon(FgIcons.Back, contentDescription = "Back", tint = c.ink) }
            Text("Post", fontWeight = FontWeight.SemiBold, color = c.ink, modifier = Modifier.weight(1f))
        }
        PostCard(post, onClick = null, onMore = { sheet = "menu" })
        var explain by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp))
            .clickable { explain = !explain }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What \"Signed\" means", fontWeight = FontWeight.SemiBold, color = c.ink)
            if (explain) {
                Text("This post was signed by ${if (post.mine) "your" else "this person's"} key and hasn't been changed since. It doesn't prove who they are or that the post is true.",
                    fontSize = 13.sp, color = c.ink2)
                Text("Post ${post.id.take(12)}…  ·  Author ${post.authorKey.take(12)}…", fontSize = 12.sp, color = c.ink3)
            }
        }
        if (!post.mine && !post.followed) FgButton("Follow ${post.authorLabel}", onClick = { ui.follow(post.authorKey) }, secondary = true)
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = c.surface) {
        if (sheet == "menu") PostMenu(ui, post, onReport = { sheet = "report" }, onDone = { sheet = null })
        else ReportSheet(ui, post, onDone = { sheet = null })
    }
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, hint: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    val c = Fg.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, contentDescription = null, tint = if (danger) c.red else c.ink2, modifier = Modifier.size(20.dp))
        Column {
            Text(text, color = if (danger) c.red else c.ink)
            if (hint != null) Text(hint, color = c.ink3, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PostMenu(ui: FreegramUi, post: PostUi, onReport: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.padding(bottom = 24.dp)) {
        if (!post.mine) {
            if (ui.canReport) MenuRow(FgIcons.Flag, "Report to maintainers", "Private. Only your maintainers can read it.", onClick = onReport)
            MenuRow(FgIcons.Mute, "Mute ${post.authorLabel}", "Hide their posts on this phone") { ui.mute(post.authorKey); onDone() }
            MenuRow(FgIcons.Block, "Block ${post.authorLabel}", "Hide and never pass on their posts") { ui.block(post.authorKey); onDone() }
            if (ui.isMaintainer) MenuRow(FgIcons.Hide, "Hide for my followers", "Maintainers only · public list") { ui.hideForFollowers(post.id); onDone() }
        }
        MenuRow(FgIcons.Trash, "Delete from this phone", "Copies on servers and other phones stay", danger = true) { ui.deleteLocal(post.id); onDone() }
    }
}

@Composable
private fun ReportSheet(ui: FreegramUi, post: PostUi, onDone: () -> Unit) {
    val c = Fg.colors
    var reason by remember { mutableStateOf(ui.reportReasons.first()) }
    var note by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Report this post", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = c.ink)
        Hint("Sent privately to your maintainers. They'll see it came from you; nobody else can read it.")
        ui.reportReasons.forEach { r ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp))
                .selectable(reason == r, onClick = { reason = r }).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = reason == r, onClick = { reason = r }, colors = RadioButtonDefaults.colors(selectedColor = c.teal))
                Text(r, color = c.ink)
            }
        }
        OutlinedTextField(note, { note = it.take(1000) }, label = { Text("Add a note (optional)") }, modifier = Modifier.fillMaxWidth())
        FgButton("Send report", onClick = { ui.report(post.id, reason, note); onDone() })
    }
}

// ---------- New post ----------

@Composable
fun ComposeScreen(ui: FreegramUi, platform: PlatformActions, onDone: () -> Unit) {
    val c = Fg.colors
    val bytes = ui.draft.encodeToByteArray().size
    SystemBack(onBack = onDone)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(Gutter).padding(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(FgIcons.Back, contentDescription = "Cancel", tint = c.ink) }
            Text("New post", fontWeight = FontWeight.SemiBold, color = c.ink)
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.amberSoft).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Public", color = c.amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("Anyone who gets this post can copy it.", color = c.ink, fontSize = 13.sp)
        }
        OutlinedTextField(ui.draft, ui::updateDraft, label = { Text("What's happening near you?") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp), enabled = ui.ready)
        Hint("$bytes / 2048", Modifier.fillMaxWidth(), TextAlign.End)
        val photo = ui.draftPhoto
        if (photo != null) {
            Image(photo, contentDescription = "Photo to post", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)))
            FgButton("Remove photo", onClick = ui::removePhoto, secondary = true)
        } else {
            FgButton("Add photo", onClick = platform.pickPhoto, secondary = true, enabled = ui.ready && !ui.busy)
        }
        Hint("Photos are shrunk to 1600 px and their location and camera details are removed.")
        FgButton(if (ui.busy) "Posting…" else "Post", onClick = { ui.publish(); onDone() },
            enabled = ui.ready && !ui.busy && (ui.draft.isNotBlank() || photo != null) && bytes <= 2048)
        Hint(if (ui.online) "Sends to your servers now, and to nearby phones when sharing is on."
        else "No internet: saved now, shared with nearby phones, and sent to servers when you're back online.")
    }
}

// ---------- Nearby ----------

@Composable
fun NearbyScreen(ui: FreegramUi, platform: PlatformActions) {
    val c = Fg.colors
    val n = ui.nearby
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(Gutter).padding(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Title("Nearby"); Spacer(Modifier.weight(1f)); NetPill(ui.online, n.running)
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 220.dp), contentAlignment = Alignment.Center) {
            if (n.running) {
                val pulse = rememberInfiniteTransition(label = "radar")
                listOf(0, 800, 1600).forEach { delay ->
                    val s by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(2400, delayMillis = delay), RepeatMode.Restart), label = "ring")
                    Box(Modifier.size(220.dp).scale(s).clip(CircleShape).border(BorderStroke(2.dp, c.amber.copy(alpha = 1f - s)), CircleShape))
                }
            }
            Column(
                Modifier.size(128.dp).clip(CircleShape).background(if (n.running) c.amber else c.surface).border(1.dp, c.line, CircleShape)
                    .clickable(enabled = ui.ready) { if (n.running) ui.setNearby(false) else platform.startNearby() },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(FgIcons.Nearby, contentDescription = null, tint = if (n.running) Color(0xFF1F1603) else c.ink2, modifier = Modifier.size(36.dp))
                Text(if (n.running) "Sharing" else "Off", fontWeight = FontWeight.Bold, color = if (n.running) Color(0xFF1F1603) else c.ink2)
            }
        }
        Text(if (n.running) "Swapping public posts with Freegram phones around you. Keeps going in the background for up to 2 hours."
        else "Tap to share public posts with Freegram phones around you, without internet.",
            color = c.ink2, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (n.status.isNotBlank()) Hint(n.status, Modifier.fillMaxWidth(), TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(n.exchanges to "phones met", n.postsReceived to "posts received", n.postsPassed to "posts passed on").forEach { (v, l) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$v", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = c.ink)
                    Text(l, fontSize = 11.sp, color = c.ink3)
                }
            }
        }
        if (n.activity.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp)) {
            n.activity.forEach { a ->
                Column(Modifier.padding(vertical = 10.dp)) { Text(a.text, color = c.ink, fontSize = 14.sp); Text(a.time, color = c.ink3, fontSize = 12.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Publish received posts when online", color = c.ink, fontSize = 14.sp)
                Text("Servers will see your phone's internet address uploading them.", color = c.ink3, fontSize = 12.sp)
            }
            Switch(n.autoPublish, ui::setAutoPublish, colors = SwitchDefaults.colors(checkedTrackColor = c.teal))
        }
        Hint("Nearby phones can tell a Freegram phone is here and which public posts it has. They never see your key.")
    }
}

// ---------- Profile ----------

@Composable
fun ProfileScreen(ui: FreegramUi, platform: PlatformActions, pages: List<SettingsPage>) {
    val c = Fg.colors
    var open by remember { mutableStateOf<SettingsDest?>(null) }
    var editing by remember { mutableStateOf(false) }
    open?.let { dest -> SettingsDestination(ui, platform, dest) { open = null }; return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(Gutter).padding(bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.size(8.dp))
        Avatar(ui.me.label, ui.me.hue, 72.dp)
        if (editing) {
            var name by remember { mutableStateOf(ui.me.name) }
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FgButton("Save name", onClick = { ui.setName(name.trim()); editing = false }, enabled = name.isNotBlank() && !ui.busy)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ui.me.label, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = c.ink)
                Text("· ${ui.me.tag}", fontSize = 14.sp, color = c.ink3)
            }
            Text("Edit name", color = c.teal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { editing = true }.padding(4.dp))
        }
        Hint("Show this code so others can follow you")
        val qr = rememberQrCodePainter("nostr:" + ui.me.npub)
        Box(Modifier.size(200.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {
            Image(qr, contentDescription = "QR code with your Freegram ID", modifier = Modifier.fillMaxSize())
        }
        Text(ui.me.npub, fontSize = 12.sp, color = c.ink2, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FgButton("Copy my ID", onClick = { platform.copyText("Freegram ID", ui.me.npub) }, secondary = true, modifier = Modifier.weight(1f))
            FgButton("Scan to follow", onClick = platform.scanToFollow, modifier = Modifier.weight(1f))
        }
        ListBox {
            ListRow("Backup and key", if (ui.me.backupDone) "✓ Encrypted backup made" else "Not backed up yet", highlight = !ui.me.backupDone) { open = SettingsDest.Backup }
            ListRow("People you follow", "${ui.people.count { it.state == PersonState.Following }} following") { open = SettingsDest.People }
        }
    }
}

@Composable
internal fun ListBox(content: @Composable () -> Unit) {
    val c = Fg.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp))) { content() }
}

@Composable
internal fun ListRow(title: String, subtitle: String, highlight: Boolean = false, onClick: () -> Unit) {
    val c = Fg.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink)
            Text(subtitle, color = if (highlight) c.amber else c.ink3, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Icon(FgIcons.Chevron, contentDescription = null, tint = c.ink3, modifier = Modifier.size(18.dp))
    }
}

/** Handles the phone's Back button/gesture so it steps back inside the app instead of closing it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SystemBack(onBack: () -> Unit) = BackHandler(enabled = true, onBack = onBack)
