package org.freegram.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.freegram.shared.model.FreegramUi
import org.freegram.shared.model.PlatformActions
import org.freegram.shared.model.PostUi
import org.freegram.shared.model.VideoUi

/** Full-screen video posts, one per page. Only the reel on screen plays. */
@Composable
fun ReelsScreen(ui: FreegramUi, platform: PlatformActions) {
    val reels = ui.reels
    if (reels.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Hint("No videos yet. Post one with the + button, or turn on Nearby to get videos from phones around you.",
                align = TextAlign.Center)
        }
        return
    }
    val pager = rememberPagerState { reels.size }
    // Queue the reel on screen and the next two, so they're ready by the time the user swipes.
    LaunchedEffect(pager.currentPage, reels.size) {
        ui.prefetchReels(reels.drop(pager.currentPage).take(3).map { it.id })
    }
    VerticalPager(pager, Modifier.fillMaxSize().background(Color.Black), key = { reels[it].id }) { page ->
        Reel(ui, platform, reels[page], playing = page == pager.currentPage)
    }
}

@Composable
private fun Reel(ui: FreegramUi, platform: PlatformActions, post: PostUi, playing: Boolean) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val video = post.video) {
            is VideoUi.Ready -> if (playing) platform.videoPlayer(video.path, true)
                else video.thumbnail?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            is VideoUi.Receiving -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Getting video · ${video.duration} · ${video.size}", color = Color.White, fontSize = 14.sp)
                LinearProgressIndicator(progress = { if (video.total == 0) 0f else video.have.toFloat() / video.total },
                    modifier = Modifier.fillMaxWidth(), color = Fg.colors.amber, trackColor = Color.White.copy(alpha = 0.2f))
                Text("${video.have} of ${video.total} pieces", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                Text("Reels downloads the videos you're about to watch, which can use mobile data.",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, textAlign = TextAlign.Center)
            }
            is VideoUi.Waiting -> Text("Video ${video.duration}. It will arrive from a nearby phone or the server.",
                color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp))
            VideoUi.None -> Unit
        }
        // Caption over a dark fade, so text stays readable on bright videos.
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .clickable { ui.open(post.id) }.padding(16.dp).padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(post.authorLabel, post.authorHue, 32.dp)
                Text(if (post.mine) "You" else post.authorLabel, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("· ${post.authorTag} · ${post.time}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
            if (post.text.isNotBlank()) Text(post.text, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${if (post.likedByMe) "♥" else "♡"} ${post.likes}", color = if (post.likedByMe) Fg.colors.red else Color.White, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { ui.like(post.id, !post.likedByMe) }.padding(4.dp))
                Text("↩ ${post.replies}", color = Color.White, fontSize = 15.sp)
                Text("Open post", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            }
        }
    }
}
