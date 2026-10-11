package com.cloudstreampc.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun Tab.icon(selected: Boolean): ImageVector = when (this) {
    Tab.Home -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
    Tab.Search -> if (selected) Icons.Filled.Search else Icons.Outlined.Search
    Tab.Library -> if (selected) AppIcons.Folder else AppIcons.FolderOpen
    Tab.Downloads -> if (selected) AppIcons.Download else AppIcons.DownloadOutlined
    Tab.Settings -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
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
                    Sidebar(nav)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        // The current tab stays composed under opened pages to keep its scroll position.
                        tabs.SaveableStateProvider(nav.tab) {
                            when (nav.tab) {
                                Tab.Home -> HomeScreen()
                                Tab.Search -> SearchScreen()
                                Tab.Library -> LibraryScreen()
                                Tab.Downloads -> DownloadsScreen()
                                Tab.Settings -> SettingsScreen()
                            }
                        }
                        nav.stack.lastOrNull()?.let { route ->
                            Surface(Modifier.fillMaxSize(), color = CsColors.Background) {
                                when (route) {
                                    is Route.Details -> DetailsScreen(route)
                                    is Route.Category -> CategoryScreen(route)
                                    is Route.Settings -> SettingsPageScreen(route.page)
                                    Route.TvGuide -> TvGuideScreen()
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

/** CloudStream's TV-style side bar: icons only, vertically centered. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Sidebar(nav: Navigator) {
    Column(
        Modifier.width(88.dp).fillMaxHeight().background(CsColors.Sidebar),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Tab.entries.forEach { tab ->
            val selected = nav.tab == tab
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            TooltipArea(
                tooltip = {
                    Text(
                        tab.label, fontSize = 13.sp, color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(CsColors.SurfaceHigh)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                },
                delayMillis = 400,
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                selected -> CsColors.Primary.copy(alpha = 0.18f)
                                hovered -> Color.White.copy(alpha = 0.08f)
                                else -> Color.Transparent
                            }
                        )
                        .hoverable(interaction)
                        .clickable(interaction, indication = null) { nav.select(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        tab.icon(selected), tab.label,
                        tint = if (selected) CsColors.Primary else Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                    // New episodes of followed series.
                    val count = if (tab == Tab.Library) com.cloudstreampc.core.Store.library.newEpisodes.size else 0
                    if (count > 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(20.dp)
                                .clip(CircleShape).background(CsColors.Error),
                            contentAlignment = Alignment.Center,
                        ) { Text(if (count > 9) "9+" else "$count", fontSize = 11.sp, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
