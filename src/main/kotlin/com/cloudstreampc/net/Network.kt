package com.cloudstreampc.net

import com.cloudstreampc.core.Logger
import com.lagradost.cloudstream3.app
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/** DNS over HTTPS, to get around ISP blocking (same choices as the Android app). */
object Network {
    data class DohProvider(val id: String, val label: String, val url: String, val bootstrap: List<String>)

    val providers = listOf(
        DohProvider("none", "Désactivé (DNS du système)", "", emptyList()),
        DohProvider("cloudflare", "Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
        DohProvider("google", "Google", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
        DohProvider("adguard", "AdGuard", "https://dns-unfiltered.adguard.com/dns-query", listOf("94.140.14.140", "94.140.14.141")),
        DohProvider("quad9", "Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
        DohProvider("dnssb", "DNS.SB", "https://doh.dns.sb/dns-query", listOf("185.222.222.222", "45.11.45.11")),
    )

    private var originalClient: OkHttpClient? = null

    /** Rebuilds the HTTP client shared by the extensions with the chosen DNS. */
    @Synchronized
    fun apply(providerId: String) {
        val original = originalClient ?: app.baseClient.also { originalClient = it }
        val provider = providers.firstOrNull { it.id == providerId }
        if (provider == null || provider.id == "none") {
            app.baseClient = original
            return
        }
        try {
            val dns = DnsOverHttps.Builder()
                .client(original)
                .url(provider.url.toHttpUrl())
                .bootstrapDnsHosts(provider.bootstrap.map { InetAddress.getByName(it) })
                .includeIPv6(false)
                .build()
            app.baseClient = original.newBuilder().dns(dns).build()
            Logger.info("DNS over HTTPS : ${provider.label}")
        } catch (t: Throwable) {
            Logger.error("DNS over HTTPS indisponible", t)
            app.baseClient = original
        }
    }
}
