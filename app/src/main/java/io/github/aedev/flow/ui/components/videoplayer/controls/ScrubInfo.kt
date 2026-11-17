package io.github.aedev.flow.ui.components.videoplayer.controls

import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.innertube.models.response.VideoChapter
import io.github.aedev.flow.innertube.models.response.VideoHeatmap

/** What the seek read-out can say about the moment being scrubbed to, beyond the timestamp. */
internal data class ScrubInfo(
    val chapterTitle: String? = null,
    val sponsorCategory: String? = null,
    val highlightLabel: String? = null,
) {
    val isEmpty: Boolean get() = chapterTitle == null && sponsorCategory == null && highlightLabel == null
}

/**
 * The labels that apply at [positionMs].
 *
 * SponsorBlock segments nest — a filler inside a sponsor read — so the shortest match wins: it is
 * the one whose boundaries the scrub is actually near.
 */
internal fun scrubInfoAt(
    positionMs: Long,
    chapters: List<VideoChapter>,
    sponsorSegments: List<SponsorBlockSegment>,
    heatmap: VideoHeatmap?,
): ScrubInfo {
    val positionSeconds = positionMs / 1000f
    val chapter = chapters.lastOrNull { it.startTimeSeconds <= positionSeconds }
    val sponsor =
        sponsorSegments
            .filter { it.endTime > it.startTime && positionSeconds >= it.startTime && positionSeconds <= it.endTime }
            .minByOrNull { it.endTime - it.startTime }
    return ScrubInfo(
        chapterTitle = chapter?.title?.takeIf { it.isNotBlank() },
        sponsorCategory = sponsor?.category?.takeIf { it.isNotBlank() },
        highlightLabel = heatmap?.highlightLabelAt(positionMs),
    )
}
