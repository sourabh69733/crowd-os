package org.freegram.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.freegram.shared.model.FreegramUi
import org.freegram.shared.model.PlatformActions
import org.freegram.shared.model.SettingsPage

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", FgIcons.Home), Nearby("Nearby", FgIcons.Nearby), Compose("Post", FgIcons.Plus),
    Profile("Profile", FgIcons.Profile), Settings("Settings", FgIcons.Settings)
}

/** The whole app: bottom tabs, post detail over them, and short messages at the bottom. */
@Composable
fun FreegramApp(ui: FreegramUi, platform: PlatformActions, settingsPages: List<SettingsPage>) {
    FreegramTheme {
        val c = Fg.colors
        var tab by rememberSaveable { mutableStateOf(Tab.Home) }
        val snackbar = remember { SnackbarHostState() }
        // Back from another tab returns to Home before leaving the app.
        @OptIn(ExperimentalComposeUiApi::class)
        BackHandler(enabled = ui.selected == null && tab != Tab.Home && tab != Tab.Compose) { tab = Tab.Home }
        LaunchedEffect(ui.toast) {
            ui.toast?.let { snackbar.showSnackbar(it); ui.dismissToast() }
        }
        Scaffold(
            containerColor = c.paper,
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (ui.selected == null && tab != Tab.Compose) {
                    FloatingActionButton(onClick = { tab = Tab.Compose }, containerColor = c.teal, contentColor = c.onTeal,
                        shape = RoundedCornerShape(18.dp)) { Icon(FgIcons.Plus, contentDescription = "New post", modifier = Modifier.size(26.dp)) }
                }
            },
            bottomBar = {
                if (ui.selected == null) NavigationBar(containerColor = c.surface) {
                    listOf(Tab.Home, Tab.Nearby, Tab.Profile, Tab.Settings).forEach { t ->
                        NavigationBarItem(
                            selected = tab == t, onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = c.teal, selectedTextColor = c.teal,
                                indicatorColor = c.tealSoft, unselectedIconColor = c.ink3, unselectedTextColor = c.ink3),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().background(c.paper).padding(padding)) {
                val selected = ui.selected
                when {
                    selected != null -> PostDetailScreen(ui, selected)
                    tab == Tab.Home -> HomeScreen(ui)
                    tab == Tab.Nearby -> NearbyScreen(ui, platform)
                    tab == Tab.Compose -> ComposeScreen(ui, platform, onDone = { tab = Tab.Home })
                    tab == Tab.Profile -> ProfileScreen(ui, platform, settingsPages)
                    tab == Tab.Settings -> SettingsScreen(settingsPages)
                }
            }
        }
    }
}
