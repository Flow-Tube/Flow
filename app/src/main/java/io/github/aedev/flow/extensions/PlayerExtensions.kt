package io.github.aedev.flow.extensions

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import java.util.ArrayDeque

fun Player.togglePlayPause() {
    if (!playWhenReady && playbackState == Player.STATE_IDLE) {
        prepare()
    }
    playWhenReady = !playWhenReady
}

fun Player.toggleRepeatMode() {
    repeatMode =
        when (repeatMode) {
            REPEAT_MODE_OFF -> REPEAT_MODE_ALL
            REPEAT_MODE_ALL -> REPEAT_MODE_ONE
            REPEAT_MODE_ONE -> REPEAT_MODE_OFF
            else -> REPEAT_MODE_OFF
        }
}

fun Player.getQueueWindows(): List<Timeline.Window> {
    val timeline = currentTimeline
    if (timeline.isEmpty) {
        return emptyList()
    }
    val queue = ArrayDeque<Timeline.Window>()
    val queueSize = timeline.windowCount

    val currentMediaItemIndex: Int = currentMediaItemIndex
    queue.add(timeline.getWindow(currentMediaItemIndex, Timeline.Window()))

    var firstMediaItemIndex = currentMediaItemIndex
    var lastMediaItemIndex = currentMediaItemIndex
    val shuffleModeEnabled = shuffleModeEnabled
    while ((firstMediaItemIndex != C.INDEX_UNSET || lastMediaItemIndex != C.INDEX_UNSET) && queue.size < queueSize) {
        if (lastMediaItemIndex != C.INDEX_UNSET) {
            lastMediaItemIndex =
                timeline.getNextWindowIndex(lastMediaItemIndex, REPEAT_MODE_OFF, shuffleModeEnabled)
            if (lastMediaItemIndex != C.INDEX_UNSET) {
                queue.add(timeline.getWindow(lastMediaItemIndex, Timeline.Window()))
            }
        }
        if (firstMediaItemIndex != C.INDEX_UNSET && queue.size < queueSize) {
            firstMediaItemIndex =
                timeline.getPreviousWindowIndex(
                    firstMediaItemIndex,
                    REPEAT_MODE_OFF,
                    shuffleModeEnabled,
                )
            if (firstMediaItemIndex != C.INDEX_UNSET) {
                queue.addFirst(timeline.getWindow(firstMediaItemIndex, Timeline.Window()))
            }
        }
    }
    return queue.toList()
}

fun Player.getCurrentQueueIndex(): Int {
    if (currentTimeline.isEmpty) {
        return -1
    }
    var index = 0
    var currentIndex = currentMediaItemIndex
    while (currentIndex != C.INDEX_UNSET) {
        currentIndex =
            currentTimeline.getPreviousWindowIndex(
                currentIndex,
                REPEAT_MODE_OFF,
                shuffleModeEnabled,
            )
        if (currentIndex != C.INDEX_UNSET) {
            index++
        }
    }
    return index
}

val Player.mediaItems: List<MediaItem>
    get() =
        object : AbstractList<MediaItem>() {
            override val size: Int
                get() = mediaItemCount

            override fun get(index: Int): MediaItem = getMediaItemAt(index)
        }

fun Player.findNextMediaItemById(mediaId: String): MediaItem? {
    for (i in currentMediaItemIndex until mediaItemCount) {
        if (getMediaItemAt(i).mediaId == mediaId) {
            return getMediaItemAt(i)
        }
    }
    return null
}

/** Songs advance gaplessly, so a device that cannot keep that while offloaded plays them in PCM. */
fun Player.setOffloadEnabled(enabled: Boolean) {
    trackSelectionParameters =
        trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences
                    .Builder()
                    .setAudioOffloadMode(
                        if (enabled) {
                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                        } else {
                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                        },
                    ).setIsGaplessSupportRequired(true)
                    .build(),
            ).build()
}

val Player.isPlayable: Boolean
    get() = playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED

fun Player.seekForwardBy(ms: Long = 10000) {
    seekTo((currentPosition + ms).coerceAtMost(duration))
}

fun Player.seekBackwardBy(ms: Long = 10000) {
    seekTo((currentPosition - ms).coerceAtLeast(0))
}
