package com.cloudstreampc.player

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.compactJson
import com.cloudstreampc.core.mapper
import com.cloudstreampc.ui.I18n
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Phone remote: a page served on the local network (scan the QR code, or type the address and the
 * 4-digit code shown by the app) with play/pause, ±10 s, volume, subtitles, audio, next episode
 * and channel zapping. Commands go to mpv through its IPC. Only devices of the local network are
 * answered, and only with the code or the key carried by the QR code.
 */
object RemoteControl {
    private val random = SecureRandom()
    private const val FIRST_PORT = 8765

    @Volatile private var server: HttpServer? = null
    val running get() = server != null

    /** Shown in the app; changes at each start. */
    var pin: String = newPin()
        private set

    /** Key carried by the QR code (after `#`, so it never travels in a request line). */
    var key: String = newKey()
        private set

    /** Keys given to the phones that typed the code. */
    private val paired = ConcurrentHashMap.newKeySet<String>()
    private var failures = 0
    private var lockedUntil = 0L

    private fun newPin() = (1000 + random.nextInt(9000)).toString()
    private fun newKey() = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    fun applySettings() {
        if (Store.settings.remoteControl) start() else stop()
    }

    @Synchronized
    fun start(): Int? {
        server?.let { return it.address.port }
        pin = newPin()
        key = newKey()
        paired.clear()
        for (port in (FIRST_PORT until FIRST_PORT + 10) + 0) {
            val created = runCatching { HttpServer.create(InetSocketAddress(port), 0) }.getOrNull() ?: continue
            created.executor = Executors.newFixedThreadPool(2) { Thread(it, "remote").apply { isDaemon = true } }
            created.createContext("/") { exchange -> runCatching { serve(exchange) }.onFailure { Logger.error("Télécommande", it); exchange.close() } }
            created.start()
            server = created
            Logger.info("Télécommande sur le port ${created.address.port}")
            return created.address.port
        }
        Logger.info("Télécommande : aucun port disponible")
        return null
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
    }

    /** Address to open on the phone (local network), with the key for the QR code. */
    fun address(withKey: Boolean): String? {
        val port = server?.address?.port ?: return null
        val host = lanAddress() ?: return null
        return "http://$host:$port/" + if (withKey) "#$key" else ""
    }

