package io.github.aedev.flow.service

import androidx.media3.common.Player
import io.github.aedev.flow.ui.screens.lockscreen.LockScreenPlayerActivity
import io.github.aedev.flow.widget.config.WidgetConfigActivity

/**
 * Whether a media service stays up when Flow is swiped away from recents. Media3's own rule keeps it
 * only while a session is playing; this also keeps it while a requested song or video is still
 * loading, so a swipe between tracks or during a stall does not end playback. A pause ends it, and so
 * does every swipe for a viewer who turned [keepPlayingAfterSwipe] off (#1468).
 */
internal fun keepsPlayingOnTaskRemoved(
    keepPlayingAfterSwipe: Boolean,
    playbackOngoing: Boolean,
    playWhenReady: Boolean,
    playbackState: Int,
    hasMedia: Boolean,
): Boolean =
    keepPlayingAfterSwipe &&
        playbackOngoing &&
        hasMedia &&
        playWhenReady &&
        playbackState != Player.STATE_IDLE &&
        playbackState != Player.STATE_ENDED

internal fun Player.keepsPlayingOnTaskRemoved(
    keepPlayingAfterSwipe: Boolean,
    playbackOngoing: Boolean,
): Boolean =
    keepsPlayingOnTaskRemoved(
        keepPlayingAfterSwipe = keepPlayingAfterSwipe,
        playbackOngoing = playbackOngoing,
        playWhenReady = playWhenReady,
        playbackState = playbackState,
        hasMedia = mediaItemCount > 0,
    )

/**
 * Activities that sit in a task of their own and never lead into the app. Android reports the end of
 * that task to every running service as a task removal, the same call a swipe from recents makes, so
 * closing the lock screen player on unlock paused the music for anyone with the swipe switch off (#1973).
 */
private val sideTaskRoots = setOf(LockScreenPlayerActivity::class.java.name, WidgetConfigActivity::class.java.name)

/** Whether the removed task, named by its root activity's class, was one of Flow's side tasks rather than the app. */
internal fun isSideTaskRemoval(rootClassName: String?): Boolean = rootClassName in sideTaskRoots
