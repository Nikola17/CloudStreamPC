package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.Profile
import com.cloudstreampc.core.RepositoryEntry
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.StremioAddonEntry
import com.cloudstreampc.core.Updates
import com.cloudstreampc.plugins.PluginManager
import com.cloudstreampc.plugins.RepositoryManager
import com.cloudstreampc.providers.BuiltinProviders
import com.cloudstreampc.providers.StremioProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Repository proposed on first launch; it can be removed in Extensions. */
val defaultRepository = RepositoryEntry(
    "CloudStream FR",
    "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/repo.json",
)

/** Code lock, then "Qui regarde ?", then the app (with first-launch setup and update notice). */
@Composable
fun StartupGate(nav: Navigator) {
    var unlocked by remember { mutableStateOf(Store.settings.appPin == null) }
    var profileChosen by remember { mutableStateOf(!Store.settings.askProfileAtStartup || Store.settings.profiles.size < 2) }
    CloudStreamTheme {
        when {
            !unlocked -> Box(Modifier.fillMaxSize().background(CsColors.Background)) {
                PinCheckDialog(Store.settings.appPin!!, "CloudStreamPC est verrouillé", onDismiss = null) { unlocked = true }
            }
            !profileChosen -> WhoIsWatching { profileChosen = true }
            else -> {
                App(nav)
                if (!Store.settings.firstLaunchDone) SetupDialog()
                UpdateNotice()
            }
        }
    }
}

@Composable
private fun WhoIsWatching(onDone: () -> Unit) {
    var unlocking by remember { mutableStateOf<Profile?>(null) }
    Column(
        Modifier.fillMaxSize().background(CsColors.Background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Qui regarde ?", fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Store.settings.profiles.forEach { profile ->
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        if (profile.pin != null) unlocking = profile else {
                            Store.switchProfile(profile.id); onDone()
                        }
                    }.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(profile, 120)
                    Text(profile.name, fontSize = 20.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
    unlocking?.let { profile ->
        PinCheckDialog(profile.pin!!, "Code du profil ${profile.name}", onDismiss = { unlocking = null }) {
            Store.switchProfile(profile.id)
            onDone()
        }
    }
}

/** First launch (or "Refaire la configuration initiale"), like the app's setup screens. */
@Composable
private fun SetupDialog() {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(false) }
    var stremioAdded by remember { mutableStateOf(Store.settings.stremioAddons.isNotEmpty()) }
    fun finish() = Store.updateSettings { it.copy(firstLaunchDone = true) }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Bienvenue dans CloudStreamPC", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Choisissez vos sources de contenu. Tout reste modifiable dans les paramètres.", color = CsColors.TextMuted)
                SetupStep(
                    "Extensions CloudStream FR",
                    "French-Stream, Movix, French-Manga, FSTV",
                    done = installed, busy = installing,
                ) {
                    installing = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                if (Store.settings.repositories.none { it.url == defaultRepository.url }) {
                                    Store.updateSettings { it.copy(repositories = it.repositories + defaultRepository) }
                                }
                                RepositoryManager.fetchPlugins(defaultRepository.url).forEach { PluginManager.install(it, defaultRepository.url) }
                            }
                        }.onFailure { nav.toast("Installation impossible : ${it.message}") }
                        installing = false
                        installed = true
                        HomeState.loadedFor = null
                    }
                }
                SetupStep("Compte IPTV", "Xtream Codes ou playlist M3U : TV en direct, films et séries", done = Store.settings.iptvAccounts.isNotEmpty()) {
                    finish()
                    nav.select(Tab.Settings)
                    nav.push(Route.Settings(SettingsPage.Iptv))
                }
                SetupStep("Catalogue Stremio (Cinemeta)", "Films et séries populaires, fiches détaillées", done = stremioAdded) {
                    val url = StremioProvider.presets.first().second
                    if (Store.settings.stremioAddons.none { it.manifestUrl == url }) {
                        Store.updateSettings { it.copy(stremioAddons = it.stremioAddons + StremioAddonEntry(url, "Cinemeta")) }
                        scope.launch { BuiltinProviders.sync() }
                    }
                    stremioAdded = true
                }
            }
        },
        confirmButton = { Button(onClick = { finish() }, enabled = !installing) { Text("Terminer") } },
    )
}

@Composable
private fun SetupStep(title: String, subtitle: String, done: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(CsColors.SurfaceHigh).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(subtitle, color = CsColors.TextMuted, fontSize = 13.sp)
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            done -> Icon(Icons.Default.Check, "Fait", tint = CsColors.Good)
            else -> OutlinedButton(onClick = onClick) { Text("Ajouter") }
        }
    }
}

@Composable
private fun UpdateNotice() {
    var release by remember { mutableStateOf<Updates.Release?>(null) }
    LaunchedEffect(Unit) {
        if (Store.settings.checkAppUpdates) {
            release = runCatching { withContext(Dispatchers.IO) { Updates.check(APP_VERSION) } }.getOrNull()
        }
    }
    release?.let { r ->
        AlertDialog(
            onDismissRequest = { release = null },
            title = { Text("CloudStreamPC ${r.version} est disponible") },
            text = { Text(r.notes?.take(600) ?: "Une nouvelle version est disponible.") },
            confirmButton = { Button(onClick = { openInBrowser(r.url); release = null }) { Text("Télécharger") } },
            dismissButton = { TextButton(onClick = { release = null }) { Text("Plus tard") } },
        )
    }
}
