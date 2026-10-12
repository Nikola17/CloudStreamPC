package com.cloudstreampc

import com.cloudstreampc.core.IptvAccount
import com.cloudstreampc.core.mapper
import com.cloudstreampc.providers.IptvProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IptvLiveTest {
    @Test
    fun readsTheSubscriptionDetails() {
        val info = IptvProvider.parseAccountInfo(
            mapper.readTree(
                """{"user_info":{"auth":1,"status":"Active","exp_date":"1799586189","max_connections":"2","active_cons":"1",
                   "allowed_output_formats":["m3u8","ts","rtmp"]},"server_info":{"timezone":"Europe/Paris"}}""",
            ),
        )!!
        assertEquals(2, info.maxConnections)
        assertEquals(1, info.activeConnections)
        assertEquals(1799586189000L, info.expiresAt)
        assertEquals(listOf("m3u8", "ts", "rtmp"), info.formats)
        assertEquals("Europe/Paris", info.timezone)
        // No end date ("null" or 0): unlimited.
        assertEquals(null, IptvProvider.parseAccountInfo(mapper.readTree("""{"user_info":{"exp_date":null}}"""))!!.expiresAt)
    }

    @Test
    fun recognizesAChannelAcrossProviders() {
        val key = IptvProvider.channelKey("TF1")
        listOf("FR| TF1 HD", "TF1 FHD", "fr: TF1 (backup)", "TF1 [1080p]", "tf1").forEach { assertEquals(key, IptvProvider.channelKey(it), it) }
        assertEquals(IptvProvider.channelKey("France 2"), IptvProvider.channelKey("FR - FRANCE 2 HD"))
        assertEquals(IptvProvider.channelKey("Canal+ Sport"), IptvProvider.channelKey("CANAL+ SPORT HEVC"))
        assertNotEquals(IptvProvider.channelKey("France 2"), IptvProvider.channelKey("France 3"))
        assertEquals("teleloisirs", IptvProvider.channelKey("Télé Loisirs"))
    }

    @Test
    fun buildsStreamAddressesSafely() {
        val provider = IptvProvider(IptvAccount(id = "a", url = "http://srv:8080", username = "jean dupont", password = "p@ss/word"))
        // Credentials with spaces or slashes stay inside their path segment.
        val url = provider.catchupUrl("42", 1_700_000_000_000L, 45)
        assertEquals("http://srv:8080/timeshift/jean%20dupont/p%40ss%2Fword/45/", url.substringBeforeLast("/").substringBeforeLast("/") + "/")
        assert(url.endsWith("/42.ts"))
    }
}
