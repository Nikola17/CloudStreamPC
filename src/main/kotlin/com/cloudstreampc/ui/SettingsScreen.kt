package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.Profile
import com.cloudstreampc.core.Store

const val APP_VERSION = "2.7.0"

/** Main settings page, laid out like the Android app (profile, then categories). */
@Composable
fun SettingsScreen() {
    val nav = LocalNavigator.current
    val profile = Store.profile
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp)) {
        Spacer(Modifier.height(120.dp))
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable { nav.push(Route.Settings(SettingsPage.Profiles)) }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(profile, 72)
            Text(profile.name, fontSize = 26.sp, modifier = Modifier.padding(start = 18.dp))
        }
        Spacer(Modifier.height(28.dp))
        listOf(
            SettingsPage.General, SettingsPage.Player, SettingsPage.Providers, SettingsPage.Layout,
            SettingsPage.Updates, SettingsPage.Accounts, SettingsPage.Extensions,
        ).forEach { page ->
            CategoryRow(page.title) { nav.push(Route.Settings(page)) }
        }
        Text(
            "$APP_VERSION  •  CloudStream library 4846fba  •  Windows",
            color = Color(0xFFDDDDDD), fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun CategoryRow(title: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsActiveAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (hovered) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction).clickable(onClick = onClick)
            .padding(vertical = 20.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 24.sp, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White, modifier = Modifier.size(32.dp))
    }
}

@Composable
fun Avatar(profile: Profile, sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(Color(profile.color)),
        contentAlignment = Alignment.Center,
    ) {
        Text(profile.name.take(1).uppercase(), fontSize = (sizeDp * 0.42).sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/** A settings sub-page with its back button. */
@Composable
fun SettingsPageScreen(page: SettingsPage) {
    val nav = LocalNavigator.current
    when (page) {
        SettingsPage.Extensions -> ExtensionsScreen(onBack = { nav.back() })
        else -> Column(Modifier.fillMaxSize()) {
            ScreenHeader(page.title, onBack = { nav.back() })
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp).padding(bottom = 48.dp)) {
                when (page) {
                    SettingsPage.General -> GeneralSettings()
                    SettingsPage.Player -> PlayerSettings()
                    SettingsPage.Providers -> ProviderSettings()
                    SettingsPage.Layout -> LayoutSettings()
                    SettingsPage.Updates -> UpdateSettings()
                    SettingsPage.Accounts -> AccountSettings()
                    SettingsPage.Iptv -> IptvSettings()
                    SettingsPage.Profiles -> ProfileSettings()
                    SettingsPage.Tracking -> TrackingSettings()
                    SettingsPage.Extensions -> Unit
                }
            }
        }
    }
}

// ----- Building blocks shared by the settings pages -----

@Composable
fun SettingsCategory(title: String) {
    Text(
        title, color = CsColors.Primary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp, start = 8.dp),
    )
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsActiveAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (hovered && onClick != null) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp)
            if (subtitle != null) Text(subtitle, color = CsColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) Box(Modifier.padding(start = 16.dp)) { trailing() }
    }
}

@Composable
fun SettingsSwitch(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsItem(title, subtitle, trailing = { Switch(checked, onChange) }) { onChange(!checked) }
}

/** An item that opens a single-choice dialog. */
@Composable
fun <T> SettingsChoice(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsItem(title, options.firstOrNull { it.first == selected }?.second ?: "—") { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(options) { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSelect(value); open = false }.padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(value == selected, onClick = { onSelect(value); open = false })
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Annuler") } },
        )
    }
}

/** An item that opens a multiple-choice dialog; an empty selection means "all". */
@Composable
fun SettingsMultiChoice(title: String, options: List<Pair<String, String>>, selected: Set<String>, allLabel: String = "Tous", onChange: (Set<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsItem(
        title,
        if (selected.isEmpty()) allLabel else options.filter { it.first in selected }.joinToString(", ") { it.second },
    ) { open = true }
    if (open) {
        var current by remember { mutableStateOf(selected) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(options) { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable { current = if (value in current) current - value else current + value }.padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(value in current, onCheckedChange = { current = if (it) current + value else current - value })
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onChange(current); open = false }) { Text("Valider") } },
            dismissButton = { TextButton(onClick = { onChange(emptySet()); open = false }) { Text(allLabel) } },
        )
    }
}

/** An item that opens a text field dialog. */
@Composable
fun SettingsText(title: String, value: String, placeholder: String = "", subtitle: String? = null, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsItem(title, subtitle ?: value.ifBlank { placeholder }) { open = true }
    if (open) {
        var text by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(placeholder) }, modifier = Modifier.width(460.dp)) },
            confirmButton = { TextButton(onClick = { onChange(text.trim()); open = false }) { Text("Valider") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Annuler") } },
        )
    }
}

@Composable
fun InfoDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}
