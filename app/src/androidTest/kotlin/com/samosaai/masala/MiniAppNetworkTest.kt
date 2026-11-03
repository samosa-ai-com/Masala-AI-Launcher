package com.samosaai.masala

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.miniapps.MiniAppConsole
import com.samosaai.masala.miniapps.MiniAppView
import com.samosaai.masala.ui.theme.MasalaTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * With network off, a mini app reaches nothing: every way a page can open a connection is tried
 * against local sockets, one port per way. TLS URLs are used on purpose: the TCP connect reaches the
 * socket before the handshake fails, and an `https` page isn't stopped by the mixed-content rule.
 * The network-on run is the control: it proves each way can connect at all.
 */
@RunWith(AndroidJUnit4::class)
class MiniAppNetworkTest {
    @get:Rule
    val compose = createComposeRule()

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val reached = ConcurrentHashMap.newKeySet<String>()
    private val tcp = WAYS.associateWith { ServerSocket(0, 50, loopback) }
    private val udp = RTC_WAYS.associateWith { DatagramSocket(0, loopback) }

    init {
        tcp.forEach { (way, socket) ->
            thread(isDaemon = true) {
                try {
                    while (true) socket.accept().use { reached += way }
                } catch (_: SocketException) {
                    // Closed after the test.
                }
            }
        }
        udp.forEach { (way, socket) ->
            thread(isDaemon = true) {
                try {
                    socket.receive(DatagramPacket(ByteArray(1500), 1500))
                    reached += way
                } catch (_: SocketException) {
                }
            }
        }
    }

    @After
    fun close() {
        tcp.values.forEach { it.close() }
        udp.values.forEach { it.close() }
    }

    private fun port(way: String) = tcp.getValue(way).localPort
    private fun stun(way: String) = udp.getValue(way).localPort

    /** Script that opens a peer connection to [way]'s STUN port from whatever window it runs in. */
    private fun peerScript(way: String) =
        "var P=window.RTCPeerConnection||window.webkitRTCPeerConnection;if(P){var pc=new P({iceServers:[{urls:'stun:127.0.0.1:${stun(way)}'}]});" +
            "pc.createDataChannel('d');pc.createOffer().then(function(o){return pc.setLocalDescription(o);});}"

