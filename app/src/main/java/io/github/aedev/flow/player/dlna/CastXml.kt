package io.github.aedev.flow.player.dlna

/** Where a renderer is in its item, in seconds; either may be missing from its answer. */
internal data class CastPosition(
    val position: Long?,
    val duration: Long?,
)

/** The XML a renderer is sent: SOAP envelopes for AVTransport actions and the DIDL-Lite item metadata. */
internal object CastXml {
    fun soapEnvelope(
        action: String,
        serviceType: String,
        args: String,
    ): String =
        """<?xml version="1.0"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body>""" +
            """<u:$action xmlns:u="$serviceType">$args</u:$action>""" +
            """</s:Body></s:Envelope>"""

    fun didlLite(
        uri: String,
        title: String,
        contentType: String = "video/mp4",
    ): String =
        """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """ +
            """xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""" +
            """<item id="1" parentID="0" restricted="0">""" +
            """<dc:title>${escape(title)}</dc:title>""" +
            """<res protocolInfo="http-get:*:$contentType:*">${escape(uri)}</res>""" +
            """<upnp:class>object.item.videoItem</upnp:class>""" +
            """</item></DIDL-Lite>"""

    fun escape(s: String) =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    /** The content type a YouTube stream URL names in its query, or [fallback]. */
    fun guessContentType(
        url: String,
        fallback: String,
    ): String =
        when {
            url.contains("mime=video/mp4") || url.contains("mime=video%2Fmp4") -> "video/mp4"
            url.contains("mime=video/webm") || url.contains("mime=video%2Fwebm") -> "video/webm"
            url.contains("mime=audio/mp4") || url.contains("mime=audio%2Fmp4") -> "audio/mp4"
            url.contains("mime=audio/webm") || url.contains("mime=audio%2Fwebm") -> "audio/webm"
            url.contains("itag=18") -> "video/mp4"
            url.contains("itag=22") -> "video/mp4"
            url.contains("itag=37") -> "video/mp4"
            else -> fallback
        }

    fun positionInfo(xml: String): CastPosition =
        CastPosition(
            position =
                Regex("<RelTime>([^<]+)</RelTime>")
                    .find(xml)
                    ?.groupValues
                    ?.get(1)
                    ?.let(::parseTimeToSeconds),
            duration =
                Regex("<TrackDuration>([^<]+)</TrackDuration>")
                    .find(xml)
                    ?.groupValues
                    ?.get(1)
                    ?.let(::parseTimeToSeconds),
        )

    /** Seconds in a UPnP `H:MM:SS[.F]` time, or 0 when it isn't one. */
    fun parseTimeToSeconds(time: String): Long {
        val parts = time.split(":")
        if (parts.size != 3) return 0
        return try {
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val seconds = parts[2].split(".")[0].toLong()
            hours * 3600 + minutes * 60 + seconds
        } catch (e: Exception) {
            0
        }
    }
}
