package io.github.aedev.flow.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DateDisplayTest {
    /**
     * `formatExactDate` moved from `SimpleDateFormat` to `DateTimeFormatter`. Every configured
     * style is a y/M/d pattern, which both formatters read identically, so the replacement must be
     * output-for-output equal to the one it replaced.
     */
    @Test
    fun `every configured date style still formats exactly as SimpleDateFormat did`() {
        val timestamps = listOf(1_672_920_000_000L, 1_700_000_000_000L, 946_684_800_000L)
        val locales = listOf(Locale.US, Locale.UK, Locale.GERMANY)

        for (timestamp in timestamps) {
            for (locale in locales) {
                for (style in DateFormatStyle.entries) {
                    val pattern = style.pattern ?: continue
                    assertEquals(
                        SimpleDateFormat(pattern, locale).format(Date(timestamp)),
                        formatExactDate(timestamp, style, locale),
                    )
                }
            }
        }
    }

    @Test
    fun `an unset timestamp formats to nothing`() {
        assertEquals("", formatExactDate(0L, DateFormatStyle.ISO, Locale.US))
        assertEquals("", formatExactDate(-1L, DateFormatStyle.ISO, Locale.US))
    }

    @Test
    fun `stored timestamp ages instead of resetting relative text`() {
        val now = 2_000_000_000_000L
        val publicationTimestamp = now - 25L * 60L * 60L * 1000L

        assertEquals(
            publicationTimestamp,
            resolveDisplayUploadTimestamp(
                date = "1 hour ago",
                timestampFallbackMs = publicationTimestamp,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `relative text remains a fallback when no timestamp was stored`() {
        val now = 2_000_000_000_000L

        assertEquals(
            now - 60L * 60L * 1000L,
            resolveDisplayUploadTimestamp(
                date = "1 hour ago",
                timestampFallbackMs = 0L,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `stored timestamp cannot make an older relative date look newer`() {
        val now = 2_000_000_000_000L

        assertEquals(
            now - 2L * 365L * 24L * 60L * 60L * 1000L,
            resolveDisplayUploadTimestamp(
                date = "2 years ago",
                timestampFallbackMs = now,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `a localized age resolves in the host language it was fetched in`() {
        val now = 2_000_000_000_000L

        assertEquals(
            now - 21L * 24L * 60L * 60L * 1000L,
            resolveDisplayUploadTimestamp(
                date = "hace 3 semanas",
                timestampFallbackMs = 0L,
                nowMillis = now,
                hl = "es",
            ),
        )
    }

    @Test
    fun `text no language reads is not guessed into a timestamp`() {
        assertNull(
            resolveDisplayUploadTimestamp(
                date = "hace 3 semanas",
                timestampFallbackMs = 0L,
                hl = "en",
            ),
        )
    }

    @Test
    fun `relative mode shows the server wording when nothing dates the video`() {
        assertEquals(
            "hace 3 semanas",
            formatUploadDateConfigured(
                date = "hace 3 semanas",
                mode = DateDisplayMode.RELATIVE,
                style = DateFormatStyle.SYSTEM,
                labels = TestDateLabels,
                hl = "en",
            ),
        )
    }

    private val now = 1_791_331_200_000L
    private val dayMs = 24L * 60L * 60L * 1000L

    private fun exactFor(
        date: String,
        stored: Long,
        exact: Boolean = false,
    ) = exactUploadTimestamp(
        date = date,
        resolvedTimestamp = resolveDisplayUploadTimestamp(date, stored, now, "en"),
        timestampFallbackMs = stored,
        timestampIsExact = exact,
        nowMillis = now,
        hl = "en",
    )

    @Test
    fun `an age in years is not turned into a calendar date`() {
        assertNull(exactFor("1 year ago", now - 365L * dayMs))
        assertNull(exactFor("1 year ago", 0L))
        assertNull(exactFor("3 days ago", now - 3L * dayMs))
        assertNull(exactFor("Streamed 2 weeks ago", now - 14L * dayMs))
    }

    @Test
    fun `an exact publish time keeps its date beside a coarse age`() {
        val published = now - 476L * dayMs

        assertEquals(published, exactFor("1 year ago", published, exact = true))
    }

    @Test
    fun `absolute text keeps its date`() {
        val parsed = parseToTimestamp("Jun 18, 2025", "en")

        assertEquals(parsed, exactFor("Jun 18, 2025", 0L))
        assertEquals(parseToTimestamp("Streamed live on Jun 19, 2020", "en"), exactFor("Streamed live on Jun 19, 2020", 0L))
    }

    @Test
    fun `an age under a day still places the date`() {
        assertEquals(now - 5L * 60L * 60L * 1000L, exactFor("5 hours ago", 0L))
        assertEquals(now - 45L * 60L * 1000L, exactFor("45 minutes ago", 0L))
    }

    @Test
    fun `blank text needs an exact source before it shows a date`() {
        val stored = now - 40L * dayMs

        assertEquals(stored, exactFor("", stored, exact = true))
        assertNull(exactFor("", stored))
    }
}
