package com.cloudstreampc.plugins

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.cloudstreampc.core.AppDirs
import com.cloudstreampc.core.InstalledPlugin
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.mapper
import com.googlecode.d2j.dex.Dex2jar
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.extractorApis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.zip.ZipFile

sealed interface PluginState {
    data object Loaded : PluginState
    data class Failed(val reason: String) : PluginState
}

/**
 * Installs and loads CloudStream extensions (.cs3).
 *
 * A .cs3 is a zip holding `manifest.json` and Android `classes.dex`. On the desktop the
 * DEX is translated once into JVM classes, then loaded with a [URLClassLoader] whose
 * parent is the app's class loader (which provides the CloudStream library).
 */
object PluginManager {
    private val mutex = Mutex()
    private val loaded = mutableMapOf<String, LoadedPlugin>()

    /** internalName -> state, observable by the UI. */
    val states = mutableStateMapOf<String, PluginState>()

    /** Bumped whenever providers are added or removed, so the UI can refresh. */
    var revision by mutableIntStateOf(0)
        private set

    /** All loaded providers; reading it in a composable subscribes to changes. */
    val providers: List<MainAPI>
        get() {
            revision
            return APIHolder.allProviders.toList().sortedBy { it.name.lowercase() }
        }

    private class LoadedPlugin(val instance: BasePlugin, val loader: URLClassLoader, val file: File)

    private fun cs3File(internalName: String) = File(AppDirs.plugins, "${safeName(internalName)}.cs3")
    private fun classesDir(internalName: String) = File(AppDirs.plugins, "${safeName(internalName)}.classes")
    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    suspend fun loadInstalled() = withContext(Dispatchers.IO) {
        for (plugin in Store.settings.installedPlugins) {
            mutex.withLock { loadLocked(plugin) }
        }
    }

    suspend fun install(plugin: SitePlugin, repositoryUrl: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            unloadLocked(plugin.internalName)
            val bytes = app.get(plugin.url, cacheTime = 0).body.bytes()
            val file = cs3File(plugin.internalName)
            file.writeBytes(bytes)
            classesDir(plugin.internalName).deleteRecursively()
            val entry = InstalledPlugin(
                internalName = plugin.internalName,
                name = plugin.name,
                version = plugin.version,
                url = plugin.url,
                repositoryUrl = repositoryUrl,
                description = plugin.description,
                language = plugin.language,
                iconUrl = plugin.iconUrl,
                authors = plugin.authors,
            )
            Store.updateSettings { s ->
                s.copy(installedPlugins = s.installedPlugins.filterNot { it.internalName == plugin.internalName } + entry)
            }
            loadLocked(entry)
        }
    }

    suspend fun uninstall(internalName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            unloadLocked(internalName)
            cs3File(internalName).delete()
            classesDir(internalName).deleteRecursively()
            states.remove(internalName)
            Store.updateSettings { s ->
                s.copy(installedPlugins = s.installedPlugins.filterNot { it.internalName == internalName })
            }
        }
    }

    /** Reinstalls every installed extension whose repository offers a newer version. */
    suspend fun updateAll(): Int {
        var updated = 0
        val byRepo = Store.settings.installedPlugins.groupBy { it.repositoryUrl }
        for ((repoUrl, installed) in byRepo) {
            if (repoUrl == null) continue
            val available = runCatching { RepositoryManager.fetchPlugins(repoUrl) }.getOrElse {
                Logger.error("Mise à jour impossible pour $repoUrl", it)
                continue
            }
            for (plugin in installed) {
                val remote = available.firstOrNull { it.internalName == plugin.internalName } ?: continue
                if (remote.version > plugin.version) {
                    Logger.info("Mise à jour de ${plugin.name} ${plugin.version} -> ${remote.version}")
                    runCatching { install(remote, repoUrl) }.onSuccess { updated++ }
                        .onFailure { Logger.error("Échec de la mise à jour de ${plugin.name}", it) }
                }
            }
        }
        return updated
    }

    private fun loadLocked(plugin: InstalledPlugin) {
        val name = plugin.internalName
        val file = cs3File(name)
        try {
            if (!file.exists()) {
                states[name] = PluginState.Failed("Fichier manquant, réinstallez l'extension")
                return
            }
            val dir = ensureConverted(file, classesDir(name))
            val manifest = ZipFile(file).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: error("manifest.json absent")
                zip.getInputStream(entry).use { mapper.readValue(it, BasePlugin.Manifest::class.java) }
            }
            val loader = URLClassLoader(arrayOf(dir.toURI().toURL()), PluginManager::class.java.classLoader)
            val instance = loader.loadClass(manifest.pluginClassName)
                .getDeclaredConstructor().newInstance() as BasePlugin
            instance.filename = file.absolutePath
            loaded[name] = LoadedPlugin(instance, loader, file)
            if (instance is Plugin) instance.load(Context()) else instance.load()
            states[name] = PluginState.Loaded
            revision++
            Logger.info("Extension chargée : ${manifest.name} v${manifest.version}")
        } catch (t: Throwable) {
            Logger.error("Impossible de charger l'extension $name", t)
            states[name] = PluginState.Failed(t.toString())
            revision++
        }
    }

    private fun unloadLocked(internalName: String) {
        val plugin = loaded.remove(internalName) ?: return
        runCatching { plugin.instance.beforeUnload() }
        val path = plugin.file.absolutePath
        val providers = APIHolder.allProviders.filter { it.sourcePlugin == path }
        providers.forEach { APIHolder.removePluginMapping(it) }
        APIHolder.allProviders.removeAll(providers.toSet())
        extractorApis.removeAll(extractorApis.filter { it.sourcePlugin == path }.toSet())
        runCatching { plugin.loader.close() }
        revision++
    }

    /** Translates classes.dex into a directory of .class files, cached by file hash. */
    private fun ensureConverted(cs3: File, dir: File): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(cs3.readBytes()).joinToString("") { "%02x".format(it) }
        val marker = File(dir, ".source-hash")
        if (marker.exists() && marker.readText() == hash) return dir

        dir.deleteRecursively()
        dir.mkdirs()
        ZipFile(cs3).use { zip ->
            val dexEntries = zip.entries().toList().filter { it.name.matches(Regex("classes\\d*\\.dex")) }
            require(dexEntries.isNotEmpty()) { "Aucun classes.dex dans ${cs3.name}" }
            for (entry in dexEntries) {
                val dex = zip.getInputStream(entry).use { it.readBytes() }
                Dex2jar.from(dex)
                    .reUseReg(false)
                    .topoLogicalSort()
                    .skipDebug(false)
                    .optimizeSynchronized(true)
                    .skipExceptions(false)
                    // Kotlin uses names like `constructor-impl` that must survive the translation.
                    .dontSanitizeNames(true)
                    .to(dir.toPath())
            }
            // Keep non-code resources (manifest, assets) reachable through the class loader.
            zip.entries().toList()
                .filter { !it.isDirectory && !it.name.endsWith(".dex") }
                .forEach { entry ->
                    val out = File(dir, entry.name)
                    out.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
        }
        marker.writeText(hash)
        return dir
    }
}
