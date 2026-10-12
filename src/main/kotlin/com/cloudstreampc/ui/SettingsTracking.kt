package com.cloudstreampc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.TrackerAccount
import com.cloudstreampc.sync.FolderSync
import com.cloudstreampc.sync.LoginUi
import com.cloudstreampc.sync.OAuthLoopback
import com.cloudstreampc.sync.Tracker
import com.cloudstreampc.sync.Trackers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private fun copy(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

/** Tracking services and the sync folder (Comptes et Sécurité → Suivi et synchronisation). */
@Composable
fun TrackingSettings() {
    val s = Store.settings
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Tracker?>(null) }

    SettingsCategory("Suivi de ce que vous regardez")
    Text(
        "Les épisodes et films vus jusqu'au bout sont envoyés aux comptes connectés. AniList et MyAnimeList " +
            "ne concernent que les animés ; Simkl et Trakt suivent tout.",
        color = CsColors.TextMuted, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp),
    )
    Trackers.all.forEach { tracker ->
        val account = s.trackers[tracker.id]
        val subtitle = when {
            account?.accessToken != null -> "Connecté" + (account.userName?.let { " : $it" } ?: "")
            else -> "Non connecté" + if (tracker.animeOnly) " · animés" else " · films, séries et animés"
        }
        SettingsItem(tracker.name, subtitle) { editing = tracker }
    }

    SettingsCategory("Synchronisation entre PC")
    SettingsItem(
        "Dossier partagé",
        s.syncDir ?: "Choisissez un dossier OneDrive, Google Drive, Dropbox… présent sur vos PC",
    ) {
        chooseFolder("Dossier de synchronisation")?.let { dir ->
            Store.updateSettings { it.copy(syncDir = dir) }
            scope.launch { nav.toast(withContext(Dispatchers.IO) { runCatching { FolderSync.syncNow() }.getOrElse { it.message ?: "Erreur" } }) }
        }
    }
    if (s.syncDir != null) {
        SettingsItem("Synchroniser maintenant", "Favoris, historique, progression et goûts du profil actuel") {
            scope.launch { nav.toast(withContext(Dispatchers.IO) { runCatching { FolderSync.syncNow() }.getOrElse { it.message ?: "Erreur" } }) }
        }
        SettingsItem("Arrêter la synchronisation") { Store.updateSettings { it.copy(syncDir = null) } }
    }

    editing?.let { tracker -> TrackerDialog(tracker) { editing = null } }
}

@Composable
private fun TrackerDialog(tracker: Tracker, onDismiss: () -> Unit) {
    val saved = Store.settings.trackers[tracker.id] ?: TrackerAccount()
    var clientId by remember { mutableStateOf(saved.clientId) }
    var clientSecret by remember { mutableStateOf(saved.clientSecret) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<Pair<String, String>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val connected = saved.accessToken != null

    fun login() {
        status = "Connexion en cours… Autorisez CloudStreamPC dans le navigateur."
        error = false
        code = null
        job = scope.launch {
            val ui = object : LoginUi {
                override fun openBrowser(url: String) = openInBrowser(url)
                override fun showCode(code: String, url: String) {
                    copy(code)
                    openInBrowser(url)
                    scope.launch { codeShown(code, url) }
                }

                private fun codeShown(value: String, url: String) {
                    code = value to url
                    status = "Saisissez ce code sur la page ouverte (il est déjà copié), puis validez."
                }
            }
            val result = runCatching {
                withContext(Dispatchers.IO) { tracker.login(TrackerAccount(clientId.trim(), clientSecret.trim()), ui) }
            }
            result.onSuccess {
                Trackers.save(tracker, it)
                status = "Connecté" + (it.userName?.let { name -> " en tant que $name" } ?: "")
                code = null
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) return@onFailure
                status = it.message ?: it.javaClass.simpleName
                error = true
            }
            job = null
        }
    }

    AlertDialog(
        onDismissRequest = { job?.cancel(); onDismiss() },
        title = { Text(tracker.name) },
        text = {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (connected) {
                    Text("Connecté" + (saved.userName?.let { " en tant que $it" } ?: "") + ".", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Les épisodes vus jusqu'au bout sont envoyés automatiquement. La liste de ce compte apparaît dans la Bibliothèque.",
                        color = CsColors.TextMuted, fontSize = 13.sp,
                    )
                } else {
                    Text(
                        "${tracker.name} demande que chaque application soit déclarée par son utilisateur. C'est gratuit et ne se fait qu'une fois :",
                        fontSize = 14.sp,
                    )
                    tracker.setupSteps.forEachIndexed { i, step -> Text("${i + 1}. $step", fontSize = 13.sp, color = CsColors.TextMuted) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { openInBrowser(tracker.developerUrl) }) { Text("Ouvrir la page de ${tracker.name}") }
                        if (tracker.setupSteps.any { OAuthLoopback.REDIRECT_URL in it }) {
                            TextButton(onClick = { copy(OAuthLoopback.REDIRECT_URL) }) { Text("Copier l'adresse de redirection") }
                        }
                    }
                    OutlinedTextField(clientId, { clientId = it }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (tracker.needsSecret) {
                        OutlinedTextField(
                            clientSecret, { clientSecret = it }, label = { Text("Client Secret") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                code?.let { (value, url) ->
                    SelectionContainer { Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = CsColors.Primary) }
                    TextButton(onClick = { openInBrowser(url) }) { Text(url) }
                }
                status?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (job != null) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(it, color = if (error) CsColors.Error else CsColors.TextMuted, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            when {
                connected -> TextButton(onClick = { Trackers.disconnect(tracker); onDismiss() }) { Text("Se déconnecter") }
                job != null -> TextButton(onClick = { job?.cancel(); job = null; status = null }) { Text("Annuler la connexion") }
                else -> Button(
                    enabled = clientId.isNotBlank() && (!tracker.needsSecret || clientSecret.isNotBlank()),
                    onClick = ::login,
                ) { Text("Se connecter") }
            }
        },
        dismissButton = { TextButton(onClick = { job?.cancel(); onDismiss() }) { Text("Fermer") } },
    )
}
