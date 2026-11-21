package io.github.aedev.flow.ui.components.home

/** Which of a playing preview's controls show. Mute, the time bar and the badge always do, as the reference keeps them. */
internal data class PreviewControlsShown(
    val mute: Boolean,
    val captions: Boolean,
    val timeBar: Boolean,
    val badge: Boolean,
)

internal object HomePreviewControlsPolicy {
    const val AUTO_HIDE_MS = 3_000L
    const val SEEK_STEP_MS = 10_000L
    const val POSITION_TICK_MS = 250L

    /** The captions button hides [AUTO_HIDE_MS] after the last touch, never mid-scrub; the badge steps aside for the scrub preview. */
    fun shown(
        hasCaptions: Boolean,
        idleMs: Long,
        scrubbing: Boolean,
    ): PreviewControlsShown {
        val recent = scrubbing || idleMs < AUTO_HIDE_MS
        return PreviewControlsShown(mute = true, captions = hasCaptions && recent, timeBar = true, badge = !scrubbing)
    }

    /** A double tap on the left half goes back, on the right half forward; a timeline runs left to right in every language. */
    fun doubleTapSeekMs(
        x: Float,
        width: Float,
    ): Long = if (x < width / 2f) -SEEK_STEP_MS else SEEK_STEP_MS

    fun seekTarget(
        positionMs: Long,
        deltaMs: Long,
        durationMs: Long,
    ): Long {
        val target = (positionMs + deltaMs).coerceAtLeast(0L)
        return if (durationMs > 0L) target.coerceAtMost(durationMs) else target
    }

    /** Whole seconds left, rounded up so the badge never reads 0:00 while the video still plays. */
    fun remainingSeconds(
        positionMs: Long,
        durationMs: Long,
    ): Int = ((durationMs - positionMs).coerceAtLeast(0L) + MILLIS_PER_SECOND - 1).div(MILLIS_PER_SECOND).toInt()

    private const val MILLIS_PER_SECOND = 1_000L
}
