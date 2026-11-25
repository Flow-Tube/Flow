package io.github.aedev.flow.player.subtitle

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.aedev.flow.player.media.MediaLoader
import io.github.aedev.flow.player.state.SubtitleOption
import io.github.aedev.flow.player.state.SubtitleOrigin
import io.github.aedev.flow.player.stream.ResolvedCaption
import io.github.aedev.flow.player.stream.StreamProcessor

/**
 * The one owner of which caption track plays. The screens only read [options] and [selected] and
 * ask for a track by its url, so the CC button can never disagree with the player.
 *
 * A pick made for a video lasts until another video loads, including across a re-prepare of the
 * same video, whose online tracks come back under new signed urls. Until someone picks, the track
 * follows [SubtitleChoice.automatic], so it settles by itself once the tracks or the policy arrive.
 */
@OptIn(UnstableApi::class)
internal class SubtitleTracks {
    private var videoId: String? = null
    private var acceptsEmbedded = false
    private var embedded: List<SubtitleOption> = emptyList()
    private var pick: Pick? = null
    private var refreshPending = false

    /** The caption files and URLs merged into the media source, in the order of their track ids. */
    var captions: List<ResolvedCaption> = emptyList()
        private set

    var policy: SubtitleAutoPolicy = SubtitleAutoPolicy.OFF
        private set

    /** The playing audio track's id; the list shows only the captions YouTube lists for it. */
    private var audioTrackId: String? = null

    val options: List<SubtitleOption>
        get() = StreamProcessor.toSubtitleOptions(AudioCaptions.visible(captions, audioTrackId)) + embedded

    /** A pick the playing audio track does not list falls back to the automatic track. */
    val selected: SubtitleOption?
        get() {
            val options = options
            return when (val current = pick) {
                Pick.Off -> null
                is Pick.Track -> options.firstOrNull { identity(it) == current.identity } ?: automatic(options)
                null -> automatic(options)
            }
        }

    private fun automatic(options: List<SubtitleOption>): SubtitleOption? {
        val recommended =
            AudioCaptions.recommended(captions, audioTrackId)?.let { caption -> options.firstOrNull { it.url == caption.url } }
        return SubtitleChoice.automatic(options, policy, recommended)
    }

    /** @param acceptsEmbedded true for a file on the device, whose own text tracks join the list. */
    fun load(
        videoId: String?,
        captions: List<ResolvedCaption>,
        acceptsEmbedded: Boolean,
    ) {
        if (videoId != this.videoId) {
            pick = null
            embedded = emptyList()
        }
        this.videoId = videoId
        this.captions = captions
        this.acceptsEmbedded = acceptsEmbedded
        if (!acceptsEmbedded) embedded = emptyList()
    }

    /** Picks the track whose option has [url]; null turns captions off. Unknown urls are ignored. */
    fun select(url: String?) {
        pick =
            if (url == null) {
                Pick.Off
            } else {
                options.firstOrNull { it.url == url }?.let { Pick.Track(identity(it)) } ?: return
            }
    }

    /** True when the change can move the track on screen or change the list. */
    fun setAudioTrack(id: String?): Boolean {
        if (id == audioTrackId) return false
        audioTrackId = id
        return captions.any { it.audioLinks.isNotEmpty() }
    }

    /** True when the change can move the track on screen. */
    fun setPolicy(policy: SubtitleAutoPolicy): Boolean {
        if (policy == this.policy) return false
        this.policy = policy
        return pick == null
    }

    /** Lists the text tracks inside a device file; true when the list changed. */
    fun onTracksChanged(tracks: Tracks): Boolean {
        if (!acceptsEmbedded) return false
        val found =
            textTracks(tracks)
                .filter { MediaLoader.subtitleTrackIndex(it.format.id) == null }
                .distinctBy { it.key }
                .map { it.toOption() }
                .toList()
        if (found == embedded) return false
        embedded = found
        return true
    }

    /**
     * Reloads the track on screen: text is switched off, and [apply] switches it back on once the
     * player reports it off. A renderer only rereads its cues when it starts again.
     */
    fun refresh(selector: DefaultTrackSelector) {
        if (selected == null) return
        refreshPending = true
        selector.setParameters(selector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build())
    }

    /** Points the track selector at [selected], or switches text off. */
    fun apply(
        selector: DefaultTrackSelector,
        tracks: Tracks,
    ) {
        if (refreshPending) {
            if (tracks.isTypeSelected(C.TRACK_TYPE_TEXT)) return
            refreshPending = false
        }
        val option = selected
        val builder = selector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (option == null) {
            builder.setPreferredTextLanguage(null).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            val key = trackKeyOf(option)
            val available = textTracks(tracks).toList()
            val match =
                available.firstOrNull { it.key == key }
                    ?: available.firstOrNull {
                        it.format.language.equals(option.language, ignoreCase = true) &&
                            it.isMachineText == option.isAutoGenerated
                    }
            if (match != null) {
                builder.setOverrideForType(TrackSelectionOverride(match.group.mediaTrackGroup, match.index))
            } else {
                builder.setPreferredTextLanguage(option.language.takeIf { it.isNotBlank() })
            }
        }
        selector.setParameters(builder.build())
    }

    private fun trackKeyOf(option: SubtitleOption): String =
        when (option.origin) {
            SubtitleOrigin.EMBEDDED -> option.url
            else -> captionKey(captions.indexOfFirst { it.url == option.url })
        }

    private fun identity(option: SubtitleOption): String =
        when (option.origin) {
            SubtitleOrigin.ONLINE -> "online|${option.trackId ?: option.language}|${option.isAutoGenerated}|${option.isTranslated}"
            else -> option.url
        }

    private sealed interface Pick {
        data object Off : Pick

        data class Track(
            val identity: String,
        ) : Pick
    }

    private class TextTrack(
        val group: Tracks.Group,
        val index: Int,
        val format: Format,
        position: String,
    ) {
        val key: String =
            MediaLoader.subtitleTrackIndex(format.id)?.let(::captionKey)
                ?: "$EMBEDDED_PREFIX${format.id ?: position}"
        val isMachineText: Boolean get() = format.roleFlags and C.ROLE_FLAG_TRANSCRIBES_DIALOG != 0

        fun toOption(): SubtitleOption {
            val language = format.language?.takeIf { it.isNotBlank() && it != UNDETERMINED_LANGUAGE }.orEmpty()
            return SubtitleOption(
                url = key,
                language = language,
                label =
                    format.label?.takeIf { it.isNotBlank() }
                        ?: language
                            .takeIf { it.isNotBlank() }
                            ?.let { StreamProcessor.localizedLanguageName(it) ?: it }
                            .orEmpty(),
                isAutoGenerated = false,
                origin = SubtitleOrigin.EMBEDDED,
                isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
            )
        }
    }

    private fun textTracks(tracks: Tracks): Sequence<TextTrack> =
        tracks.groups.asSequence().withIndex().filter { it.value.type == C.TRACK_TYPE_TEXT }.flatMap { (groupIndex, group) ->
            (0 until group.length).asSequence().map { index ->
                TextTrack(group, index, group.getTrackFormat(index), position = "$groupIndex.$index")
            }
        }

    private companion object {
        const val EMBEDDED_PREFIX = "embedded:"
        const val CAPTION_PREFIX = "caption:"

        fun captionKey(index: Int): String = "$CAPTION_PREFIX$index"

        const val UNDETERMINED_LANGUAGE = "und"
    }
}
