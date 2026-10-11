package com.cloudstreampc

import com.cloudstreampc.plugins.RepositoryManager
import kotlin.test.Test
import kotlin.test.assertEquals

class RepositoryManagerTest {
    @Test
    fun normalizesRepositoryLinks() {
        val url = "https://raw.githubusercontent.com/user/repo/main/repo.json"
        assertEquals(url, RepositoryManager.normalizeRepoUrl("  $url "))
        assertEquals(url, RepositoryManager.normalizeRepoUrl("cloudstreamrepo://raw.githubusercontent.com/user/repo/main/repo.json"))
        assertEquals(url, RepositoryManager.normalizeRepoUrl("https://cs.repo/?$url"))
        assertEquals(url, RepositoryManager.normalizeRepoUrl("raw.githubusercontent.com/user/repo/main/repo.json"))
    }
}
