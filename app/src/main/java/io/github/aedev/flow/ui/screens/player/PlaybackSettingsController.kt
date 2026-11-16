package io.github.aedev.flow.ui.screens.player

import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.stream.MergedPlaybackAssembly
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.applySelectedQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The playback settings the player screen lets the viewer change: autoplay, looping, subtitles,
 * quality, skip silence and stable volume.
 *
 * Each one is stored, pushed to the player and mirrored onto the screen from here, so the
 * preference and the player never disagree about what is on. Autoplay and looping exclude each
 * other, which is why both live on one class rather than on their own.
 */
internal class PlaybackSettingsController(
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    private val playerManager: EnhancedPlayerManager,
    private val playerPreferences: PlayerPreferences,
    private val scope: CoroutineScope,
) {
    fun collectAutoplayPreference() {
        playerPreferences.autoplayEnabled
            .distinctUntilChanged()
            .onEach(::applyAutoplayPreference)
            .launchIn(scope)
    }

    fun toggleAutoplay(enabled: Boolean) {
        scope.launch {
            val resolvedEnabled =
                enabled &&
                    !playerManager.playerState.value.isLooping
            playerPreferences.setAutoplayEnabled(resolvedEnabled)
            uiState.value = uiState.value.copy(autoplayEnabled = resolvedEnabled)
            uiState.value.cachedVideo?.id?.let { videoId ->
                playerManager.setAutoplayCandidates(
                    sourceVideoId = videoId,
                    videos = uiState.value.relatedVideos,
                    enabled = resolvedEnabled,
                )
            }
        }
    }

    fun toggleLoop(enabled: Boolean) = playerManager.toggleLoop(enabled)

    fun switchQuality(quality: VideoQuality) {
        val state = uiState.value
        scope.launch {
            val streams =
                MergedPlaybackAssembly.selectQualityStreams(
                    innerTubeVideoFormats = state.innerTubeVideoFormats,
                    innerTubeAudioFormats = state.innerTubeAudioFormats,
                    quality = quality,
                    preferredAudioLanguage = playerPreferences.preferredAudioLanguage.first(),
                    preferredCodecKey = playerPreferences.videoCodecPriority.first(),
                )

            uiState.value = state.applySelectedQuality(quality, streams.first, streams.second)
        }
    }

    fun toggleSkipSilence(isEnabled: Boolean) {
        playerManager.toggleSkipSilence(isEnabled)
    }

    fun toggleVolumeNormalization(isEnabled: Boolean) {
        playerManager.toggleVolumeNormalization(isEnabled)
    }

    private suspend fun applyAutoplayPreference(autoplay: Boolean) {
        uiState.update { it.copy(autoplayEnabled = autoplay) }
        uiState.value.cachedVideo?.id?.let { videoId ->
            playerManager.setAutoplayCandidates(
                sourceVideoId = videoId,
                videos = uiState.value.relatedVideos,
                enabled = autoplay,
            )
        }
    }
}
