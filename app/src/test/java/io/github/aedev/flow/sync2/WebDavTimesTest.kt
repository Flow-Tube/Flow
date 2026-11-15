package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync2.store.DavTimes
import io.github.aedev.flow.sync2.store.deviceTimes
import io.github.aedev.flow.sync2.store.httpDate
import io.github.aedev.flow.sync2.store.readDavTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** `getlastmodified` in a `PROPFIND Depth: 1` answer for `dev/`, written by hand in the shapes Nextcloud and Koofr answer. */
class WebDavTimesTest {
    private val a = "0123456789abcdef0123456789abcdef"
    private val b = "fedcba9876543210fedcba9876543210"

    private fun read(xml: String): DavTimes =
        readDavTimes(
            KXmlParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(StringReader(xml))
            },
        )

    private fun at(
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long = ZonedDateTime.of(2026, 11, day, hour, minute, second, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun nextcloudItem(
        path: String,
        modified: String,
    ) = """<d:response><d:href>/remote.php/dav/files/user/Flow/$path</d:href><d:propstat><d:prop>""" +
        """<d:getlastmodified>$modified</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    private val nextcloud =
        """<?xml version="1.0"?>""" +
            """<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" """ +
            """xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">""" +
            nextcloudItem("dev/", "Sat, 14 Nov 2026 09:30:00 GMT") +
            nextcloudItem("dev/$a/", "Sat, 14 Nov 2026 09:30:00 GMT") +
            nextcloudItem("dev/$b/", "Fri, 13 Nov 2026 22:05:09 GMT") +
            """</d:multistatus>"""

    private val koofr =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<D:multistatus xmlns:D="DAV:">""" +
            """<D:response><D:href>/dav/Koofr/Flow/dev/</D:href><D:propstat><D:prop>""" +
            """<D:getlastmodified>Mon, 2 Nov 2026 08:00:00 GMT</D:getlastmodified></D:prop>""" +
            """<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>""" +
            """<D:response><D:href>https://app.koofr.net/dav/Koofr/Flow/dev/$a/</D:href><D:propstat><D:prop>""" +
            """<D:getlastmodified>Tue, 3 Nov 2026 07:04:05 GMT</D:getlastmodified></D:prop>""" +
            """<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>""" +
            """<D:response><D:href>/dav/Koofr/Flow/dev/$b/</D:href><D:propstat><D:prop><D:getlastmodified/></D:prop>""" +
            """<D:status>HTTP/1.1 404 Not Found</D:status></D:propstat></D:response>""" +
            """</D:multistatus>"""

    @Test
    fun rfc_1123_dates_read_as_utc_milliseconds() {
        assertEquals(at(14, 9, 30, 0), httpDate("Sat, 14 Nov 2026 09:30:00 GMT"))
        assertEquals("a one-digit day", at(3, 7, 4, 5), httpDate(" Tue, 3 Nov 2026 07:04:05 GMT "))
        assertEquals("an offset instead of GMT", at(14, 9, 30, 0), httpDate("Sat, 14 Nov 2026 10:30:00 +0100"))
        assertNull("ISO 8601 is not what the property carries", httpDate("2026-11-14T09:30:00Z"))
        assertNull(httpDate(""))
    }

    @Test
    fun nextcloud_answers_each_device_folder_time() {
        val times = read(nextcloud)
        assertTrue(times.propagates)
        assertEquals(at(14, 9, 30, 0), times.modified["/remote.php/dav/files/user/Flow/dev/$a/"])
        assertEquals(mapOf(a to at(14, 9, 30, 0), b to at(13, 22, 5, 9)), deviceTimes(times))
    }

    @Test
    fun koofr_items_parse_but_its_folder_times_are_not_taken_for_writes() {
        val times = read(koofr)
        assertFalse(times.propagates)
        assertEquals(at(3, 7, 4, 5), times.modified["https://app.koofr.net/dav/Koofr/Flow/dev/$a/"])
        assertNull("an item without the property is left out", times.modified["/dav/Koofr/Flow/dev/$b/"])
        assertEquals(emptyMap<String, Long>(), deviceTimes(times))
    }

    @Test
    fun only_device_folders_count_and_a_bad_date_drops_its_item_alone() {
        val xml =
            """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">""" +
                nextcloudItem("dev/", "Sat, 14 Nov 2026 09:30:00 GMT") +
                nextcloudItem("dev/not-a-device/", "Sat, 14 Nov 2026 09:30:00 GMT") +
                nextcloudItem("dev/$a/", "yesterday") +
                nextcloudItem("dev/$b/", "Fri, 13 Nov 2026 22:05:09 GMT") +
                """</d:multistatus>"""
        assertEquals(mapOf(b to at(13, 22, 5, 9)), deviceTimes(read(xml)))
    }
}
