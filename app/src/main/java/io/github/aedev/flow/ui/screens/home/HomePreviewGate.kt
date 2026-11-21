package io.github.aedev.flow.ui.screens.home

import io.github.aedev.flow.data.local.FeedAutoplay

/** What the device and the app say right now, everything a Home preview depends on. */
internal data class HomePreviewSignals(
    val autoplay: FeedAutoplay,
    val onWifi: Boolean = false,
    val accessibilityOn: Boolean = false,
    val casting: Boolean = false,
    val mainPlayerActive: Boolean = false,
    val batterySaver: Boolean = false,
    val lowRamDevice: Boolean = false,
    val thermalSevere: Boolean = false,
    val foreground: Boolean = false,
)

/** Why no preview may play. The first that applies is reported. */
internal enum class HomePreviewBlock {
    SETTING_OFF,
    NOT_ON_WIFI,
    ACCESSIBILITY,
    CASTING,
    MAIN_PLAYER,
    BATTERY_SAVER,
    LOW_RAM,
    THERMAL,
    NOT_FOREGROUND,
}

internal object HomePreviewGate {
    /** Null when a preview may play. */
    fun blockOf(signals: HomePreviewSignals): HomePreviewBlock? =
        when {
            signals.autoplay == FeedAutoplay.OFF -> HomePreviewBlock.SETTING_OFF
            signals.autoplay == FeedAutoplay.WIFI_ONLY && !signals.onWifi -> HomePreviewBlock.NOT_ON_WIFI
            signals.accessibilityOn -> HomePreviewBlock.ACCESSIBILITY
            signals.casting -> HomePreviewBlock.CASTING
            signals.mainPlayerActive -> HomePreviewBlock.MAIN_PLAYER
            signals.batterySaver -> HomePreviewBlock.BATTERY_SAVER
            signals.lowRamDevice -> HomePreviewBlock.LOW_RAM
            signals.thermalSevere -> HomePreviewBlock.THERMAL
            !signals.foreground -> HomePreviewBlock.NOT_FOREGROUND
            else -> null
        }
}
