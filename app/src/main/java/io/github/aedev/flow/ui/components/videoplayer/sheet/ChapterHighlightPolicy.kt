package io.github.aedev.flow.ui.components.videoplayer.sheet

/** How far past the tapped chapter's start playback must get before the position takes over. */
internal const val CHAPTER_ARRIVAL_MARGIN_MS = 1_000L

/** How far before the tapped chapter's start a seek may land and still count as arriving there. */
internal const val CHAPTER_LANDING_WINDOW_MS = 5_000L

/** A chapter the viewer tapped, highlighted until the seek to [targetMs] has really landed. */
internal data class PendingChapterSeek(
    val index: Int,
    val targetMs: Long,
    val positionAtTapMs: Long,
)

/**
 * Whether the highlight goes back to following the playhead.
 *
 * Right after the seek, Media3 reports [PendingChapterSeek.targetMs] itself, and the seek can then land
 * a little before the chapter starts. Both readings keep the tapped chapter, so the one before it never
 * flashes (#2104). A reading equal to the one at the tap predates the seek and is ignored the same way.
 */
internal fun PendingChapterSeek.isReleasedAt(
    positionMs: Long,
    nextChapterStartMs: Long?,
): Boolean {
    if (positionMs == positionAtTapMs) return false
    val arrived = positionMs >= targetMs + CHAPTER_ARRIVAL_MARGIN_MS
    val reachedNextChapter = nextChapterStartMs != null && positionMs >= nextChapterStartMs
    val landedElsewhere = positionMs < targetMs - CHAPTER_LANDING_WINDOW_MS
    return arrived || reachedNextChapter || landedElsewhere
}
