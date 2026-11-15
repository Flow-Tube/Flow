package io.github.aedev.flow.player.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URI
import java.net.URL

/**
 * Finds DLNA renderers: sends an SSDP M-SEARCH to 239.255.255.250:1900, collects the AVTransport
 * responses, and fetches each device description to locate its AVTransport control URL.
 */
internal class SsdpDiscovery(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private var multicastLock: WifiManager.MulticastLock? = null

    /** Searches for [DISCOVERY_TIMEOUT_MS] ms and hands every resolved renderer to [onResolved]. */
    suspend fun discover(onResolved: (DlnaDevice) -> Unit) {
        val found = LinkedHashMap<String, DlnaDevice>()
        val mSearchQuery =
            buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 3\r\n")
                append("ST: urn:schemas-upnp-org:service:AVTransport:1\r\n")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)

        try {
            withTimeout(DISCOVERY_TIMEOUT_MS + 1_000L) {
                MulticastSocket(0).use { socket ->
                    socket.soTimeout = DISCOVERY_TIMEOUT_MS.toInt()
                    val group = InetAddress.getByName(SSDP_ADDR)
                    socket.joinGroup(group)

                    socket.send(
                        DatagramPacket(mSearchQuery, mSearchQuery.size, group, SSDP_PORT),
                    )

                    val buf = ByteArray(4096)
                    val packet = DatagramPacket(buf, buf.size)
                    val stop = SystemClock.elapsedRealtime() + DISCOVERY_TIMEOUT_MS
                    while (SystemClock.elapsedRealtime() < stop) {
                        try {
                            socket.receive(packet)
                            val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            parseSSDP(response)?.let { raw ->
                                if (!found.containsKey(raw.usn)) {
                                    found[raw.usn] = raw
                                    scope.launch { resolveDevice(raw)?.let(onResolved) }
                                }
                            }
                        } catch (_: Exception) {
                            // timeout on receive is normal
                        }
                    }
                    socket.leaveGroup(group)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSDP discovery error: ${e.message}")
        }
    }

    fun acquireMulticastLock(context: Context) {
        val wifiManager =
            context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock =
            wifiManager.createMulticastLock("flow_dlna_discovery").also {
                it.setReferenceCounted(true)
                it.acquire()
            }
    }

    fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
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
    }
}
