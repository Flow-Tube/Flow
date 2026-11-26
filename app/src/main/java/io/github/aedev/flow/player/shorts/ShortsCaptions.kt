package io.github.aedev.flow.player.shorts

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.aedev.flow.player.state.SubtitleOption
import io.github.aedev.flow.player.stream.ResolvedCaption
import io.github.aedev.flow.player.subtitle.SubtitleAutoPolicy
import io.github.aedev.flow.player.subtitle.SubtitleTracks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The captions of the reel on screen: its tracks and the one showing, or null while they are off. */
data class ShortsCaptionState(
    val videoId: String? = null,
    val options: List<SubtitleOption> = emptyList(),
    val selected: SubtitleOption? = null,
)

/**
 * What the Shorts caption setting holds: follow the video player's automatic captions, off, or
 * on in the language last picked for a reel.
 */
internal object ShortsCaptionChoice {
    const val FOLLOW = ""
    const val OFF = "off"

    fun policy(
        choice: String,
        autoEnable: Boolean,
        preferredLanguage: String,
    ): SubtitleAutoPolicy =
        when (choice) {
            FOLLOW -> SubtitleAutoPolicy(autoEnable, preferredLanguage)
            OFF -> SubtitleAutoPolicy.OFF
            else -> SubtitleAutoPolicy(enabled = true, languageTag = choice)
        }

    /** What picking [option] in the sheet stores; null turns captions off. A track with no language leaves the choice to follow. */
    fun stored(option: SubtitleOption?): String = if (option == null) OFF else option.language.ifBlank { FOLLOW }
}

/**
 * Captions for the reel on screen, through the same [SubtitleTracks] the video player uses. Every
 * pooled player starts with text switched off and only the one showing the reel on screen has it
 * switched on, so the neighbours buffering either side never fetch a caption track.
 */
@OptIn(UnstableApi::class)
internal class ShortsCaptions {
    private val tracks = SubtitleTracks()
    private var player: Player? = null
    private var videoId: String? = null

    private val _state = MutableStateFlow(ShortsCaptionState())
    val state: StateFlow<ShortsCaptionState> = _state.asStateFlow()

    /** [player] now holds the reel on screen, or null while no slot holds it yet. */
    fun show(
        player: Player?,
        videoId: String?,
        captions: List<ResolvedCaption>,
    ) {
        val previous = this.player
        if (previous != null && previous !== player) switchOff(previous)
        this.player = player
        this.videoId = videoId
        tracks.load(videoId, captions, acceptsEmbedded = false)
        apply()
    }

    fun setPolicy(policy: SubtitleAutoPolicy) {
        if (tracks.setPolicy(policy)) apply()
    }

    /** Shows the track whose option has [url] on this reel; null turns captions off for it. */
    fun select(url: String?) {
        tracks.select(url)
        apply()
    }

    fun onTracksChanged(player: Player) {
        if (player === this.player) apply()
    }

    fun clear() {
        player = null
        videoId = null
        tracks.load(null, emptyList(), acceptsEmbedded = false)
        _state.value = ShortsCaptionState()
    }

    private fun apply() {
        val current = player
        val selector = current?.selector()
        if (current != null && selector != null) tracks.apply(selector, current.currentTracks)
        _state.value = ShortsCaptionState(videoId, tracks.options, tracks.selected)
    }

    private fun switchOff(player: Player) {
        val selector = player.selector() ?: return
        selector.setParameters(selector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true))
    }

    private fun Player.selector(): DefaultTrackSelector? = (this as? ExoPlayer)?.trackSelector as? DefaultTrackSelector
}
