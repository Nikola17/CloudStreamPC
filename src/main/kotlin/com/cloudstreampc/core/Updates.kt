package com.cloudstreampc.core

import com.lagradost.cloudstream3.app

object Updates {
    data class Release(val version: String, val url: String, val notes: String?)

    /** Latest CloudStreamPC release on GitHub, if newer than [current]. */
    suspend fun check(current: String): Release? {
        val node = mapper.readTree(app.get("https://api.github.com/repos/Nikola17/CloudStreamPC/releases/latest", cacheTime = 0).text)
        val tag = node.get("tag_name")?.asText()?.removePrefix("v") ?: return null
        return if (isNewer(tag, current)) Release(tag, node.get("html_url")?.asText().orEmpty(), node.get("body")?.asText()) else null
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
