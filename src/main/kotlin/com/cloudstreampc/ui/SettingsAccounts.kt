package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.Backup
import com.cloudstreampc.core.IptvAccount
import com.cloudstreampc.core.IptvType
import com.cloudstreampc.core.Profile
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.Updates
import com.cloudstreampc.plugins.PluginManager
import com.cloudstreampc.providers.BuiltinProviders
import com.cloudstreampc.providers.IptvProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun UpdateSettings() {
    val s = Store.settings
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<Updates.Release?>(null) }
    var logs by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(false) }

    SettingsCategory("Mises à jour de l'application")
    SettingsItem("Vérifier les mises à jour", "Version installée : $APP_VERSION") {
        scope.launch {
            val release = runCatching { withContext(Dispatchers.IO) { Updates.check(APP_VERSION) } }.getOrElse {
                nav.toast("Impossible de vérifier : ${it.message}"); return@launch
            }
            if (release == null) nav.toast("CloudStreamPC est à jour") else update = release
        }
    }
    SettingsSwitch("Afficher les mises à jour de l'application", "Vérifie au démarrage", s.checkAppUpdates) { v ->
        Store.updateSettings { it.copy(checkAppUpdates = v) }
    }

    SettingsCategory("Sauvegarde")
    SettingsItem("Sauvegarder les données", "Paramètres, profils, favoris, historique, comptes" +
        if (s.lastBackup > 0) " · dernière : ${DateFormat.getDateTimeInstance().format(Date(s.lastBackup))}" else "") {
        scope.launch {
            val file = runCatching { withContext(Dispatchers.IO) { Backup.create() } }.getOrElse {
                nav.toast("Échec : ${it.message}"); return@launch
            }
            nav.toast("Sauvegardé dans ${file.path}")
        }
    }
    SettingsChoice("Fréquence de sauvegarde", listOf(0 to "Désactivée", 1 to "Tous les jours", 7 to "Toutes les semaines"), s.backupFrequencyDays) { v ->
        Store.updateSettings { it.copy(backupFrequencyDays = v) }
    }
    SettingsItem("Restaurer une sauvegarde", "Remplace les données actuelles") {
        val chooser = JFileChooser(Backup.folder).apply { fileFilter = FileNameExtensionFilter("Sauvegarde CloudStreamPC (.zip)", "zip") }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        Backup.restore(chooser.selectedFile)
                        PluginManager.restoreMissing()
                        BuiltinProviders.sync()
                    }
                }.onSuccess { nav.toast("Sauvegarde restaurée") }.onFailure { nav.toast("Échec : ${it.message}") }
            }
        }
    }
    SettingsItem("Dossier des sauvegardes", Backup.folder.path) {
        val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            Store.updateSettings { it.copy(backupDir = chooser.selectedFile.absolutePath) }
        }
    }

    SettingsCategory("Extensions")
    SettingsSwitch("Mises à jour automatiques des extensions", null, s.autoUpdatePlugins) { v -> Store.updateSettings { it.copy(autoUpdatePlugins = v) } }
    SettingsItem("Mettre à jour les extensions") {
        scope.launch {
            val count = PluginManager.updateAll()
            nav.toast(if (count == 0) "Tout est à jour" else "$count extension(s) mise(s) à jour")
        }
    }

    SettingsCategory("Actions")
    SettingsItem("Afficher les journaux", "Utile pour signaler un problème") { logs = true }
    SettingsItem("Signaler un problème", "Ouvre un signalement GitHub avec le système et la fin du journal (sans mots de passe ni clés)") { report = true }
    SettingsItem("Refaire la configuration initiale") { Store.updateSettings { it.copy(firstLaunchDone = false) } }

    update?.let { release -> UpdateDialog(release) { update = null } }
    if (logs) LogViewerDialog { logs = false }
    if (report) ReportDialog { report = false }
}

