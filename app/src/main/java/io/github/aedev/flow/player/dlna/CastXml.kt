package io.github.aedev.flow.player.dlna

/** Where a renderer is in its item, in seconds; either may be missing from its answer. */
internal data class CastPosition(
    val position: Long?,
    val duration: Long?,
)

/** What a renderer is told about the item it plays; a [music] item is a song with its artist, album and art. */
internal data class CastItem(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artUrl: String? = null,
    val durationSeconds: Long = 0,
    val music: Boolean = false,
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
        item: CastItem,
        contentType: String = "video/mp4",
    ): String {
        val song =
            if (item.music) {
                item.artist
                    ?.takeIf {
                        it.isNotBlank()
                    }?.let { """<dc:creator>${escape(it)}</dc:creator><upnp:artist>${escape(it)}</upnp:artist>""" }
                    .orEmpty() +
                    item.album
                        ?.takeIf { it.isNotBlank() }
                        ?.let { """<upnp:album>${escape(it)}</upnp:album>""" }
                        .orEmpty() +
                    item.artUrl?.let { """<upnp:albumArtURI>${escape(it)}</upnp:albumArtURI>""" }.orEmpty()
            } else {
                ""
            }
        val duration =
            item.durationSeconds
                .takeIf { it > 0 }
                ?.let { """ duration="${formatDuration(it)}"""" }
                .orEmpty()
        val upnpClass = if (item.music) "object.item.audioItem.musicTrack" else "object.item.videoItem"
        return """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """ +
            """xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""" +
            """<item id="1" parentID="0" restricted="0">""" +
            """<dc:title>${escape(item.title)}</dc:title>""" +
            song +
            """<res protocolInfo="http-get:*:$contentType:*"$duration>${escape(uri)}</res>""" +
            """<upnp:class>$upnpClass</upnp:class>""" +
            """</item></DIDL-Lite>"""
    }

    /** A DIDL-Lite `res` duration, `H:MM:SS.000`. */
    fun formatDuration(seconds: Long): String = "%d:%02d:%02d.000".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)

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
