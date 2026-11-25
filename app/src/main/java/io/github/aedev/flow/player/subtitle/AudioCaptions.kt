package io.github.aedev.flow.player.subtitle

import io.github.aedev.flow.player.stream.CaptionAudioLink
import io.github.aedev.flow.player.stream.ResolvedCaption

/**
 * The caption tracks YouTube lists for the audio track that is playing, as its own player does:
 * each audio track names its captions and the one to show, and says whether captions start on.
 */
internal object AudioCaptions {
    /**
     * The tracks to offer while [audioTrackId] plays. A track YouTube tied to no audio track, such
     * as a machine translation or a file, is always offered.
     */
    fun visible(
        captions: List<ResolvedCaption>,
        audioTrackId: String?,
    ): List<ResolvedCaption> {
        val linkOf = activeLink(captions, audioTrackId) ?: return captions
        return captions.filter { it.audioLinks.isEmpty() || linkOf(it) != null }
    }

    /**
     * The track YouTube turns on by itself for [audioTrackId], such as the original language's
     * captions under a dub, or null when that audio track starts with captions off.
     */
    fun recommended(
        captions: List<ResolvedCaption>,
        audioTrackId: String?,
    ): ResolvedCaption? {
        val linkOf = activeLink(captions, audioTrackId) ?: return null
        return captions.firstOrNull { caption ->
            linkOf(caption)?.let { it.isDefaultCaption && it.initialState.turnsCaptionsOn } == true
        }
    }

    /**
     * How a caption is tied to the audio track playing: by its id, else by YouTube's default audio
     * track when the id is unknown or not listed. Null when YouTube gave no mapping at all.
     */
    private fun activeLink(
        captions: List<ResolvedCaption>,
        audioTrackId: String?,
    ): ((ResolvedCaption) -> CaptionAudioLink?)? {
        val links = captions.flatMap { it.audioLinks }
        if (links.isEmpty()) return null
        if (audioTrackId != null && links.any { it.audioTrackId == audioTrackId }) {
            return { caption -> caption.audioLinks.firstOrNull { it.audioTrackId == audioTrackId } }
        }
        if (links.none { it.isDefaultAudioTrack }) return null
        return { caption -> caption.audioLinks.firstOrNull { it.isDefaultAudioTrack } }
    }
}
