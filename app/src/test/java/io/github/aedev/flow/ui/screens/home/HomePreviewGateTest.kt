package io.github.aedev.flow.ui.screens.home

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.FeedAutoplay
import org.junit.Test

class HomePreviewGateTest {
    private val open = HomePreviewSignals(autoplay = FeedAutoplay.ALWAYS, foreground = true)

    @Test
    fun `always, in the foreground and nothing else on, lets a preview play`() {
        assertThat(HomePreviewGate.blockOf(open)).isNull()
    }

    @Test
    fun `off is off whatever else holds`() {
        assertThat(HomePreviewGate.blockOf(open.copy(autoplay = FeedAutoplay.OFF, onWifi = true)))
            .isEqualTo(HomePreviewBlock.SETTING_OFF)
    }

    @Test
    fun `wifi only needs wifi, always does not`() {
        assertThat(HomePreviewGate.blockOf(open.copy(autoplay = FeedAutoplay.WIFI_ONLY))).isEqualTo(HomePreviewBlock.NOT_ON_WIFI)
        assertThat(HomePreviewGate.blockOf(open.copy(autoplay = FeedAutoplay.WIFI_ONLY, onWifi = true))).isNull()
        assertThat(HomePreviewGate.blockOf(open.copy(onWifi = false))).isNull()
    }

    @Test
    fun `every device and app signal closes the gate on its own`() {
        val cases =
            mapOf(
                open.copy(accessibilityOn = true) to HomePreviewBlock.ACCESSIBILITY,
                open.copy(casting = true) to HomePreviewBlock.CASTING,
                open.copy(mainPlayerActive = true) to HomePreviewBlock.MAIN_PLAYER,
                open.copy(batterySaver = true) to HomePreviewBlock.BATTERY_SAVER,
                open.copy(lowRamDevice = true) to HomePreviewBlock.LOW_RAM,
                open.copy(thermalSevere = true) to HomePreviewBlock.THERMAL,
                open.copy(foreground = false) to HomePreviewBlock.NOT_FOREGROUND,
            )
        cases.forEach { (signals, block) -> assertThat(HomePreviewGate.blockOf(signals)).isEqualTo(block) }
    }

    @Test
    fun `the setting is reported before any device signal`() {
        val everything =
            HomePreviewSignals(
                autoplay = FeedAutoplay.OFF,
                accessibilityOn = true,
                casting = true,
                mainPlayerActive = true,
                batterySaver = true,
                lowRamDevice = true,
                thermalSevere = true,
            )
        assertThat(HomePreviewGate.blockOf(everything)).isEqualTo(HomePreviewBlock.SETTING_OFF)
    }
}