@Composable
fun AccountSettings() {
    val s = Store.settings
    val nav = LocalNavigator.current
    var pinDialog by remember { mutableStateOf(false) }
    SettingsCategory("Comptes")
    SettingsItem("Profils", s.profiles.joinToString(", ") { it.name }) { nav.push(Route.Settings(SettingsPage.Profiles)) }
    SettingsSwitch("Choisir le profil au démarrage", "Affiche « Qui regarde ? » quand il y a plusieurs profils", s.askProfileAtStartup) { v ->
        Store.updateSettings { it.copy(askProfileAtStartup = v) }
    }
    SettingsItem(
        "Comptes IPTV",
        if (s.iptvAccounts.isEmpty()) "Ajouter un abonnement Xtream Codes ou une playlist M3U" else s.iptvAccounts.joinToString(", ") { it.name },
    ) { nav.push(Route.Settings(SettingsPage.Iptv)) }
    SettingsItem(
        "Suivi et synchronisation",
        com.cloudstreampc.sync.Trackers.connected().joinToString(", ") { it.name }.ifBlank { "AniList, MyAnimeList, Simkl, Trakt, synchronisation entre PC" } +
            if (s.syncDir != null) " · dossier partagé" else "",
    ) { nav.push(Route.Settings(SettingsPage.Tracking)) }
    SettingsItem(
        "Addons Stremio",
        if (s.stremioAddons.isEmpty()) "Catalogues, flux et sous-titres des addons Stremio" else "${s.stremioAddons.size} addon(s)",
    ) { nav.push(Route.Settings(SettingsPage.Extensions)) }

    SettingsCategory("Sécurité")
    SettingsItem(
        "Verrouiller l'application",
        if (s.appPin != null) "Code demandé au démarrage" else "Désactivé",
    ) { pinDialog = true }
    if (pinDialog) {
        PinSetupDialog(
            current = s.appPin,
            onDismiss = { pinDialog = false },
            onSave = { pin -> Store.updateSettings { it.copy(appPin = pin) }; pinDialog = false },
        )
    }
}

