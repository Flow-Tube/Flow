package io.github.aedev.flow.ui.screens.player.state

/*
 * Where the player sheet goes when a new video arrives. A video that starts by itself while the
 * viewer is in the mini player stays there, whether it came from a queue or from related autoplay,
 * and a restored or resumed session stays there too. Anything else opens the full player.
 *
 * The overlay host (which owns the sheet's visibility) and the player host (which reacts to the load)
 * both ask this one question, so they can never disagree about the same video.
 */

/** What the sheet does for a video that just became current. */
internal enum class SheetOnNewVideo {
    COLLAPSE,
    EXPAND,
    OPEN,
}

/**
 * [autoAdvancedInMiniPlayer] is true when the player reported an automatic advance while the sheet
 * was collapsed; [isCollapsed] is the sheet now, so a viewer who opened it in between is not
 * pulled back down.
 */
internal fun staysInMiniPlayer(
    isRestoredSession: Boolean,
    resumedInMiniPlayer: Boolean,
    autoAdvancedInMiniPlayer: Boolean,
    isCollapsed: Boolean,
): Boolean = isRestoredSession || resumedInMiniPlayer || (autoAdvancedInMiniPlayer && isCollapsed)

internal fun sheetOnNewVideo(
    isRestoredSession: Boolean,
    resumedInMiniPlayer: Boolean,
    autoAdvancedInMiniPlayer: Boolean,
    isCollapsed: Boolean,
    wasExpanded: Boolean,
): SheetOnNewVideo {
    val stays = staysInMiniPlayer(isRestoredSession, resumedInMiniPlayer, autoAdvancedInMiniPlayer, isCollapsed)
    return when {
        stays -> SheetOnNewVideo.COLLAPSE
        wasExpanded -> SheetOnNewVideo.EXPAND
        else -> SheetOnNewVideo.OPEN
    }
}

internal fun expandsOnLoad(
    isLoading: Boolean,
    isRestoredSession: Boolean,
    resumedInMiniPlayer: Boolean,
    autoAdvancedInMiniPlayer: Boolean,
    isCollapsed: Boolean,
): Boolean =
    isLoading &&
        !staysInMiniPlayer(isRestoredSession, resumedInMiniPlayer, autoAdvancedInMiniPlayer, isCollapsed)
