package io.github.aedev.flow.player

/** Off, then the whole queue, then the current item, then off again. */
fun RepeatMode.next(): RepeatMode =
    when (this) {
        RepeatMode.OFF -> RepeatMode.ALL
        RepeatMode.ALL -> RepeatMode.ONE
        RepeatMode.ONE -> RepeatMode.OFF
    }

/** The video player's queue loop and video loop read as one mode; looping the video wins. */
fun videoRepeatMode(
    queueLoop: Boolean,
    videoLoop: Boolean,
): RepeatMode =
    when {
        videoLoop -> RepeatMode.ONE
        queueLoop -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }
