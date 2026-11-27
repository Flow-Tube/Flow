package io.github.aedev.flow.ui.screens.player

import io.github.aedev.flow.innertube.models.MusicResponsiveListItemRenderer.Companion.MUSIC_VIDEO_TYPE_PODCAST_EPISODE
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState

/** What the player knows about a video when it picks a starting speed. */
internal data class PlaybackSpeedContext(
    val videoId: String?,
    val channelId: String?,
    val musicVideoType: String? = null,
    val knownMusic: Boolean = false,
) {
    companion object {
        val Unknown = PlaybackSpeedContext(videoId = null, channelId = null)
    }
}

internal sealed interface SpeedDecision {
    /** Applies to this video only; the speed it replaced comes back on the next one. */
    data class Override(
        val speed: Float,
    ) : SpeedDecision

    data class Remembered(
        val speed: Float,
    ) : SpeedDecision

    data object Keep : SpeedDecision
}

internal object PlaybackSpeedPolicy {
    private const val MUSIC_CATEGORY = "Music"

    fun decide(
        isMusic: Boolean,
        channelSpeed: Float?,
        musicAtNormalSpeed: Boolean,
        rememberSpeed: Boolean,
        rememberedSpeed: Float,
    ): SpeedDecision =
        when {
            channelSpeed != null -> SpeedDecision.Override(channelSpeed)
            isMusic && musicAtNormalSpeed -> SpeedDecision.Override(1f)
            rememberSpeed -> SpeedDecision.Remembered(rememberedSpeed)
            else -> SpeedDecision.Keep
        }

    /**
     * Categories come from an English-locale request, so the name is stable. A podcast episode carries
     * a music video type and plays in the music player, but it is talk, so its speed is the listener's.
     */
    fun isMusic(
        musicVideoType: String?,
        category: String?,
        openedAsMusic: Boolean,
    ): Boolean =
        musicVideoType != MUSIC_VIDEO_TYPE_PODCAST_EPISODE &&
            (openedAsMusic || !musicVideoType.isNullOrBlank() || category.equals(MUSIC_CATEGORY, ignoreCase = true))
}

/** What the screen already knows about [videoId], with what the stream response added on top. */
internal fun VideoPlayerUiState.speedContextFor(
    videoId: String,
    musicVideoType: String? = null,
    channelId: String? = null,
): PlaybackSpeedContext {
    val cached = cachedVideo?.takeIf { it.id == videoId }
    return PlaybackSpeedContext(
        videoId = videoId,
        channelId = channelId ?: cached?.channelId,
        musicVideoType = musicVideoType,
        knownMusic = cached?.isMusic == true,
    )
}
