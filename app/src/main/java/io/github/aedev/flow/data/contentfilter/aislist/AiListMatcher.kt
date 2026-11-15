package io.github.aedev.flow.data.contentfilter.aislist

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.ArtistDetails
import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack

enum class AiVerdict { NONE, WARN, BLOCK }

/** One list's entries as lookup sets: normalized handles without `@`, and `UC…` channel IDs. */
internal class AiListSets(
    val handles: Set<String>,
    val ids: Set<String>,
) {
    companion object {
        val EMPTY = AiListSets(emptySet(), emptySet())
    }
}

/**
 * The answer for every surface, rebuilt whenever a list, a switch, the allowlist or the
 * subscriptions change; a lookup never touches the disk or the network.
 *
 * A subscribed or allowed channel is never flagged. With hiding off, a block-list channel is still
 * labelled when labels are on: it is the more certain of the two lists.
 *
 * Video surfaces use AiSList only. Music surfaces also use the AI music artist list, which has
 * IDs only and no warn tier, so it can only hide.
 */
class AiListMatcher internal constructor(
    private val blockEnabled: Boolean,
    private val warnEnabled: Boolean,
    private val block: AiListSets,
    private val warn: AiListSets,
    private val exemptChannelIds: Set<String>,
    private val exemptHandles: Set<String>,
    private val index: ChannelHandleIndex?,
    private val musicEnabled: Boolean = false,
    private val music: AiListSets = AiListSets.EMPTY,
) {
    val isActive: Boolean =
        (blockEnabled || warnEnabled) &&
            (block.handles.isNotEmpty() || block.ids.isNotEmpty() || warn.handles.isNotEmpty() || warn.ids.isNotEmpty())

    /** True when any music lookup can flag something: AiSList IDs or the music artist list. */
    val isMusicActive: Boolean = isActive || (musicEnabled && music.ids.isNotEmpty())

    fun verdict(video: Video): AiVerdict = verdict(video.channelId, video.channelHandle)

    fun blocks(video: Video): Boolean = verdict(video) == AiVerdict.BLOCK

    fun verdict(
        channelId: String,
        handle: String?,
    ): AiVerdict {
        if (!isActive) return AiVerdict.NONE
        if (channelId.isNotBlank() && channelId in exemptChannelIds) return AiVerdict.NONE
        val id = channelId.takeIf { it.isChannelId() }
        val normalized = (handle?.takeIf(String::isNotBlank) ?: channelId.takeIf { it.startsWith('@') })?.let(AiHandle::normalize)
        if (id != null && normalized != null) index?.learn(id, normalized)
        val key = normalized ?: id?.let { index?.handleFor(it) }
        if (key != null && key in exemptHandles) return AiVerdict.NONE

        val onBlock = (key != null && key in block.handles) || (id != null && id in block.ids)
        if (onBlock && blockEnabled) return AiVerdict.BLOCK
        val onWarn = onBlock || (key != null && key in warn.handles) || (id != null && id in warn.ids)
        return if (onWarn && warnEnabled) AiVerdict.WARN else AiVerdict.NONE
    }

    /**
     * A music row: its uploader and every credited artist. One listed, non-exempt ID is enough, so
     * a collaboration with a listed artist is flagged too.
     */
    fun verdict(track: MusicTrack): AiVerdict {
        if (!isMusicActive) return AiVerdict.NONE
        return musicVerdict(track.artists.mapNotNull { it.id } + track.channelId)
    }

    fun hides(track: MusicTrack): Boolean = verdict(track) == AiVerdict.BLOCK

    /** An album or playlist card, by its structured author. */
    fun hides(album: MusicPlaylist): Boolean = hidesMusic(listOfNotNull(album.authorId))

    fun hides(artist: ArtistDetails): Boolean = hidesMusic(listOf(artist.channelId))

    /** Any music item by the IDs it credits, such as a search result's artists. */
    fun hidesMusic(ids: List<String>): Boolean = isMusicActive && musicVerdict(ids) == AiVerdict.BLOCK

    /** An artist page: which list it is on, for the banner. */
    fun artistVerdict(channelId: String): AiVerdict = if (isMusicActive) musicVerdict(listOf(channelId)) else AiVerdict.NONE

    private fun musicVerdict(ids: List<String>): AiVerdict {
        var result = AiVerdict.NONE
        for (raw in ids) {
            val id = raw.takeIf { it.isChannelId() } ?: continue
            if (id in exemptChannelIds) continue
            if (musicEnabled && id in music.ids) return AiVerdict.BLOCK
            val listed = if (isActive) verdict(id, null) else AiVerdict.NONE
            if (listed == AiVerdict.BLOCK) return AiVerdict.BLOCK
            if (listed == AiVerdict.WARN) result = AiVerdict.WARN
        }
        return result
    }

    /** Records a pair seen outside a video row, such as a channel search result or a channel page. */
    fun learn(
        channelId: String,
        handle: String?,
    ) {
        if (!isActive || !channelId.isChannelId()) return
        val normalized = handle?.let(AiHandle::normalize) ?: return
        index?.learn(channelId, normalized)
    }

    companion object {
        val NONE =
            AiListMatcher(
                blockEnabled = false,
                warnEnabled = false,
                block = AiListSets.EMPTY,
                warn = AiListSets.EMPTY,
                exemptChannelIds = emptySet(),
                exemptHandles = emptySet(),
                index = null,
            )

        private const val CHANNEL_ID_LENGTH = 24

        private fun String.isChannelId(): Boolean = length == CHANNEL_ID_LENGTH && startsWith("UC")
    }
}
