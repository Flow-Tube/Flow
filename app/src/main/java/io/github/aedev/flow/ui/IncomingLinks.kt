package io.github.aedev.flow.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import io.github.aedev.flow.utils.parseYouTubeLink

/**
 * What an intent from outside asks the app shell to open: a video or short, a page route, or the
 * music player. The activity fills it from each intent and the shell consumes it once handled.
 */
internal class IncomingLinks {
    private val _videoId = mutableStateOf<String?>(null)
    val videoId: State<String?> = _videoId

    private val _isShort = mutableStateOf(false)
    val isShort: State<Boolean> = _isShort

    /** The video came through Share rather than a tapped link, so the shell may ask before playing it. */
    private val _isShared = mutableStateOf(false)
    val isShared: State<Boolean> = _isShared

    private val _musicPlayerRequest = mutableIntStateOf(0)
    val musicPlayerRequest: State<Int> = _musicPlayerRequest

    private val _route = mutableStateOf<String?>(null)
    val route: State<String?> = _route

    /** A video shared to Download with Flow, which opens the download menu instead of the player. */
    private val _downloadVideoId = mutableStateOf<String?>(null)
    val downloadVideoId: State<String?> = _downloadVideoId

    fun openVideo(
        videoId: String,
        isShort: Boolean = false,
        isShared: Boolean = false,
    ) {
        _isShort.value = isShort
        _isShared.value = isShared
        _videoId.value = videoId
    }

    fun openRoute(route: String?) {
        _route.value = route
    }

    fun openMusicPlayer() {
        consumeVideo()
        _musicPlayerRequest.intValue += 1
    }

    fun consumeVideo() {
        _videoId.value = null
        _isShort.value = false
        _isShared.value = false
    }

    fun consumeRoute() {
        _route.value = null
    }

    fun consumeDownload() {
        _downloadVideoId.value = null
    }

    /** A link shared to Download with Flow; false when it names no video or short. */
    fun openDownload(text: String): Boolean {
        _downloadVideoId.value =
            when (val destination = parseYouTubeLink(text)?.let(::linkDestination)) {
                is LinkDestination.Video -> destination.videoId
                is LinkDestination.Short -> destination.videoId
                is LinkDestination.Page, null -> return false
            }
        return true
    }

    /**
     * Opens a YouTube link from another app; false when Flow has no page for it. A recreated activity
     * gets its intent again with its back stack restored, so then only playback is re-requested, and
     * not even that for a shared video, whose choices were already made or are still on screen.
     */
    fun openLink(
        text: String,
        isShared: Boolean,
        isRestoring: Boolean,
    ): Boolean {
        when (val destination = parseYouTubeLink(text)?.let(::linkDestination)) {
            is LinkDestination.Video -> if (!(isShared && isRestoring)) openVideo(destination.videoId, isShared = isShared)
            is LinkDestination.Short -> openVideo(destination.videoId, isShort = true)
            is LinkDestination.Page -> if (!isRestoring) openRoute(destination.route)
            null -> return false
        }
        return true
    }
}