@Composable
private fun PinSetupDialog(current: String?, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Code de verrouillage") },
        text = {
            OutlinedTextField(
                pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("Nouveau code (4 à 8 chiffres)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = { TextButton(enabled = pin.length >= 4, onClick = { onSave(pin) }) { Text("Enregistrer") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Désactiver") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}

private val avatarColors = listOf(0xFF3D50FA, 0xFFE91E63, 0xFF4CAF50, 0xFFFF9800, 0xFF7B61FF, 0xFF00BCD4, 0xFFF44336, 0xFF9E9E9E)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileSettings() {
    val s = Store.settings
    val nav = LocalNavigator.current
    var editing by remember { mutableStateOf<Profile?>(null) }
    var unlocking by remember { mutableStateOf<Profile?>(null) }
    SettingsCategory("Profils")
    Text(
        "Chaque profil a ses propres favoris, historique et progression, comme les comptes de l'application Android.",
        color = CsColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(8.dp)) {
        s.profiles.forEach { profile ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(130.dp)) {
                Box(
                    Modifier.clip(CircleShape)
                        .border(3.dp, if (profile.id == s.currentProfile) Color.White else Color.Transparent, CircleShape)
                        .clickable {
                            if (profile.id == s.currentProfile) return@clickable
                            if (profile.pin != null) unlocking = profile else {
                                Store.switchProfile(profile.id); nav.toast("Profil : ${profile.name}")
                            }
                        }
                ) { Avatar(profile, 96) }
                Text(profile.name, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    IconButton(onClick = { editing = profile }) { Icon(Icons.Default.Edit, "Modifier", tint = CsColors.TextMuted) }
                    if (profile.id != "default" && profile.id != s.currentProfile) {
                        IconButton(onClick = {
                            Store.updateSettings { st -> st.copy(profiles = st.profiles - profile) }
                            com.cloudstreampc.core.AppDirs.libraryFile(profile.id).delete()
                        }) { Icon(Icons.Default.Delete, "Supprimer", tint = CsColors.TextMuted) }
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(130.dp)) {
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(CsColors.SurfaceHigh)
                    .clickable { editing = Profile(id = UUID.randomUUID().toString().take(8), name = "", color = avatarColors.random()) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Add, "Ajouter", modifier = Modifier.size(40.dp)) }
            Text("Ajouter", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
    editing?.let { profile ->
        ProfileEditDialog(profile, onDismiss = { editing = null }) { updated ->
            Store.updateSettings { st ->
                val exists = st.profiles.any { it.id == updated.id }
                st.copy(profiles = if (exists) st.profiles.map { if (it.id == updated.id) updated else it } else st.profiles + updated)
            }
            editing = null
        }
    }
    unlocking?.let { profile ->
        PinCheckDialog(profile.pin!!, "Code du profil ${profile.name}", onDismiss = { unlocking = null }) {
            Store.switchProfile(profile.id)
            unlocking = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditDialog(profile: Profile, onDismiss: () -> Unit, onSave: (Profile) -> Unit) {
    var name by remember { mutableStateOf(profile.name) }
    var color by remember { mutableStateOf(profile.color) }
    var pin by remember { mutableStateOf(profile.pin.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile.name.isEmpty()) "Nouveau profil" else "Modifier le profil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(24) }, label = { Text("Nom") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    avatarColors.forEach { c ->
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(Color(c))
                                .border(3.dp, if (c == color) Color.White else Color.Transparent, CircleShape)
                                .clickable { color = c }
                        )
                    }
                }
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("Code du profil (facultatif)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(profile.copy(name = name.trim(), color = color, pin = pin.ifBlank { null })) }) {
                Text("Enregistrer")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun PinCheckDialog(expected: String, title: String, onDismiss: (() -> Unit)?, onSuccess: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                pin,
                {
                    pin = it.filter(Char::isDigit).take(8)
                    wrong = false
                    if (pin == expected) onSuccess()
                },
                label = { Text("Code") }, singleLine = true, isError = wrong,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { if (wrong) Text("Code incorrect") },
            )
        },
        confirmButton = { TextButton(onClick = { if (pin == expected) onSuccess() else wrong = true }) { Text("Valider") } },
        dismissButton = { if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun IptvSettings() {
    val s = Store.settings
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<IptvAccount?>(null) }
    var syncing by remember { mutableStateOf(false) }

    fun save(accounts: List<IptvAccount>) {
        Store.updateSettings { it.copy(iptvAccounts = accounts) }
        syncing = true
        scope.launch {
            BuiltinProviders.sync()
            HomeState.loadedFor = null
            syncing = false
        }
    }

    SettingsCategory("Comptes IPTV")
    Text(
        "Ajoutez un abonnement Xtream Codes (adresse du serveur, identifiant, mot de passe) ou une playlist M3U. " +
            "Chaque compte apparaît comme un fournisseur avec ses chaînes TV, films et séries, triés par catégorie.",
        color = CsColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
    )
    s.iptvAccounts.forEach { account ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(CsColors.Surface).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Tv, null, tint = CsColors.Primary, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(account.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    (if (account.type == IptvType.XTREAM) "Xtream Codes · ${account.username} · " else "Playlist M3U · ") + account.url,
                    color = CsColors.TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(account.enabled, { v -> save(s.iptvAccounts.map { if (it.id == account.id) it.copy(enabled = v) else it }) })
            IconButton(onClick = { editing = account }) { Icon(Icons.Default.Edit, "Modifier") }
            IconButton(onClick = { save(s.iptvAccounts - account) }) { Icon(Icons.Default.Delete, "Supprimer") }
        }
    }
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { editing = IptvAccount(id = UUID.randomUUID().toString().take(8)) }) {
            Icon(Icons.Default.Add, null)
            Text(" Ajouter un compte IPTV")
        }
        if (syncing) {
            Spacer(Modifier.width(16.dp))
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("  Chargement des catalogues…", color = CsColors.TextMuted)
        }
    }
    editing?.let { account ->
        IptvAccountDialog(account, onDismiss = { editing = null }) { saved ->
            val exists = s.iptvAccounts.any { it.id == saved.id }
            save(if (exists) s.iptvAccounts.map { if (it.id == saved.id) saved else it } else s.iptvAccounts + saved)
            editing = null
            nav.toast("Compte « ${saved.name} » enregistré")
        }
    }
}

@Composable
private fun IptvAccountDialog(account: IptvAccount, onDismiss: () -> Unit, onSave: (IptvAccount) -> Unit) {
    var type by remember { mutableStateOf(account.type) }
    var name by remember { mutableStateOf(account.name) }
    var url by remember { mutableStateOf(account.url) }
    var user by remember { mutableStateOf(account.username) }
    var pass by remember { mutableStateOf(account.password) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun current() = account.copy(
        type = type,
        name = name.trim().ifBlank { if (type == IptvType.XTREAM) "Xtream" else "M3U" },
        url = url.trim().let { if (it.isNotEmpty() && !it.startsWith("http")) "http://$it" else it },
        username = user.trim(),
        password = pass,
    )
    val valid = url.isNotBlank() && (type == IptvType.M3U || (user.isNotBlank() && pass.isNotBlank()))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (account.url.isEmpty()) "Ajouter un compte IPTV" else "Modifier le compte IPTV") },
        text = {
            Column(Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(type == IptvType.XTREAM, { type = IptvType.XTREAM }, { Text("Xtream Codes") })
                    FilterChip(type == IptvType.M3U, { type = IptvType.M3U }, { Text("Playlist M3U") })
                }
                OutlinedTextField(name, { name = it }, label = { Text("Nom du compte") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it },
                    label = { Text(if (type == IptvType.XTREAM) "Adresse du serveur (http://serveur:port)" else "Lien de la playlist M3U") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (type == IptvType.XTREAM) {
                    OutlinedTextField(user, { user = it }, label = { Text("Nom d'utilisateur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        pass, { pass = it }, label = { Text("Mot de passe") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = valid && !testing, onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = runCatching { withContext(Dispatchers.IO) { IptvProvider.test(current()) } }
                                .fold({ "✓ $it" }, { "✗ ${it.message ?: it.javaClass.simpleName}" })
                            testing = false
                        }
                    }) { Text("Tester la connexion") }
                    if (testing) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                testResult?.let {
                    Text(it, color = if (it.startsWith("✓")) CsColors.Good else CsColors.Error, fontSize = 14.sp)
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(current()) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

