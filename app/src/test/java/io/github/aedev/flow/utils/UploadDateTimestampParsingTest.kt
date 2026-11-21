package io.github.aedev.flow.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * `parseToTimestamp` moved from `SimpleDateFormat` to `java.time`. The reference below is the
 * replaced implementation, kept here so every form the feeds actually send must still land on the
 * same moment.
 */
class UploadDateTimestampParsingTest {
    private val savedLocale = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    private val corpus =
        listOf(
            "2024-01-15T10:00:00+01:00",
            "2024-01-15T10:00:00-05:30",
            "2024-01-15T10:00:00Z",
            "2024-01-15T10:00:00+01",
            "2024-01-15T10:00:00.123+01:00",
            "2024-01-15T10:00:00.123Z",
            "2024-01-15T10:00:00.123-05",
            "2024-01-15T10:00:00",
            "2024-01-15T10:00:00.123",
            "2024-01-15",
            "2024-1-5",
            "2024-01-15 trailing words",
            "Jun 18, 2025",
            "Jun 8, 2025",
            "jun 18, 2025",
            "JUN 18, 2025",
            "June 18, 2025",
            "18 Jun 2025",
            "8 Jun 2025",
            "18 June 2025",
            "Jun 18, 2025 and more",
            "Streamed live on Jun 19, 2020",
            "Premiered on Jan 1, 2020",
            "Premiered Jan 1, 2020",
            "Feb 29, 2024",
            "Feb 30, 2020",
            "2020-02-30",
            "2020-13-01",
            "Jun 32, 2020",
            "1700000000000",
            "Streamed live on",
            "not a date at all",
            "   ",
        )

    @Test
    fun `every feed date form parses to the same moment as SimpleDateFormat did`() {
        Locale.setDefault(Locale.US)
        for (text in corpus) {
            assertEquals(text, simpleDateFormatReference(text), parseToTimestamp(text, "en"))
        }
    }

    @Test
    fun `the device locale is tried after US, as before`() {
        Locale.setDefault(Locale.GERMANY)
        for (text in corpus + listOf("18 Juni 2025", "18 Jun. 2025", "Juni 18, 2025", "3 Okt. 2024")) {
            assertEquals(text, simpleDateFormatReference(text), parseToTimestamp(text, "en"))
        }
    }

    private fun simpleDateFormatReference(text: String?): Long? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toLongOrNull()?.takeIf { it > 100_000_000_000L }?.let { return it }
        val cleanRaw = withoutStreamOrPremiereWords(raw)
        val absFormats =
            listOf(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd",
                "MMM dd, yyyy",
                "MMM d, yyyy",
                "d MMM yyyy",
                "dd MMM yyyy",
            )
        for (f in absFormats) {
            for (loc in listOf(Locale.US, Locale.getDefault())) {
                try {
                    val sdf = SimpleDateFormat(f, loc)
                    sdf.isLenient = false
                    val d = sdf.parse(cleanRaw)
                    if (d != null) return d.time
                } catch (_: Exception) {
                }
            }
        }
        return null
    }
}