    /** This computer's address on the local network. */
    fun lanAddress(): String? {
        // The interface used to reach the outside (no packet is sent).
        runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName("192.168.1.1"), 9)
                socket.localAddress.takeIf { it is Inet4Address && it.isSiteLocalAddress }?.let { return it.hostAddress }
            }
        }
        return NetworkInterface.networkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .sortedBy { if (it.hostAddress.startsWith("192.168.")) 0 else if (it.isSiteLocalAddress) 1 else 2 }
            .firstOrNull()?.hostAddress
    }

    private fun isLocal(address: InetAddress) =
        address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            // IPv6 unique local addresses (fc00::/7).
            (address.address.size == 16 && (address.address[0].toInt() and 0xFE) == 0xFC)

    private fun serve(exchange: HttpExchange) {
        if (!isLocal(exchange.remoteAddress.address)) return reply(exchange, 403, "text/plain", "Réseau local uniquement")
        val path = exchange.requestURI.path
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        when {
            path == "/" || path == "/index.html" -> reply(exchange, 200, "text/html; charset=utf-8", page())
            path == "/api/pair" && exchange.requestMethod == "POST" -> pair(exchange, query["pin"].orEmpty())
            path.startsWith("/api/") -> {
                val token = exchange.requestHeaders.getFirst("X-Remote-Key").orEmpty()
                if (token != key && token !in paired) return reply(exchange, 401, "application/json", """{"error":"code"}""")
                when (path) {
                    "/api/state" -> reply(exchange, 200, "application/json", compactJson(state()))
                    "/api/cmd" -> {
                        val ok = command(query["c"].orEmpty())
                        reply(exchange, if (ok) 200 else 409, "application/json", """{"ok":$ok}""")
                    }
                    else -> reply(exchange, 404, "text/plain", "")
                }
            }
            else -> reply(exchange, 404, "text/plain", "")
        }
    }

    private fun pair(exchange: HttpExchange, given: String) {
        val now = System.currentTimeMillis()
        val result = synchronized(this) {
            when {
                now < lockedUntil -> null
                given == pin -> {
                    failures = 0
                    newKey().also { paired += it }
                }
                else -> {
                    // Five wrong codes: one minute's wait, so the code can't be guessed.
                    if (++failures >= 5) {
                        failures = 0
                        lockedUntil = now + 60_000
                    }
                    ""
                }
            }
        }
        when {
            result == null -> reply(exchange, 429, "application/json", """{"error":"wait"}""")
            result.isEmpty() -> reply(exchange, 401, "application/json", """{"error":"code"}""")
            else -> reply(exchange, 200, "application/json", compactJson(mapOf("key" to result)))
        }
    }

    /** IPC of what plays now: the video in the window, or the one followed by the playback manager. */
    private fun ipc(): String? = PlayerSurface.ipcPath ?: PlaybackManager.currentIpc

    fun state(): Map<String, Any?> {
        val now = PlaybackManager.nowPlaying
        val path = ipc() ?: return mapOf("playing" to false)
        val values = MpvIpc.get(path, "media-title", "time-pos", "duration", "pause", "volume", "mute")
        if (values.isEmpty()) return mapOf("playing" to false)
        return mapOf(
            "playing" to true,
            "title" to (values["media-title"]?.asText() ?: PlayerSurface.title),
            "position" to values["time-pos"]?.asDouble(),
            "duration" to values["duration"]?.asDouble(),
            "paused" to (values["pause"]?.asBoolean() ?: false),
            "volume" to values["volume"]?.asDouble(),
            "muted" to (values["mute"]?.asBoolean() ?: false),
            "live" to (now?.request?.isLive ?: false),
            "hasNext" to (now?.request?.next != null),
            "fullscreen" to PlayerSurface.fullscreen,
        )
    }

    fun command(name: String): Boolean {
        val path = ipc() ?: return false
        val commands: List<List<Any>> = when (name) {
            "toggle" -> listOf(listOf("cycle", "pause"))
            "back" -> listOf(listOf("seek", -10))
            "forward" -> listOf(listOf("seek", 10))
            "skip" -> listOf(listOf("seek", 85))
            "volup" -> listOf(listOf("add", "volume", 5))
            "voldown" -> listOf(listOf("add", "volume", -5))
            "mute" -> listOf(listOf("cycle", "mute"))
            "sub" -> listOf(listOf("cycle", "sub"))
            "audio" -> listOf(listOf("cycle", "audio"))
            "next" -> listOf(listOf("script-message-to", "cloudstreampc", "next"))
            "zapnext" -> listOf(listOf("script-message-to", "cloudstreampc", "zap", "1"))
            "zapprev" -> listOf(listOf("script-message-to", "cloudstreampc", "zap", "-1"))
            "fullscreen" -> {
                if (PlayerSurface.active) {
                    PlayerSurface.switchFullscreen(!PlayerSurface.fullscreen)
                    return true
                }
                listOf(listOf("cycle", "fullscreen"))
            }
            "stop" -> listOf(listOf("quit", 0))
            else -> return false
        }
        return MpvIpc.send(path, *commands.toTypedArray())
    }

    private fun reply(exchange: HttpExchange, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", type)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
        exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun page(): String {
        fun t(text: String) = mapper.writeValueAsString(I18n.tr(text))
        val labels = mapOf(
            "title" to "Télécommande", "code" to "Code affiché par CloudStreamPC (Paramètres → Lecteur)", "ok" to "Valider",
            "wrong" to "Code incorrect", "wait" to "Trop d'essais, réessayez dans une minute", "idle" to "Rien en lecture",
            "next" to "Épisode suivant", "sub" to "Sous-titres", "audio" to "Audio", "full" to "Plein écran", "stop" to "Arrêter",
            "chprev" to "Chaîne précédente", "chnext" to "Chaîne suivante", "live" to "En direct",
        ).mapValues { t(it.value) }
        val js = labels.entries.joinToString(",") { "${it.key}:${it.value}" }
        return REMOTE_PAGE.replace("/*LABELS*/", js).replace("__LANG__", I18n.language)
            .replace("__DIR__", if (I18n.rightToLeft) "rtl" else "ltr")
    }
}

