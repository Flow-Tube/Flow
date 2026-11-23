package io.github.aedev.flow.player

/** Whether the queue behind a PiP window can step back or forward. */
data class PipQueueNavigation(
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

internal enum class PipControl { BACKGROUND, PREVIOUS, PLAY, PAUSE, REPLAY, RETRY, NEXT }

/** Why a video stopped, when Play would not start it again. */
internal enum class PipStop { ENDED, FAILED }

internal fun pipStopOf(
    ended: Boolean,
    failed: Boolean,
): PipStop? =
    when {
        failed -> PipStop.FAILED
        ended -> PipStop.ENDED
        else -> null
    }

internal data class PipActionSpec(
    val control: PipControl,
    val enabled: Boolean = true,
)

private const val SKIP_LAYOUT_ACTION_COUNT = 3
private const val BACKGROUND_LAYOUT_ACTION_COUNT = 2

/**
 * The window's buttons, left to right. Skip buttons need a queue and room for all three; a video
 * without a queue can offer listening in the background beside play or pause, then Next if there is
 * room. With nothing to play next that button greys out rather than going, so the others stay put.
 *
 * The system's PiP menu lays the list out in the system's direction, but playback controls stay
 * left to right in every language, so a right-to-left system gets the list reversed.
 */
internal fun pipActionSpecs(
    isPlaying: Boolean,
    navigation: PipQueueNavigation?,
    maxActions: Int,
    offerBackground: Boolean = false,
    videoHasNext: Boolean = false,
    videoStop: PipStop? = null,
    systemRtl: Boolean = false,
): List<PipActionSpec> {
    val specs = pipActionsLeftToRight(isPlaying, navigation, maxActions, offerBackground, videoHasNext, videoStop)
    return if (systemRtl) specs.reversed() else specs
}

private fun pipActionsLeftToRight(
    isPlaying: Boolean,
    navigation: PipQueueNavigation?,
    maxActions: Int,
    offerBackground: Boolean,
    videoHasNext: Boolean,
    videoStop: PipStop?,
): List<PipActionSpec> {
    val playPause = PipActionSpec(if (isPlaying) PipControl.PAUSE else PipControl.PLAY)
    if (navigation == null) {
        val middle =
            when (videoStop?.takeUnless { isPlaying }) {
                PipStop.ENDED -> PipActionSpec(PipControl.REPLAY)
                PipStop.FAILED -> PipActionSpec(PipControl.RETRY)
                null -> playPause
            }
        return videoActions(middle, maxActions, offerBackground, videoHasNext)
    }
    if (maxActions < SKIP_LAYOUT_ACTION_COUNT) return listOf(playPause)
    return listOf(
        PipActionSpec(PipControl.PREVIOUS, enabled = navigation.hasPrevious),
        playPause,
        PipActionSpec(PipControl.NEXT, enabled = navigation.hasNext),
    )
}

private fun videoActions(
    middle: PipActionSpec,
    maxActions: Int,
    offerBackground: Boolean,
    videoHasNext: Boolean,
): List<PipActionSpec> =
    when {
        !offerBackground || maxActions < BACKGROUND_LAYOUT_ACTION_COUNT -> {
            listOf(middle)
        }

        maxActions < SKIP_LAYOUT_ACTION_COUNT -> {
            listOf(PipActionSpec(PipControl.BACKGROUND), middle)
        }

        else -> {
            listOf(
                PipActionSpec(PipControl.BACKGROUND),
                middle,
                PipActionSpec(PipControl.NEXT, enabled = videoHasNext),
            )
        }
    }

/**
 * The video and Shorts receivers both hear every action. While Shorts owns the window its Next is
 * for the reel, and a video kept composed behind it must not skip too.
 */
internal fun videoHandlesPipNext(shortsNavigation: PipQueueNavigation?): Boolean = shortsNavigation == null
