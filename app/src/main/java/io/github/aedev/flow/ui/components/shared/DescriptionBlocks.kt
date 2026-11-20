package io.github.aedev.flow.ui.components.shared

import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.RichTextTarget
import io.github.aedev.flow.data.model.slice

/** A description cut into runs of text and the video cards YouTube shows between them. */
internal sealed interface DescriptionBlock<out T> {
    data class Text<T>(
        val text: T,
    ) : DescriptionBlock<T>

    data class VideoCard(
        val videoId: String,
        val startSeconds: Long?,
        val title: String,
    ) : DescriptionBlock<Nothing>
}

internal fun <T, R> DescriptionBlock<T>.mapText(transform: (T) -> R): DescriptionBlock<R> =
    when (this) {
        is DescriptionBlock.Text -> DescriptionBlock.Text(transform(text))
        is DescriptionBlock.VideoCard -> this
    }

/**
 * Lifts every link to another video that ends its line out into a card, as YouTube's app does;
 * whatever precedes it on the line, usually the video's name as a label, stays text above the card.
 * A link with more text after it on its line is part of a sentence and stays inline, and a link
 * back to [ownVideoId] is a position in this video, never a card.
 *
 * A description with no such link comes back as one untouched block.
 */
internal fun RichText.descriptionBlocks(ownVideoId: String?): List<DescriptionBlock<RichText>> {
    val blocks = mutableListOf<DescriptionBlock<RichText>>()
    var cursor = 0

    fun addText(
        from: Int,
        to: Int,
    ) {
        var start = from
        var end = to
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start < end) blocks += DescriptionBlock.Text(slice(start, end))
    }

    spans.sortedBy { it.start }.forEach { span ->
        val target = span.target as? RichTextTarget.Video ?: return@forEach
        if (target.videoId == ownVideoId || span.start < cursor) return@forEach
        val lineEnd = text.indexOf('\n', span.end).let { if (it < 0) text.length else it }
        if (text.substring(span.end, lineEnd).isNotBlank()) return@forEach
        val title = videoCardTitle(text.substring(span.start, span.end))
        if (title.isEmpty()) return@forEach
        addText(cursor, span.start)
        blocks += DescriptionBlock.VideoCard(videoId = target.videoId, startSeconds = target.startSeconds, title = title)
        cursor = minOf(lineEnd + 1, text.length)
    }
    if (blocks.isEmpty()) return listOf(DescriptionBlock.Text(this))
    addText(cursor, text.length)
    return blocks
}

/**
 * The server prints a video link as `•` and the title cut short with `...`, padded with
 * non-breaking spaces; the card ellipsizes the title itself.
 */
internal fun videoCardTitle(linkText: String): String =
    linkText
        .trim()
        .removePrefix("•")
        .trim()
        .removeSuffix("...")
        .removeSuffix("…")
        .trim()
        .replace(' ', ' ')
