package io.github.aedev.flow.player.shorts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The reels whose slot has drawn a frame on a surface. A page drops its poster on that frame rather
 * than on "playing" or "buffering", which can flip before anything is on screen or after it already is.
 */
internal class ShortsFirstFrames {
    private val rendered = MutableStateFlow<Set<String>>(emptySet())
    val renderedVideoIds: StateFlow<Set<String>> = rendered.asStateFlow()

    fun onRendered(videoId: String?) {
        videoId ?: return
        rendered.update { it + videoId }
    }

    fun forget(videoId: String?) {
        videoId ?: return
        rendered.update { it - videoId }
    }

    fun clear() {
        rendered.value = emptySet()
    }
}