    private fun html() = """
        <!doctype html><html><body><script>
        function tryIt(name, f) { try { f(); } catch (e) { console.log(name + ' threw ' + e); } }
        tryIt('fetch', function () { fetch('https://127.0.0.1:${port("fetch")}/f').catch(function () {}); });
        tryIt('xhr', function () { var x = new XMLHttpRequest(); x.open('GET', 'https://127.0.0.1:${port("xhr")}/x'); x.send(); });
        tryIt('img', function () { var i = new Image(); i.src = 'https://127.0.0.1:${port("img")}/i.png'; document.body.appendChild(i); });
        tryIt('websocket', function () { new WebSocket('wss://127.0.0.1:${port("websocket")}/w'); });
        tryIt('beacon', function () { navigator.sendBeacon('https://127.0.0.1:${port("beacon")}/b', 'x'); });
        tryIt('eventsource', function () { new EventSource('https://127.0.0.1:${port("eventsource")}/e'); });
        function peer(w, port) {
          var P = w && (w.RTCPeerConnection || w.webkitRTCPeerConnection);
          if (!P) return;
          var pc = new P({ iceServers: [{ urls: 'stun:127.0.0.1:' + port }] });
          pc.createDataChannel('d');
          pc.createOffer().then(function (o) { return pc.setLocalDescription(o); });
        }
        tryIt('webrtc', function () { peer(window, ${stun("webrtc")}); });
        // Fresh frames get their own globals: the ways around anything done to this window only.
        tryIt('webrtc-iframe', function () {
          var f = document.createElement('iframe'); document.body.appendChild(f); peer(f.contentWindow, ${stun("webrtc-iframe")});
        });
        tryIt('webrtc-window0', function () {
          var d = document.createElement('div'); document.body.appendChild(d); d.innerHTML = '<iframe></iframe>';
          peer(window[window.length - 1], ${stun("webrtc-window0")});
        });
        tryIt('webrtc-nested', function () {
          var f = document.createElement('iframe'); document.body.appendChild(f);
          var g = f.contentDocument.createElement('iframe'); f.contentDocument.body.appendChild(g);
          peer(f.contentWindow[0], ${stun("webrtc-nested")});
        });
        tryIt('webrtc-srcdoc', function () {
          var f = document.createElement('iframe'); f.srcdoc = "<script>${peerScript("webrtc-srcdoc")}</scr" + "ipt>"; document.body.appendChild(f);
        });
        tryIt('webrtc-srcdoc-load', function () {
          var f = document.createElement('iframe'); f.srcdoc = '<p>x</p>';
          f.onload = function () { peer(window[Array.prototype.indexOf.call(document.querySelectorAll('iframe'), f)], ${stun("webrtc-srcdoc-load")}); };
          document.body.appendChild(f);
        });
        tryIt('webrtc-data', function () {
          var f = document.createElement('iframe'); f.src = 'data:text/html,' + encodeURIComponent("<script>${peerScript("webrtc-data")}</scr" + "ipt>"); document.body.appendChild(f);
        });
        tryIt('webrtc-onload', function () {
          // Runs during the insertion itself: an about:blank iframe fires load synchronously.
          var f = document.createElement('iframe');
          f.onload = function () { peer(window[window.length - 1], ${stun("webrtc-onload")}); };
          document.body.appendChild(f);
        });
        tryIt('webrtc-script', function () {
          // A script inserted with the frame runs before the insertion call returns.
          var f = document.createElement('iframe'), s = document.createElement('script');
          s.textContent = "peer(window[window.length - 1], ${stun("webrtc-script")});";
          document.body.append(f, s);
        });
        tryIt('webrtc-object', function () {
          var o = document.createElement('object'); o.type = 'text/html'; o.data = 'about:blank';
          o.onload = function () { peer(window[window.length - 1], ${stun("webrtc-object")}); };
          document.body.appendChild(o);
        });
        tryIt('webrtc-open', function () { var w = window.open(''); if (w && w !== window) peer(w, ${stun("webrtc-open")}); });
        tryIt('worker-websocket', function () {
          var src = "new WebSocket('wss://127.0.0.1:${port("worker-websocket")}/w');";
          new Worker(URL.createObjectURL(new Blob([src], { type: 'text/javascript' })));
        });
        </script></body></html>
    """.trimIndent()

    private fun load(networkAllowed: Boolean): Set<String> {
        val id = if (networkAllowed) "net-on" else "net-off"
        MiniAppConsole.clear(id)
        compose.setContent { MasalaTheme(LauncherSettings()) { MiniAppView(id, html(), networkAllowed) } }
        compose.waitForIdle()
        Thread.sleep(6_000)
        android.util.Log.i("MiniAppNetworkTest", "network=$networkAllowed reached=$reached console=${MiniAppConsole.errors(id)}")
        return reached.toSet()
    }

    @Test
    fun networkOnReachesEveryWay() {
        // The control: if a way can't connect even with network on, the off test proves nothing for it.
        assertEquals((WAYS + RTC_WAYS).toSet() - CANT_CONNECT, load(networkAllowed = true))
    }

    @Test
    fun networkOffReachesNothing() {
        assertEquals(emptySet<String>(), load(networkAllowed = false))
    }

    private companion object {
        val WAYS = listOf("fetch", "xhr", "img", "websocket", "beacon", "eventsource", "worker-websocket")
        val RTC_WAYS = listOf(
            "webrtc", "webrtc-iframe", "webrtc-window0", "webrtc-nested", "webrtc-srcdoc", "webrtc-srcdoc-load", "webrtc-data",
            "webrtc-onload", "webrtc-script", "webrtc-object", "webrtc-open",
        )

        /** Ways WebView itself never opens, network on or off (logged by the control run). */
        val CANT_CONNECT = setOf("webrtc-data", "webrtc-open")
    }
}
