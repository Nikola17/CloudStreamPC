package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.InstalledPlugin
import com.cloudstreampc.core.IptvType
import com.cloudstreampc.core.RepositoryEntry
import com.cloudstreampc.core.Store
import com.cloudstreampc.plugins.PluginManager
import com.cloudstreampc.plugins.PluginState
import com.cloudstreampc.plugins.RepositoryManager
import com.cloudstreampc.plugins.SitePlugin
import com.cloudstreampc.providers.BuiltinProviders
import kotlinx.coroutines.launch

private sealed interface RepoContent {
    data object Loading : RepoContent
    data class Loaded(val plugins: List<SitePlugin>) : RepoContent
    data class Failed(val message: String) : RepoContent
}

private enum class AddonTab(val label: String) {
    All("Tous mes addons"),
    CloudStream("CloudStream"),
    Stremio("Stremio"),
    Iptv("IPTV"),
}

/** Cached repository listings, refreshed on demand. */
private val repoContents = mutableStateMapOf<String, RepoContent>()

/**
 * Unified Addons Hub: CloudStream extensions, Stremio addons, and IPTV accounts
 * synchronized in one single place with clear badges and status.
 */
@Composable
fun ExtensionsScreen(onBack: (() -> Unit)? = null) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val settings = Store.settings
    var newRepo by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    var activeTab by remember { mutableStateOf(AddonTab.All) }

    fun refresh(repo: RepositoryEntry) {
        repoContents[repo.url] = RepoContent.Loading
        scope.launch {
            repoContents[repo.url] = try {
                RepoContent.Loaded(RepositoryManager.fetchPlugins(repo.url))
            } catch (t: Throwable) {
                RepoContent.Failed(t.message ?: t.toString())
            }
        }
    }

    fun install(plugin: SitePlugin, repoUrl: String) {
        busy[plugin.internalName] = true
        scope.launch {
            try {
                PluginManager.install(plugin, repoUrl)
                val state = PluginManager.states[plugin.internalName]
                nav.toast(if (state is PluginState.Failed) "${plugin.name} : échec du chargement" else "${plugin.name} installée")
                HomeState.loadedFor = null
            } catch (t: Throwable) {
                nav.toast("Échec de l'installation de ${plugin.name} : ${t.message}")
            } finally {
                busy.remove(plugin.internalName)
            }
        }
    }

    fun uninstall(internalName: String) {
        busy[internalName] = true
        scope.launch {
            PluginManager.uninstall(internalName)
            busy.remove(internalName)
            HomeState.loadedFor = null
        }
    }

    LaunchedEffect(settings.repositories) {
        settings.repositories.filter { it.url !in repoContents }.forEach { refresh(it) }
    }

    val totalCount = settings.installedPlugins.size + settings.stremioAddons.size + settings.iptvAccounts.size

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            ScreenHeader("Addons", onBack = onBack) {
                TextButton(onClick = {
                    scope.launch {
                        val count = PluginManager.updateAll()
                        BuiltinProviders.sync()
                        HomeState.loadedFor = null
                        nav.toast(if (count == 0) "Addons synchronisés et à jour" else "$count extension(s) mise(s) à jour")
                        settings.repositories.forEach { refresh(it) }
                    }
                }) { Text("Tout synchroniser") }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = activeTab == AddonTab.All,
                    onClick = { activeTab = AddonTab.All },
                    label = { Text("${AddonTab.All.label} ($totalCount)") },
                )
                FilterChip(
                    selected = activeTab == AddonTab.CloudStream,
                    onClick = { activeTab = AddonTab.CloudStream },
                    label = { Text("CloudStream (${settings.installedPlugins.size})") },
                )
                FilterChip(
                    selected = activeTab == AddonTab.Stremio,
                    onClick = { activeTab = AddonTab.Stremio },
                    label = { Text("Stremio (${settings.stremioAddons.size})") },
                )
                FilterChip(
                    selected = activeTab == AddonTab.Iptv,
                    onClick = { activeTab = AddonTab.Iptv },
                    label = { Text("IPTV (${settings.iptvAccounts.size})") },
                )
            }
        }

        when (activeTab) {
            AddonTab.All -> {
                item {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Text(
                            "Tous vos addons CloudStream, Stremio et IPTV sont synchronisés au même endroit pour l'accueil, la recherche et la lecture.",
                            color = CsColors.TextMuted, fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(onClick = { activeTab = AddonTab.CloudStream }) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Dépôt CloudStream")
                            }
                            FilledTonalButton(onClick = { activeTab = AddonTab.Stremio }) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Addon Stremio")
                            }
                            FilledTonalButton(onClick = { activeTab = AddonTab.Iptv }) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Compte IPTV")
                            }
                        }
                    }
                }
                if (totalCount == 0) {
                    item {
                        MessageBox(
                            "Aucun addon installé",
                            "Ajoutez vos dépôts CloudStream, vos addons Stremio ou vos comptes IPTV (TV, films et séries VOD) à l'aide des boutons ci-dessus.",
                            Modifier.fillMaxWidth().padding(top = 24.dp),
                        )
                    }
                } else {
                    if (settings.iptvAccounts.isNotEmpty()) {
                        items(settings.iptvAccounts, key = { "all-iptv:${it.id}" }) { account ->
                            UnifiedAddonCard(
                                title = account.name,
                                subtitle = (if (account.type == IptvType.XTREAM) "Xtream Codes · TV, Films & Séries · " else "Playlist M3U · ") + account.url,
                                badge = "IPTV",
                                badgeColor = Color(0xFF009688),
                                statusText = if (account.enabled) "Actif et synchronisé" else "Désactivé",
                                statusOk = account.enabled,
                                trailing = {
                                    Switch(account.enabled, { v ->
                                        Store.updateSettings { s ->
                                            s.copy(iptvAccounts = s.iptvAccounts.map { if (it.id == account.id) it.copy(enabled = v) else it })
                                        }
                                        scope.launch { BuiltinProviders.sync(); HomeState.loadedFor = null }
                                    })
                                    OutlinedButton(onClick = { activeTab = AddonTab.Iptv }) { Text("Gérer") }
                                },
                            )
                        }
                    }
                    if (settings.stremioAddons.isNotEmpty()) {
                        items(settings.stremioAddons, key = { "all-stremio:${it.manifestUrl}" }) { entry ->
                            UnifiedAddonCard(
                                title = entry.name.ifBlank { entry.manifestUrl },
                                subtitle = entry.manifestUrl,
                                badge = "Stremio",
                                badgeColor = Color(0xFF7B61FF),
                                statusText = if (entry.enabled) "Actif et synchronisé" else "Désactivé",
                                statusOk = entry.enabled,
                                trailing = {
                                    Switch(entry.enabled, { v ->
                                        Store.updateSettings { s ->
                                            s.copy(stremioAddons = s.stremioAddons.map { if (it == entry) it.copy(enabled = v) else it })
                                        }
                                        scope.launch { BuiltinProviders.sync(); HomeState.loadedFor = null }
                                    })
                                    IconButton(onClick = {
                                        Store.updateSettings { s -> s.copy(stremioAddons = s.stremioAddons - entry) }
                                        scope.launch { BuiltinProviders.sync(); HomeState.loadedFor = null }
                                    }) { Icon(Icons.Default.Delete, "Supprimer") }
                                },
                            )
                        }
                    }
                    if (settings.installedPlugins.isNotEmpty()) {
                        items(settings.installedPlugins, key = { "all-cs:${it.internalName}" }) { plugin ->
                            val state = PluginManager.states[plugin.internalName]
                            UnifiedAddonCard(
                                title = plugin.name,
                                subtitle = plugin.description?.takeIf { it.isNotBlank() } ?: "Extension CloudStream v${plugin.version}",
                                badge = "CloudStream",
                                badgeColor = CsColors.Primary,
                                statusText = when (state) {
                                    is PluginState.Failed -> "Erreur : ${state.reason}"
                                    PluginState.Loaded -> "Actif (v${plugin.version})"
                                    null -> "Installé (v${plugin.version})"
                                },
                                statusOk = state !is PluginState.Failed,
                                trailing = {
                                    if (busy[plugin.internalName] == true) {
                                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    } else {
                                        OutlinedButton(onClick = { uninstall(plugin.internalName) }) { Text("Désinstaller") }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            AddonTab.Stremio -> {
                item { StremioAddonsSection() }
            }

            AddonTab.Iptv -> {
                item {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        IptvSettings()
                    }
                }
            }

            AddonTab.CloudStream -> {
                item {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Text("Ajouter un dépôt CloudStream", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Collez l'adresse d'un dépôt (repo.json ou lien cloudstreamrepo://), comme dans l'application CloudStream Android.",
                            color = CsColors.TextMuted, fontSize = 13.sp,
                        )
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                newRepo, { newRepo = it }, singleLine = true, modifier = Modifier.weight(1f),
                                placeholder = { Text("https://…/repo.json ou cloudstreamrepo://…") },
                            )
                            Spacer(Modifier.width(12.dp))
                            Button(enabled = newRepo.isNotBlank() && !adding, onClick = {
                                adding = true
                                scope.launch {
                                    try {
                                        val url = RepositoryManager.normalizeRepoUrl(newRepo)
                                        val repo = RepositoryManager.fetchRepository(url)
                                        if (settings.repositories.none { it.url == url }) {
                                            Store.updateSettings { it.copy(repositories = it.repositories + RepositoryEntry(repo.name, url)) }
                                        }
                                        newRepo = ""
                                        nav.toast("Dépôt « ${repo.name} » ajouté")
                                    } catch (t: Throwable) {
                                        nav.toast("Dépôt invalide : ${t.message}")
                                    } finally {
                                        adding = false
                                    }
                                }
                            }) { Text(if (adding) "Ajout…" else "Ajouter") }
                        }
                    }
                }

                if (settings.repositories.isEmpty()) {
                    item { MessageBox("Aucun dépôt CloudStream", "Ajoutez un dépôt ci-dessus pour installer vos extensions.", Modifier.fillMaxWidth()) }
                }

                settings.repositories.forEach { repo ->
                    item(key = "repo:" + repo.url) {
                        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 28.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(repo.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(repo.url, color = CsColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val content = repoContents[repo.url]
                            if (content is RepoContent.Loaded) {
                                val missing = content.plugins.filter { p -> settings.installedPlugins.none { it.internalName == p.internalName } }
                                if (missing.isNotEmpty()) {
                                    OutlinedButton(onClick = { missing.forEach { install(it, repo.url) } }) { Text("Tout installer") }
                                }
                            }
                            IconButton(onClick = { refresh(repo) }) { Icon(Icons.Default.Refresh, "Actualiser") }
                            IconButton(onClick = {
                                Store.updateSettings { s -> s.copy(repositories = s.repositories - repo) }
                                repoContents.remove(repo.url)
                            }) { Icon(Icons.Default.Delete, "Supprimer le dépôt") }
                        }
                    }
                    when (val content = repoContents[repo.url]) {
                        null, RepoContent.Loading -> item(key = "loading:" + repo.url) {
                            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("  Chargement du dépôt…", color = CsColors.TextMuted)
                            }
                        }
                        is RepoContent.Failed -> item(key = "failed:" + repo.url) {
                            Text("Impossible de lire le dépôt : ${content.message}", color = CsColors.Error, modifier = Modifier.padding(horizontal = 24.dp))
                        }
                        is RepoContent.Loaded -> items(content.plugins, key = { repo.url + "/" + it.internalName }) { plugin ->
                            val installed = settings.installedPlugins.firstOrNull { it.internalName == plugin.internalName }
                            PluginRow(
                                name = plugin.name,
                                description = plugin.description,
                                details = listOfNotNull(
                                    plugin.language?.uppercase(),
                                    "v${plugin.version}",
                                    plugin.authors.takeIf { it.isNotEmpty() }?.joinToString(),
                                    plugin.tvTypes?.joinToString(),
                                ).joinToString(" · "),
                                installed = installed,
                                updateAvailable = installed != null && plugin.version > installed.version,
                                busy = busy[plugin.internalName] == true,
                                onInstall = { install(plugin, repo.url) },
                                onUninstall = { uninstall(plugin.internalName) },
                            )
                        }
                    }
                }

                // Installed extensions whose repository is no longer listed.
                val orphans = settings.installedPlugins.filter { p ->
                    repoContents.values.none { it is RepoContent.Loaded && it.plugins.any { s -> s.internalName == p.internalName } } &&
                        settings.repositories.none { it.url == p.repositoryUrl }
                }
                if (orphans.isNotEmpty()) {
                    item { Text("Autres extensions installées", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, top = 28.dp, bottom = 6.dp)) }
                    items(orphans, key = { "orphan/" + it.internalName }) { plugin ->
                        PluginRow(
                            plugin.name, plugin.description, "v${plugin.version}", plugin, false,
                            busy[plugin.internalName] == true, onInstall = {}, onUninstall = { uninstall(plugin.internalName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedAddonCard(
    title: String,
    subtitle: String,
    badge: String,
    badgeColor: Color,
    statusText: String,
    statusOk: Boolean,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CsColors.Surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(badgeColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (badge == "IPTV") AppIcons.Tv else AppIcons.Extension, null, tint = badgeColor)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    badge,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(badgeColor).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Text(subtitle, fontSize = 12.sp, color = CsColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(statusText, fontSize = 12.sp, color = if (statusOk) CsColors.Good else CsColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            trailing()
        }
    }
}

@Composable
private fun PluginRow(
    name: String,
    description: String?,
    details: String,
    installed: InstalledPlugin?,
    updateAvailable: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val state = installed?.let { PluginManager.states[it.internalName] }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CsColors.Surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(CsColors.SurfaceHigh), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Extension, null, tint = if (installed != null) CsColors.Primary else CsColors.TextMuted)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(name, fontWeight = FontWeight.SemiBold)
            description?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = CsColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(details, fontSize = 12.sp, color = CsColors.TextMuted)
            // Weekly check: a site of this extension no longer answers.
            val broken = installed?.let { PluginManager.providersOf(it.internalName) }.orEmpty()
                .mapNotNull { api -> com.cloudstreampc.plugins.ExtensionHealth.results[api.name]?.takeIf { !it.ok }?.let { api.name to it } }
            broken.forEach { (site, result) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        "En panne",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(CsColors.Error).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    Text(
                        "  $site : ${result.detail ?: ""}",
                        fontSize = 12.sp, color = CsColors.Error, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when (state) {
                is PluginState.Failed -> Text("Erreur de chargement : ${state.reason}", fontSize = 12.sp, color = CsColors.Error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                PluginState.Loaded -> Text("Installée et active (v${installed.version})", fontSize = 12.sp, color = CsColors.Good)
                null -> {}
            }
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            installed == null -> Button(onClick = onInstall) { Text("Installer") }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (updateAvailable) FilledTonalButton(onClick = onInstall) { Text("Mettre à jour") }
                OutlinedButton(onClick = onUninstall) { Text("Désinstaller") }
            }
        }
    }
}
