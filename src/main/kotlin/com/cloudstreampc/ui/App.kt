package com.cloudstreampc.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun Tab.icon() = when (this) {
    Tab.Home -> Icons.Default.Home
    Tab.Search -> Icons.Default.Search
    Tab.Library -> AppIcons.VideoLibrary
    Tab.Extensions -> AppIcons.Extension
    Tab.Settings -> Icons.Default.Settings
}

@Composable
fun rememberNavigator(): Navigator {
    val scope = rememberCoroutineScope()
    return remember { Navigator(scope) }
}

@Composable
fun App(nav: Navigator) {
    val tabs = rememberSaveableStateHolder()
    CloudStreamTheme {
        CompositionLocalProvider(LocalNavigator provides nav) {
            Surface(Modifier.fillMaxSize(), color = CsColors.Background) {
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(Modifier.fillMaxHeight(), containerColor = CsColors.Surface) {
                        Text(
                            "CloudStream", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            color = CsColors.Primary, modifier = Modifier.padding(vertical = 12.dp),
                        )
                        Tab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = nav.tab == tab && nav.stack.isEmpty(),
                                onClick = { nav.select(tab) },
                                icon = { Icon(tab.icon(), tab.label) },
                                label = { Text(tab.label, fontSize = 11.sp) },
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        // The current tab stays composed under opened pages to keep its scroll position.
                        tabs.SaveableStateProvider(nav.tab) {
                            when (nav.tab) {
                                Tab.Home -> HomeScreen()
                                Tab.Search -> SearchScreen()
                                Tab.Library -> LibraryScreen()
                                Tab.Extensions -> ExtensionsScreen()
                                Tab.Settings -> SettingsScreen()
                            }
                        }
                        nav.stack.lastOrNull()?.let { route ->
                            Surface(Modifier.fillMaxSize(), color = CsColors.Background) {
                                when (route) {
                                    is Route.Details -> DetailsScreen(route)
                                    is Route.Category -> CategoryScreen(route)
                                }
                            }
                        }
                        PlaybackOverlays(Modifier.align(Alignment.BottomStart))
                        SnackbarHost(nav.snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                    }
                }
            }
        }
    }
}
