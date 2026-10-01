package org.freegram.shared.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.freegram.shared.model.FoundPersonUi
import org.freegram.shared.model.FreegramUi

/** Find people to follow: search, maintainers' suggestions, and recent public Freegram posts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(ui: FreegramUi) {
    val c = Fg.colors
    val d = ui.discover
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { if (d.posts.isEmpty() && !d.loading) ui.loadDiscover() }
    PullToRefreshBox(isRefreshing = d.loading, onRefresh = ui::loadDiscover, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Title("Discover") }
            item {
                OutlinedTextField(query, { query = it.take(100) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(FgIcons.Search, contentDescription = null, tint = c.ink3) },
                    placeholder = { Text("Search by name or ID (npub1…)") })
            }
            if (query.isNotBlank()) {
                val found = ui.searchPeople(query)
                if (found.isEmpty()) item {
                    Hint("No one found. Search knows names of people this phone has seen; for anyone else, paste their full ID.")
                } else items(found, key = { "s" + it.key }) { PersonCard(ui, it) }
                return@LazyColumn
            }
            if (d.note.isNotEmpty()) item { Hint(d.note) }

            item { SectionTitle("Suggested by your maintainers") }
            when {
                d.suggested.isNotEmpty() -> items(d.suggested, key = { "p" + it.key }) { PersonCard(ui, it) }
                !d.hasMaintainers -> item { Hint("Add a maintainer in Settings → Maintainers to see the people they suggest.") }
                else -> item { Hint("Your maintainers haven't suggested anyone yet.") }
            }

            item { SectionTitle("Recent posts") }
            if (d.posts.isEmpty() && !d.loading) item { Hint("No public Freegram posts found yet. Pull down to try again.") }
            items(d.posts, key = { "d" + it.id }) { post ->
                PostCard(post, onClick = { ui.open(post.id) }, onLike = { ui.like(post.id, !post.likedByMe) })
            }
            item {
                Hint("Recent public posts from Freegram users. Posts hidden by your maintainers, and people you muted or blocked, are left out.",
                    Modifier.fillMaxWidth(), TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Fg.colors.ink,
    modifier = Modifier.padding(top = 4.dp))

@Composable
private fun PersonCard(ui: FreegramUi, person: FoundPersonUi) {
    val c = Fg.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar(person.label, person.hue, 38.dp)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(person.label, color = c.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("· ${person.tag}", color = c.ink3, fontSize = 13.sp)
            }
            person.latest?.let { Text(it, color = c.ink2, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (person.following) Text("Following", color = c.ink3, fontSize = 13.sp)
        else Text("Follow", color = c.teal, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { ui.follow(person.key) }.padding(8.dp))
    }
}
