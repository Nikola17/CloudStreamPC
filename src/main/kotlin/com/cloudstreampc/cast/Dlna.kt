package com.cloudstreampc.cast

import com.cloudstreampc.core.Logger
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI

/** A DLNA / UPnP TV or media player ("MediaRenderer") on the local network. */
data class DlnaDevice(val name: String, val location: String, val controlUrl: String) {
    val host: String get() = URI(location).host
}

/** Finds DLNA renderers (SSDP) and drives them with the AVTransport service (SOAP). */
object Dlna {
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Sends an SSDP search and reads the answers for [timeoutMs]. */
    suspend fun discover(timeoutMs: Int = 3000): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val search = (
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
            ).toByteArray()
        val locations = linkedSetOf<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 500
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) { socket.send(DatagramPacket(search, search.size, group, 1900)) }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    String(packet.data, 0, packet.length).lines()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.let(locations::add)
                } catch (_: SocketTimeoutException) {
                    // Keep listening until the deadline.
                }
            }
        }
        locations.mapNotNull { location -> runCatching { describe(location) }.onFailure { Logger.info("DLNA $location : ${it.message}") }.getOrNull() }
    }

    /** Reads the device description: its name and the AVTransport control address. */
    suspend fun describe(location: String): DlnaDevice? {
        val xml = app.get(location, timeout = 5).text
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val name = doc.selectFirst("device > friendlyName")?.text() ?: return null
        val service = doc.select("service").firstOrNull { it.selectFirst("serviceType")?.text() == AV_TRANSPORT } ?: return null
        val control = service.selectFirst("controlURL")?.text() ?: return null
        val base = doc.selectFirst("URLBase")?.text()?.takeIf { it.isNotBlank() } ?: location
        return DlnaDevice(name, location, URI(base).resolve(control).toString())
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private suspend fun soap(device: DlnaDevice, action: String, arguments: String) = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body><u:$action xmlns:u="$AV_TRANSPORT"><InstanceID>0</InstanceID>$arguments</u:$action></s:Body></s:Envelope>"""
        val request = Request.Builder().url(device.controlUrl)
            .header("SOAPACTION", "\"$AV_TRANSPORT#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        app.baseClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("$action refusé par ${device.name} (${response.code})")
        }
    }

    suspend fun play(device: DlnaDevice, url: String, title: String, contentType: String) {
        val metadata = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="0" parentID="-1" restricted="1"><dc:title>${escape(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class>""" +
            """<res protocolInfo="http-get:*:$contentType:*">${escape(url)}</res></item></DIDL-Lite>"""
        runCatching { soap(device, "Stop", "") }
        soap(device, "SetAVTransportURI", "<CurrentURI>${escape(url)}</CurrentURI><CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>")
        soap(device, "Play", "<Speed>1</Speed>")
    }

    suspend fun pause(device: DlnaDevice) = soap(device, "Pause", "")
    suspend fun resume(device: DlnaDevice) = soap(device, "Play", "<Speed>1</Speed>")
    suspend fun stop(device: DlnaDevice) = soap(device, "Stop", "")
}