private const val REMOTE_PAGE = """<!doctype html>
<html lang="__LANG__" dir="__DIR__"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<meta name="theme-color" content="#000000"><title>CloudStreamPC</title>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:#000;color:#fff;font:16px system-ui,-apple-system,Segoe UI,Roboto,sans-serif;padding:16px;max-width:520px;margin:auto}
h1{font-size:18px;margin:4px 0 14px;color:#9aa0ff}
#np{background:#1c1c1e;border-radius:14px;padding:14px;margin-bottom:16px}
#t{font-weight:600;font-size:17px;min-height:22px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
#bar{height:6px;background:#333;border-radius:3px;margin:12px 0 6px;overflow:hidden}#fill{height:100%;width:0;background:#3d50fa}
#time{color:#a0a0a8;font-size:13px;display:flex;justify-content:space-between}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:10px}
button{background:#26262a;color:#fff;border:0;border-radius:14px;padding:18px 6px;font-size:15px;font-weight:600;touch-action:manipulation}
button:active{background:#3d50fa}
.big{font-size:30px;padding:22px 6px}.main{background:#3d50fa}
#pair{display:none;text-align:center}#pin{font-size:32px;letter-spacing:12px;width:180px;text-align:center;padding:10px;border-radius:12px;border:0;background:#1c1c1e;color:#fff;margin:14px 0}
#err{color:#e5484d;min-height:20px}.hide{display:none}
</style></head><body>
<h1 id="h"></h1>
<div id="pair"><p id="codeLabel"></p><input id="pin" inputmode="numeric" maxlength="4" autocomplete="off"><br><button class="main" id="go"></button><p id="err"></p></div>
<div id="remote" class="hide">
<div id="np"><div id="t"></div><div id="bar"><div id="fill"></div></div><div id="time"><span id="pos"></span><span id="dur"></span></div></div>
<div class="grid"><button class="big" data-c="back">⟲10</button><button class="big main" id="pp" data-c="toggle">▶︎❚❚</button><button class="big" data-c="forward">10⟳</button></div>
<div class="grid"><button data-c="voldown">🔉 −</button><button data-c="mute">🔇</button><button data-c="volup">🔊 +</button></div>
<div class="grid"><button data-c="sub" id="bsub"></button><button data-c="audio" id="baudio"></button><button data-c="fullscreen" id="bfull"></button></div>
<div class="grid" id="vod"><button data-c="skip">+85 s</button><button data-c="next" id="bnext"></button><button data-c="stop" id="bstop"></button></div>
<div class="grid hide" id="tv"><button data-c="zapprev" id="bprev"></button><button data-c="stop" id="bstop2"></button><button data-c="zapnext" id="bchnext"></button></div>
</div>
<script>
const L={/*LABELS*/};
const $=id=>document.getElementById(id);
$('h').textContent='CloudStreamPC · '+L.title;$('codeLabel').textContent=L.code;$('go').textContent=L.ok;
$('bsub').textContent=L.sub;$('baudio').textContent=L.audio;$('bfull').textContent=L.full;$('bnext').textContent=L.next;
$('bstop').textContent=L.stop;$('bstop2').textContent=L.stop;$('bprev').textContent=L.chprev;$('bchnext').textContent=L.chnext;
let key=null;try{key=localStorage.getItem('cspcKey')}catch(e){}
if(location.hash.length>1){key=location.hash.slice(1);try{localStorage.setItem('cspcKey',key)}catch(e){}history.replaceState(null,'',location.pathname)}
function show(paired){$('pair').style.display=paired?'none':'block';$('remote').className=paired?'':'hide'}
function fmt(s){if(s==null||isNaN(s))return'';s=Math.floor(s);const h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=s%60;return(h?h+':'+String(m).padStart(2,'0'):m)+':'+String(x).padStart(2,'0')}
async function api(path,opts){const r=await fetch(path,Object.assign({headers:{'X-Remote-Key':key||''}},opts||{}));if(r.status==401){key=null;show(false);throw 0}return r}
async function refresh(){if(!key)return show(false);try{const s=await (await api('/api/state')).json();show(true);
$('t').textContent=s.playing?(s.title||''):L.idle;const d=s.duration||0,p=s.position||0;
$('fill').style.width=(s.live||!d?0:Math.min(100,p/d*100))+'%';$('pos').textContent=s.live?L.live:fmt(p);$('dur').textContent=s.live?'':fmt(d);
$('pp').textContent=s.paused?'▶︎':'❚❚';$('vod').className=s.live?'grid hide':'grid';$('tv').className=s.live?'grid':'grid hide';
$('bnext').style.visibility=s.hasNext?'visible':'hidden'}catch(e){}}
document.querySelectorAll('button[data-c]').forEach(b=>b.onclick=async()=>{try{await api('/api/cmd?c='+b.dataset.c,{method:'POST'});if(navigator.vibrate)navigator.vibrate(15);setTimeout(refresh,250)}catch(e){}});
$('go').onclick=async()=>{const r=await fetch('/api/pair?pin='+encodeURIComponent($('pin').value),{method:'POST'});
if(r.ok){key=(await r.json()).key;try{localStorage.setItem('cspcKey',key)}catch(e){}$('err').textContent='';refresh()}else $('err').textContent=r.status==429?L.wait:L.wrong};
refresh();setInterval(refresh,1500);
</script></body></html>"""
