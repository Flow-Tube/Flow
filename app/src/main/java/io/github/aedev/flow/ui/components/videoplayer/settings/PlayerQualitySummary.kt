package io.github.aedev.flow.ui.components.videoplayer.settings

import io.github.aedev.flow.player.QualityOption

/** The resolution part of an option's label: "1080p60" from "1080p60 VP9". */
internal fun QualityOption.resolutionLabel(): String = label.substringBefore(' ').takeIf { it.isNotBlank() } ?: "${height}p"

/**
 * The resolution the quality row shows (#1725): the picked option's, or in Auto the one Auto is
 * playing. Null in Auto until a stream has played, when the row shows plain "Auto".
 */
internal fun qualitySummaryResolution(
    currentQuality: Int,
    currentQualityKey: String?,
    effectiveQuality: Int,
    options: List<QualityOption>,
): String? =
    if (currentQuality == 0) {
        options.firstOrNull { it.height != 0 && it.height == effectiveQuality }?.resolutionLabel()
            ?: effectiveQuality.takeIf { it > 0 }?.let { "${it}p" }
    } else {
        (
            options.firstOrNull { it.streamKey != null && it.streamKey == currentQualityKey }
                ?: options.firstOrNull { it.height == currentQuality }
        )?.resolutionLabel() ?: "${currentQuality}p"
    }
