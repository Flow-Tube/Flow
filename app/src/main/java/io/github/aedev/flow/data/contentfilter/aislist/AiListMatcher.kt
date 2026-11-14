package io.github.aedev.flow.data.contentfilter.aislist

import io.github.aedev.flow.data.model.Video

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
 * The answer for every surface, rebuilt whenever the list, a switch, the allowlist or the
 * subscriptions change; a lookup never touches the disk or the network.
 *
 * A subscribed or allowed channel is never flagged. With hiding off, a block-list channel is still
 * labelled when labels are on: it is the more certain of the two lists.
 */
class AiListMatcher internal constructor(
    private val blockEnabled: Boolean,
    private val warnEnabled: Boolean,
    private val block: AiListSets,
    private val warn: AiListSets,
    private val exemptChannelIds: Set<String>,
    private val exemptHandles: Set<String>,
    private val index: ChannelHandleIndex?,
) {
    val isActive: Boolean =
        (blockEnabled || warnEnabled) &&
            (block.handles.isNotEmpty() || block.ids.isNotEmpty() || warn.handles.isNotEmpty() || warn.ids.isNotEmpty())

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
