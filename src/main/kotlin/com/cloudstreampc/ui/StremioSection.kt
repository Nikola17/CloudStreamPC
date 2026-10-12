package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.StremioAddonEntry
import com.cloudstreampc.providers.BuiltinProviders
import com.cloudstreampc.providers.StremioAddon
import com.cloudstreampc.providers.StremioProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stremio addons, added by manifest URL like in Stremio. */
@Composable
fun StremioAddonsSection() {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val addons = Store.settings.stremioAddons
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun save(list: List<StremioAddonEntry>) {
        Store.updateSettings { it.copy(stremioAddons = list) }
        busy = true
        scope.launch {
            BuiltinProviders.sync()
            HomeState.loadedFor = null
            busy = false
        }
    }

    fun add(manifestUrl: String) {
        busy = true
        scope.launch {
            try {
                val addon = withContext(Dispatchers.IO) { StremioAddon.fetch(manifestUrl) }
                if (addons.none { it.manifestUrl == addon.manifestUrl }) save(addons + StremioAddonEntry(addon.manifestUrl, addon.name))
                url = ""
                nav.toast("Addon « ${addon.name} » ajouté")
            } catch (t: Throwable) {
                nav.toast("Addon invalide : ${t.message}")
                busy = false
            }
        }
    }

    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(
            "Les addons Stremio ajoutent des catalogues (accueil, recherche), des fiches et des flux. Collez le lien du " +
                "manifest (…/manifest.json ou stremio://…). Les flux torrent passent par le moteur torrent intégré.",
            color = CsColors.TextMuted, fontSize = 13.sp,
        )
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(url, { url = it }, singleLine = true, placeholder = { Text("https://…/manifest.json") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Button(enabled = url.isNotBlank() && !busy, onClick = { add(url) }) { Text("Ajouter") }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StremioProvider.presets.filter { (_, preset) -> addons.none { it.manifestUrl == preset } }.forEach { (label, preset) ->
                OutlinedButton(enabled = !busy, onClick = { add(preset) }) { Text("+ $label") }
            }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(1.dp).padding(top = 12.dp))
        addons.forEach { entry ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(CsColors.Surface).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Extension, null, tint = CsColors.Primary)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(entry.name.ifBlank { entry.manifestUrl }, fontWeight = FontWeight.SemiBold)
                    Text(entry.manifestUrl, color = CsColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(entry.enabled, { v -> save(addons.map { if (it == entry) it.copy(enabled = v) else it }) })
                IconButton(onClick = { save(addons - entry) }) { Icon(Icons.Default.Delete, "Supprimer") }
            }
        }
    }
}
