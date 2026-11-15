package io.github.aedev.flow.player.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds DLNA renderers: sends SSDP M-SEARCHes to 239.255.255.250:1900 from every local network a
 * renderer could be on, collects the answers, and fetches each device description to locate its
 * AVTransport control URL.
 */
internal class SsdpDiscovery(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    /** Searches for [DISCOVERY_TIMEOUT_MS] ms and hands every resolved renderer to [onResolved]. */
    suspend fun discover(
        context: Context,
        onResolved: (DlnaDevice) -> Unit,
    ) {
        val lock = acquireMulticastLock(context)
        try {
            val sources = CastNetwork.discoveryInterfaces(CastNetwork.localAddresses())
            Log.i(TAG, "SSDP search on ${sources.joinToString { "${it.interfaceName}=${it.ip}" }.ifEmpty { "default route" }}")
            val found = ConcurrentHashMap<String, DlnaDevice>()
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS + 1_000L) {
                coroutineScope {
                    sources.ifEmpty { listOf(null) }.forEach { source ->
                        launch { searchFrom(source, found, onResolved) }
                    }
                }
            }
        } finally {
            lock?.let { if (it.isHeld) it.release() }
        }
    }

    private suspend fun searchFrom(
        source: LocalIpv4?,
        found: ConcurrentHashMap<String, DlnaDevice>,
        onResolved: (DlnaDevice) -> Unit,
    ) {
        try {
            val socket =
                source?.let { MulticastSocket(InetSocketAddress(InetAddress.getByName(it.ip), 0)) } ?: MulticastSocket(0)
            try {
                source?.let { NetworkInterface.getByName(it.interfaceName) }?.let(socket::setNetworkInterface)
                socket.soTimeout = RECEIVE_POLL_MS
                receiveAnswers(socket, source, found, onResolved)
            } finally {
                socket.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SSDP discovery error on ${source?.interfaceName ?: "default route"}: ${e.message}")
        }
    }

    private suspend fun receiveAnswers(
        socket: MulticastSocket,
        source: LocalIpv4?,
        found: ConcurrentHashMap<String, DlnaDevice>,
        onResolved: (DlnaDevice) -> Unit,
    ) {
        val group = InetAddress.getByName(SSDP_ADDR)
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        val start = SystemClock.elapsedRealtime()
        var sent = 0
        while (SystemClock.elapsedRealtime() < start + DISCOVERY_TIMEOUT_MS) {
            currentCoroutineContext().ensureActive()
            // Resent because a single UDP packet is easily lost on a busy hotspot.
            if (sent < SEARCH_ROUNDS && SystemClock.elapsedRealtime() >= start + sent * SEARCH_INTERVAL_MS) {
                SEARCH_TARGETS.forEach { target ->
                    val query = mSearch(target)
                    socket.send(DatagramPacket(query, query.size, group, SSDP_PORT))
                }
                sent++
            }
            val response =
                try {
                    // receive() shrinks the packet to the last answer, which would cut longer ones.
                    packet.length = buffer.size
                    socket.receive(packet)
                    String(packet.data, 0, packet.length, Charsets.UTF_8)
                } catch (_: SocketTimeoutException) {
                    null
                }
            val raw = response?.let(::parseSSDP)?.copy(localHost = source?.ip)
            // A renderer answers every round and every search target, under one uuid.
            if (raw != null && found.putIfAbsent(raw.usn.substringBefore("::"), raw) == null) {
                scope.launch { resolveDevice(raw)?.let(onResolved) }
            }
        }
    }

    private fun mSearch(target: String): ByteArray =
        buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 3\r\n")
            append("ST: $target\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)

    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? {
        val wifiManager =
            context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return wifiManager.createMulticastLock("flow_dlna_discovery").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun parseSSDP(response: String): DlnaDevice? {
        var location: String? = null
        var usn: String? = null
        for (line in response.lines()) {
            val lower = line.lowercase()
            when {
                lower.startsWith("location:") -> location = line.substringAfter(":").trim()
                lower.startsWith("usn:") -> usn = line.substringAfter(":").trim()
            }
        }
        if (location.isNullOrBlank()) return null
        return DlnaDevice(
            friendlyName = location,
            location = location,
            usn = usn ?: location,
        )
    }

    private fun resolveDevice(device: DlnaDevice): DlnaDevice? {
        return try {
            val descriptionUrl = URL(device.location)
            val xml =
                http
                    .newCall(Request.Builder().url(device.location).build())
                    .execute()
                    .use { it.body?.string() ?: "" }

            var friendlyName = device.friendlyName
            var urlBaseFromXml: String? = null
            var avTransportControlPath: String? = null
            var inAVTransport = false
            var inServiceType = false
            var inControlUrl = false

            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(xml.reader())

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase()
                        when (tag) {
                            "friendlyname" -> {
                                event = parser.next()
                                if (event == XmlPullParser.TEXT) friendlyName = parser.text.trim()
                            }

                            "urlbase" -> {
                                event = parser.next()
                                if (event == XmlPullParser.TEXT) urlBaseFromXml = parser.text.trim()
                            }

                            "servicetype" -> {
                                inServiceType = true
                            }

                            "controlurl" -> {
                                if (inAVTransport) inControlUrl = true
                            }
                        }
                    }

                    XmlPullParser.TEXT -> {
                        when {
                            inServiceType -> {
                                if (parser.text.lowercase().contains("avtransport")) inAVTransport = true
                                inServiceType = false
                            }

                            inControlUrl -> {
                                avTransportControlPath = parser.text.trim()
                                inControlUrl = false
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name.lowercase() == "service") inAVTransport = false
                    }
                }
                event = parser.next()
            }

            if (avTransportControlPath == null) {
                Log.w(TAG, "No AVTransport service found at ${device.location}")
                return null
            }
            val controlUrl =
                resolveControlUrl(
                    descriptionUrl = descriptionUrl,
                    urlBase = urlBaseFromXml,
                    controlPath = avTransportControlPath,
                )
            device.copy(friendlyName = friendlyName, avTransportUrl = controlUrl)
        } catch (e: Exception) {
            Log.e(TAG, "resolveDevice failed for ${device.location}: ${e.message}")
            null
        }
    }

    private fun resolveControlUrl(
        descriptionUrl: URL,
        urlBase: String?,
        controlPath: String,
    ): String {
        if (controlPath.startsWith("http://") || controlPath.startsWith("https://")) {
            return controlPath
        }
        val base =
            when {
                !urlBase.isNullOrBlank() -> URI(urlBase)
                else -> descriptionUrl.toURI()
            }
        return base.resolve(controlPath).toString()
    }

    private companion object {
        const val TAG = "DlnaCastManager"
        const val SSDP_ADDR = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val DISCOVERY_TIMEOUT_MS = 5_000L
        const val RECEIVE_POLL_MS = 250
        const val SEARCH_ROUNDS = 3
        const val SEARCH_INTERVAL_MS = 1_000L

        // Some renderers answer only the device type, others only the service.
        val SEARCH_TARGETS =
            listOf(
                "urn:schemas-upnp-org:service:AVTransport:1",
                "urn:schemas-upnp-org:device:MediaRenderer:1",
            )
    }
}
