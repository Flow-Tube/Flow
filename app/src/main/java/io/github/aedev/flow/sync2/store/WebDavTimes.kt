package io.github.aedev.flow.sync2.store

import org.xmlpull.v1.XmlPullParser
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val DEVICE_FOLDER = Regex("[0-9a-f]{32}")

/** Servers whose folder ETags and times change with anything inside: Nextcloud and ownCloud. */
internal val PROPAGATING = setOf("http://nextcloud.org/ns", "http://owncloud.org/ns")

/** A `PROPFIND` answer's `getlastmodified` for each `href` that has one, and whether the server propagates folder changes. */
internal class DavTimes(
    val modified: Map<String, Long>,
    val propagates: Boolean,
)

/** An RFC 1123 date, as `getlastmodified` carries it, in epoch milliseconds; null when it isn't one. */
internal fun httpDate(text: String): Long? =
    try {
        ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

/** Reads a multistatus body from [parser], which processes namespaces. */
internal fun readDavTimes(parser: XmlPullParser): DavTimes {
    val modified = HashMap<String, Long>()
    var propagates = false
    var href: String? = null
    var time: Long? = null
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                for (i in parser.getNamespaceCount(parser.depth - 1) until parser.getNamespaceCount(parser.depth)) {
                    if (parser.getNamespaceUri(i) in PROPAGATING) propagates = true
                }
                if (parser.namespace != "DAV:") continue
                when (parser.name) {
                    "response" -> {
                        href = null
                        time = null
                    }

                    "href" -> {
                        href = parser.nextText().trim()
                    }

                    "getlastmodified" -> {
                        time = httpDate(parser.nextText())
                    }
                }
            }

            XmlPullParser.END_TAG -> {
                val done = href
                val at = time
                if (parser.namespace == "DAV:" && parser.name == "response" && done != null && at != null) modified[done] = at
            }
        }
    }
    return DavTimes(modified, propagates)
}

/**
 * When each device last wrote, from the times of the `dev/<did>/` folders in [times]. Only a server whose folder times
 * follow what changes inside answers it; elsewhere a folder's time is when it was made, so nothing is answered.
 */
internal fun deviceTimes(times: DavTimes): Map<String, Long> {
    if (!times.propagates) return emptyMap()
    val devices = HashMap<String, Long>()
    for ((href, at) in times.modified) {
        val name = href.trimEnd('/').substringAfterLast('/')
        if (DEVICE_FOLDER.matches(name)) devices[name] = maxOf(at, devices[name] ?: 0)
    }
    return devices
}
