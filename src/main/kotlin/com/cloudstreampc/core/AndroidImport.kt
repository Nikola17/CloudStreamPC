package com.cloudstreampc.core

import com.fasterxml.jackson.databind.JsonNode
import java.io.File

/**
 * Brings a CloudStream Android backup (Paramètres → Mises à jour et sauvegarde → Sauvegarde, a
 * .json file) into the current profile: library lists (En cours, À voir…), favorites, the titles
 * being watched, and the extension repositories. Positions inside episodes can't be carried over
 * (the phone identifies episodes by internal numbers).
 */
object AndroidImport {
    data class Imported(
        val statuses: List<StatusEntry>,
        val favorites: List<SavedItem>,
        val history: List<HistoryEntry>,
        val repositories: List<RepositoryEntry>,
    ) {
        val isEmpty get() = statuses.isEmpty() && favorites.isEmpty() && history.isEmpty() && repositories.isEmpty()
        fun summary() = listOfNotNull(
            statuses.size.takeIf { it > 0 }?.let { "$it titre(s) de bibliothèque" },
            favorites.size.takeIf { it > 0 }?.let { "$it favori(s)" },
            history.size.takeIf { it > 0 }?.let { "$it en cours de visionnage" },
            repositories.size.takeIf { it > 0 }?.let { "$it dépôt(s)" },
        ).joinToString(", ")
    }

    /** CloudStream's WatchType ids. */
    private val statusIds = mapOf(
        0 to WatchStatus.WATCHING,
        1 to WatchStatus.COMPLETED,
        2 to WatchStatus.ON_HOLD,
        3 to WatchStatus.DROPPED,
        4 to WatchStatus.PLAN_TO_WATCH,
    )

    fun read(file: File): Imported = parse(file.readText())

    fun parse(json: String): Imported {
        val root = mapper.readTree(json)
        val store = root.path("datastore")
        require(store.isObject) { "Ce fichier n'est pas une sauvegarde CloudStream" }
        // Every value is kept as JSON text, under "<account>/<folder>/<id>" or "<folder>/<id>".
        val values = mutableMapOf<String, JsonNode>()
        listOf("_String", "_Int", "_Long", "_Bool").forEach { kind ->
            store.path(kind).fields().forEach { (key, value) ->
                values[key] = if (value.isTextual) runCatching { mapper.readTree(value.asText()) }.getOrDefault(value) else value
            }
        }
        fun folder(key: String) = key.split("/").let { if (it.size >= 3) it[it.size - 2] else if (it.size == 2) it[0] else "" }
        fun id(key: String) = key.substringAfterLast('/')

        fun item(node: JsonNode) = SavedItem(
            name = node.path("name").asText(),
            url = node.path("url").asText(),
            apiName = node.path("apiName").asText(),
            posterUrl = node.path("posterUrl").asText().ifBlank { node.path("poster").asText() }.ifBlank { null },
            type = node.path("type").asText().ifBlank { null },
            year = node.path("year").asInt(0).takeIf { it > 0 },
        )

        val watchState = values.filterKeys { folder(it) == "result_watch_state" }.mapKeys { id(it.key) }
        val statuses = values.filterKeys { folder(it) == "result_watch_state_data" }.mapNotNull { (key, node) ->
            val status = statusIds[watchState[id(key)]?.asInt(-1)] ?: return@mapNotNull null
            val saved = item(node).takeIf { it.url.isNotBlank() && it.apiName.isNotBlank() } ?: return@mapNotNull null
            StatusEntry(saved, status, node.path("bookmarkedTime").asLong(node.path("latestUpdatedTime").asLong(0)))
        }.distinctBy { it.item.apiName + it.item.url }

        val favorites = values.filterKeys { folder(it) == "result_favorites_state_data" }.values
            .sortedByDescending { it.path("favoritesTime").asLong(0) }
            .map(::item).filter { it.url.isNotBlank() && it.apiName.isNotBlank() }
            .distinctBy { it.apiName + it.url }

        val headers = values.filterKeys { folder(it) == "download_header_cache" }.mapKeys { id(it.key) }
        val history = values.filterKeys { folder(it) == "result_resume_watching_2" || folder(it) == "result_resume_watching" }.values
            .mapNotNull { resume ->
                val header = headers[resume.path("parentId").asText()] ?: return@mapNotNull null
                val saved = item(header).takeIf { it.url.isNotBlank() && it.apiName.isNotBlank() } ?: return@mapNotNull null
                val episode = resume.path("episode").asInt(0).takeIf { it > 0 }
                val season = resume.path("season").asInt(0).takeIf { it > 0 }
                val label = episode?.let { if (season != null) "S$season·E$it" else "Épisode $it" }
                HistoryEntry(saved, label, null, resume.path("updateTime").asLong(0))
            }
            .sortedByDescending { it.timestamp }
            .distinctBy { it.item.apiName + it.item.url }

        val repositories = values["REPOSITORIES_KEY"]?.takeIf { it.isArray }?.mapNotNull { repo ->
            val url = repo.path("url").asText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RepositoryEntry(repo.path("name").asText().ifBlank { url }, url)
        }.orEmpty()

        return Imported(statuses, favorites, history, repositories)
    }

    /** Adds what was read to the current profile (nothing already there is replaced). */
    fun apply(imported: Imported) {
        Store.updateLibrary { lib ->
            val now = System.currentTimeMillis()
            val statuses = lib.statuses + imported.statuses
                .filter { "${it.item.apiName}|${it.item.url}" !in lib.statuses }
                .associateBy { "${it.item.apiName}|${it.item.url}" }
                .mapValues { (_, entry) -> entry.copy(updatedAt = entry.updatedAt.takeIf { it > 0 } ?: now) }
            val newFavorites = imported.favorites.filter { f -> lib.favorites.none { it.apiName == f.apiName && it.url == f.url } }
            val newHistory = imported.history.filter { h -> lib.history.none { it.item.apiName == h.item.apiName && it.item.url == h.item.url } }
            lib.copy(
                statuses = statuses,
                favorites = lib.favorites + newFavorites,
                favoriteAdded = lib.favoriteAdded + newFavorites.associate { "${it.apiName}|${it.url}" to now },
                history = (lib.history + newHistory).sortedByDescending { it.timestamp }.take(500),
            )
        }
        val repos = imported.repositories.filter { r -> Store.settings.repositories.none { it.url == r.url } }
        if (repos.isNotEmpty()) Store.updateSettings { it.copy(repositories = it.repositories + repos) }
        Logger.info("Sauvegarde Android importée : ${imported.summary()}")
    }
}
