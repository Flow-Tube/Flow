package io.github.aedev.flow.ui

/**
 * The restored mini player setting is about a session brought back from last time. A song already
 * running in this one keeps its mini player, for example after leaving the app and coming back.
 */
internal fun hidesRestoredMusicMiniPlayer(
    showRestored: Boolean?,
    isRestoredQueue: Boolean,
): Boolean = showRestored == false && isRestoredQueue

/** The one mini player the shell shows. Never more than one: they are not stacked. */
internal enum class ActiveMiniPlayer { None, Video, VideoBar, Music, Remote }

/**
 * A video wins over music because starting either clears the other, so both are only cached
 * together for the moment a hand-over takes. A video playing in the background gets the bar.
 * A video on the device the user drives from here gets the remote bar; music there shows in the
 * music player, which follows it.
 */
internal fun resolveActiveMiniPlayer(
    hasVideo: Boolean,
    videoVisible: Boolean,
    videoInBackground: Boolean,
    onShortsPlayer: Boolean,
    hasMusic: Boolean,
    musicSuppressed: Boolean,
    remoteSession: Boolean = false,
): ActiveMiniPlayer =
    when {
        hasVideo && onShortsPlayer -> ActiveMiniPlayer.None
        hasVideo && videoInBackground -> ActiveMiniPlayer.VideoBar
        hasVideo -> if (videoVisible) ActiveMiniPlayer.Video else ActiveMiniPlayer.None
        remoteSession -> ActiveMiniPlayer.Remote
        hasMusic && !musicSuppressed -> ActiveMiniPlayer.Music
        else -> ActiveMiniPlayer.None
    }
