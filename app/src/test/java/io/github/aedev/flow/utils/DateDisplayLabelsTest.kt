package io.github.aedev.flow.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** The English values of the resources the app resolves into [DateLabels]. */
internal val TestDateLabels =
    DateLabels(
        live = "LIVE",
        streamedTemplate = "Streamed %1\$s",
        premieredTemplate = "Premiered %1\$s",
    )

class DateDisplayLabelsTest {
    private fun format(
        date: String,
        mode: DateDisplayMode,
        style: DateFormatStyle = DateFormatStyle.ISO,
        labels: DateLabels = TestDateLabels,
    ) = formatUploadDateConfigured(
        date = date,
        mode = mode,
        style = style,
        labels = labels,
        hl = "en",
    )

    @Test
    fun `a live stream reads LIVE in every mode`() {
        assertEquals("LIVE", format("live", DateDisplayMode.RELATIVE))
        assertEquals("LIVE", format(" Live ", DateDisplayMode.EXACT))
        assertEquals("LIVE", format("LIVE", DateDisplayMode.BOTH))
    }

    @Test
    fun `a streamed or premiered date keeps its word in front of the exact date`() {
        assertEquals("Streamed 2020-06-19", format("Streamed live on Jun 19, 2020", DateDisplayMode.EXACT))
        assertEquals("Premiered 2020-01-01", format("Premiered on Jan 1, 2020", DateDisplayMode.EXACT))
        assertEquals("Premiered 01/01/2020", format("Premiered Jan 1, 2020", DateDisplayMode.EXACT, DateFormatStyle.DMY_SLASH))
    }

    @Test
    fun `a plain date in exact mode has no prefix`() {
        assertEquals("2025-06-18", format("Jun 18, 2025", DateDisplayMode.EXACT))
    }

    @Test
    fun `the words come from the labels, so a translation can reorder them`() {
        val german = DateLabels(live = "LIVE-X", streamedTemplate = "%1\$s gestreamt", premieredTemplate = "Premiere am %1\$s")

        assertEquals("LIVE-X", format("live", DateDisplayMode.RELATIVE, labels = german))
        assertEquals("2020-06-19 gestreamt", format("Streamed live on Jun 19, 2020", DateDisplayMode.EXACT, labels = german))
        assertEquals("Premiere am 2020-01-01", format("Premiered on Jan 1, 2020", DateDisplayMode.EXACT, labels = german))
    }

    @Test
    fun `unread server wording is shown as sent, without a prefix`() {
        assertEquals("hace 3 semanas", format("hace 3 semanas", DateDisplayMode.RELATIVE))
    }
}
