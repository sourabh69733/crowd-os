package org.freegram.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.freegram.shared.model.PhotoUi
import org.freegram.shared.model.PostSource
import org.freegram.shared.model.PostUi

@Composable
fun Avatar(label: String, hue: Float, size: Dp = 40.dp) {
    val initials = label.removePrefix("npub1").filter { it.isLetterOrDigit() }.take(2).uppercase()
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(Color.hsl(hue, 0.55f, 0.42f), Color.hsl((hue + 40f) % 360f, 0.6f, 0.32f)))),
        contentAlignment = Alignment.Center,
    ) { Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp) }
}

@Composable
fun Chip(text: String, bg: Color, fg: Color) {
    Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
fun SourceChip(source: PostSource) {
    val c = Fg.colors
    when (source) {
        is PostSource.Nearby -> Chip("via nearby · ${if (source.hops == 1) "1 phone" else "${source.hops} phones"} away", c.amberSoft, c.amber)
        PostSource.Server -> Chip("via server", c.surface2, c.ink2)
        PostSource.Sending -> Chip("Sending…", c.surface2, c.ink2)
        is PostSource.Sent -> Chip("Sent to ${source.accepted} of ${source.total} servers", c.tealSoft, c.teal)
        PostSource.WaitingForInternet -> Chip("Waiting for internet", c.amberSoft, c.amber)
        PostSource.Rejected -> Chip("A server refused this post", c.redSoft, c.red)
        PostSource.OnlyHere -> Chip("Only on this phone", c.surface2, c.ink2)
    }
}

@Composable
fun PhotoView(photo: PhotoUi, maxHeight: Dp = 360.dp) {
    val c = Fg.colors
    when (photo) {
        is PhotoUi.Loaded -> Image(photo.image, contentDescription = "Post photo", contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(12.dp)))
        PhotoUi.Missing -> Text("Photo not on this phone yet. It will arrive from a nearby phone that has it.", color = c.ink3, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(14.dp))
        PhotoUi.None -> Unit
    }
}

@Composable
fun PostCard(post: PostUi, onClick: (() -> Unit)?, onMore: (() -> Unit)? = null) {
    val c = Fg.colors
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp))
    Column(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(post.authorLabel, post.authorHue, 38.dp)
            Column(Modifier.weight(1f)) {
                Text(if (post.mine) "You" else post.authorLabel, fontWeight = FontWeight.SemiBold, color = c.ink, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("✓ Signed", color = c.teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("· ${post.time}", color = c.ink3, fontSize = 12.sp)
                }
            }
            if (onMore != null) IconButton(onClick = onMore) { Icon(FgIcons.More, contentDescription = "Post options", tint = c.ink2) }
        }
        if (post.text.isNotBlank()) Text(post.text, color = c.ink, fontSize = 15.sp, lineHeight = 21.sp)
        PhotoView(post.photo)
        SourceChip(post.source)
    }
}

@Composable
fun FgButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, secondary: Boolean = false, danger: Boolean = false) {
    val c = Fg.colors
    val bg = when { danger -> c.redSoft; secondary -> c.surface; else -> c.teal }
    val fg = when { danger -> c.red; secondary -> c.ink; else -> c.onTeal }
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .then(if (secondary) Modifier.border(1.dp, c.line, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg.copy(alpha = if (enabled) 1f else 0.6f), fontWeight = FontWeight.SemiBold) }
}

@Composable
fun NetPill(online: Boolean, nearbyOn: Boolean) {
    val c = Fg.colors
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (online) c.tealSoft else c.amberSoft).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (online) c.teal else c.amber
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(fg))
        Text(if (online) "Online" else if (nearbyOn) "Offline · sharing nearby" else "Offline", color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
