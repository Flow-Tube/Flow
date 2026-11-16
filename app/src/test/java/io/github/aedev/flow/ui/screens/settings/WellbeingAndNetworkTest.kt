package io.github.aedev.flow.ui.screens.settings

import io.github.aedev.flow.network.AppProxyConfig
import io.github.aedev.flow.network.AppProxyType
import io.github.aedev.flow.ui.screens.settings.network.ProxyDraft
import io.github.aedev.flow.ui.screens.settings.wellbeing.WatchRecord
import io.github.aedev.flow.ui.screens.settings.wellbeing.summarizeWatchTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class WellbeingAndNetworkTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 3, 29)

    private fun at(
        date: LocalDate,
        time: LocalTime,
    ) = date
        .atTime(time)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun `watch time lands on the local calendar day, oldest first`() {
        val records =
            listOf(
                WatchRecord(at(today, LocalTime.of(0, 5)), 60_000),
                WatchRecord(at(today.minusDays(1), LocalTime.of(23, 55)), 120_000),
                WatchRecord(at(today.minusDays(6), LocalTime.NOON), 30_000),
                WatchRecord(at(today.minusDays(7), LocalTime.NOON), 999_000),
                WatchRecord(at(today.plusDays(1), LocalTime.NOON), 999_000),
            )
        val summary = summarizeWatchTime(records, today, zone)
        assertEquals(7, summary.days.size)
        assertEquals(today.minusDays(6), summary.days.first().date)
        assertEquals(today, summary.days.last().date)
        assertEquals(60_000L, summary.todayMillis)
        assertEquals(120_000L, summary.days[5].millis)
        assertEquals(30_000L, summary.days[0].millis)
        assertEquals(210_000L, summary.weekMillis)
        assertEquals(30_000L, summary.dailyAverageMillis)
    }

    @Test
    fun `negative positions do not subtract watch time`() {
        val summary = summarizeWatchTime(listOf(WatchRecord(at(today, LocalTime.NOON), -5_000)), today, zone)
        assertTrue(summary.isEmpty)
    }

    @Test
    fun `a selected day is found in the week, and a day outside it is not`() {
        val week = summarizeWatchTime(listOf(WatchRecord(at(today.minusDays(1), LocalTime.NOON), 60_000L)), today, zone)

        assertEquals(60_000L, week.dayOrNull(today.minusDays(1))?.millis)
        assertEquals(0L, week.dayOrNull(today)?.millis)
        assertNull(week.dayOrNull(today.minusDays(7)))
        assertNull(week.dayOrNull(null))
    }

    @Test
    fun `proxy drafts need a host and a port only while enabled`() {
        val off = ProxyDraft(enabled = false, type = AppProxyType.HTTP, host = "", port = "", username = "", password = "")
        assertTrue(off.valid)
        val on = off.copy(enabled = true)
        assertTrue(on.hostError)
        assertTrue(on.portError)
        assertFalse(on.copy(host = "proxy.local", port = "65536").valid)
        assertTrue(on.copy(host = "proxy.local", port = "1080").valid)
    }

    @Test
    fun `proxy drafts trim what they save and keep the password as typed`() {
        val draft = ProxyDraft(true, AppProxyType.SOCKS5, " proxy.local ", "1080", " me ", " secret ")
        assertEquals(
            AppProxyConfig(true, AppProxyType.SOCKS5, "proxy.local", 1080, "me", " secret "),
            draft.toConfig(fallbackPort = 8080),
        )
        assertEquals(ProxyDraft.of(draft.toConfig(8080)).port, "1080")
    }

    @Test
    fun `the vpn bypass survives a save and reload and stays off by default`() {
        val draft = ProxyDraft(true, AppProxyType.HTTP, "proxy.local", "3128", "", "")
        assertFalse(draft.toConfig(8080).bypassOnVpn)

        val saved = draft.copy(bypassOnVpn = true).toConfig(8080)

        assertTrue(saved.bypassOnVpn)
        assertTrue(ProxyDraft.of(saved).bypassOnVpn)
        assertFalse(AppProxyConfig().bypassOnVpn)
    }
}
