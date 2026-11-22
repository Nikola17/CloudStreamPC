package com.moncloudi.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moncloudi.core.Profile
import com.moncloudi.core.Store
import com.moncloudi.core.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Code lock, then "Qui regarde ?", then the app (with first-launch setup and update notice). */
@Composable
fun StartupGate(nav: Navigator) {
    var unlocked by remember { mutableStateOf(Store.settings.appPin == null) }
    var profileChosen by remember { mutableStateOf(!Store.settings.askProfileAtStartup || Store.settings.profiles.size < 2) }
    // The first-launch setup and the update notice sit outside App(): they need the navigator too.
    CloudStreamTheme { androidx.compose.runtime.CompositionLocalProvider(LocalNavigator provides nav) {
        when {
            !unlocked -> Box(Modifier.fillMaxSize().background(CsColors.Background)) {
                PinCheckDialog(Store.settings.appPin!!, "MonCloudi est verrouillé", onDismiss = null) { unlocked = true }
            }
            !profileChosen -> WhoIsWatching { profileChosen = true }
            else -> {
                App(nav)
                if (!Store.settings.firstLaunchDone) SetupDialog()
                UpdateNotice()
            }
        }
    } }
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
                        if (com.moncloudi.core.Parental.codeFor(profile) != null) unlocking = profile else {
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
        PinCheckDialog(com.moncloudi.core.Parental.codeFor(profile)!!, "Code du profil ${profile.name}", onDismiss = { unlocking = null }) {
            Store.switchProfile(profile.id)
            onDone()
        }
    }
}

/**
 * Clean first-launch welcome: no pre-installed or pre-added repositories/addons,
 * just like the official CloudStream app. Users add their own CloudStream, Stremio, or IPTV addons.
 */
@Composable
private fun SetupDialog() {
    val nav = LocalNavigator.current
    fun finish() = Store.updateSettings { it.copy(firstLaunchDone = true) }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Bienvenue dans MonCloudi", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(540.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "MonCloudi démarre vierge, sans aucun addon préinstallé. Ajoutez vos propres sources dans le centre Addons unifié :",
                    color = CsColors.TextMuted,
                )
                WelcomeStep(
                    "Extensions CloudStream",
                    "Ajoutez vos dépôts (code court, repo.json ou cloudstreamrepo://) comme sur CloudStream Android",
                )
                WelcomeStep(
                    "Addons Stremio",
                    "Ajoutez vos liens manifest.json pour vos catalogues, flux et sous-titres",
                )
                WelcomeStep(
                    "Comptes IPTV",
                    "Connectez votre abonnement Xtream Codes ou playlist M3U (chaînes TV, films et séries)",
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                finish()
                nav.select(Tab.Addons)
            }) { Text("Configurer mes addons") }
        },
        dismissButton = {
            OutlinedButton(onClick = { finish() }) { Text("Plus tard") }
        },
    )
}

@Composable
private fun WelcomeStep(title: String, subtitle: String) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(CsColors.SurfaceHigh).padding(14.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(subtitle, color = CsColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
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
    release?.let { r -> UpdateDialog(r) { release = null } }
}
