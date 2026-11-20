package io.github.aedev.flow.data.model

/**
 * A stretch of text YouTube marked as actionable, with the target it resolved to.
 *
 * The ranges come from the response's own `commandRuns`, so a timestamp carries the seconds the
 * endpoint names rather than a number recovered from the printed text, and a link carries the real
 * destination rather than the `redirect?q=` wrapper YouTube prints.
 */
data class RichTextSpan(
    val start: Int,
    val length: Int,
    val target: RichTextTarget,
) {
    val end: Int get() = start + length
}

sealed interface RichTextTarget {
    /** A position inside the video the text belongs to. */
    data class Timestamp(
        val seconds: Long,
    ) : RichTextTarget

    /** Another video, optionally at a position. */
    data class Video(
        val videoId: String,
        val startSeconds: Long? = null,
    ) : RichTextTarget

    data class Channel(
        val browseId: String,
    ) : RichTextTarget

    data class Hashtag(
        val tag: String,
    ) : RichTextTarget

    data class Url(
        val url: String,
    ) : RichTextTarget
}

/**
 * An image YouTube places in the text.
 *
 * A [length] of zero inserts the image without consuming any text — how a platform icon is put in
 * front of a social link; anything larger replaces that range, which is how a custom emoji arrives.
 */
data class RichTextEmoji(
    val start: Int,
    val length: Int,
    val imageUrl: String,
    val label: String,
)

/** A range YouTube marks with a rounded tint, like the chip behind a social link. */
data class RichTextHighlight(
    val start: Int,
    val length: Int,
) {
    val end: Int get() = start + length
}

/**
 * A range the writer set in another face: YouTube turns `*bold*`, `_italic_` and `-strike-` in a
 * comment into these. Server colours and fonts are left out on purpose; the theme owns those.
 */
data class RichTextStyle(
    val start: Int,
    val length: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
) {
    val end: Int get() = start + length
}

/**
 * Text plus the spans and emoji the server described for it.
 *
 * Null on any surface still fed by the extractor, which hands over HTML and leaves the caller to
 * recover structure with regex.
 */
data class RichText(
    val text: String,
    val spans: List<RichTextSpan> = emptyList(),
    val emojis: List<RichTextEmoji> = emptyList(),
    val highlights: List<RichTextHighlight> = emptyList(),
    val styles: List<RichTextStyle> = emptyList(),
) {
    val hasTimestamp: Boolean get() = spans.any { it.target is RichTextTarget.Timestamp }
}

/**
 * The part of this text between [from] and [to], with every range clipped to it and moved to its
 * start. An emoji that replaces text is kept only whole; an inserted one belongs to the text after it.
 */
fun RichText.slice(
    from: Int,
    to: Int,
): RichText {
    val start = from.coerceIn(0, text.length)
    val end = to.coerceIn(start, text.length)

    fun <T> clip(
        rangeStart: Int,
        rangeEnd: Int,
        make: (Int, Int) -> T,
    ): T? {
        val clippedStart = maxOf(rangeStart, start)
        val clippedEnd = minOf(rangeEnd, end)
        return if (clippedStart < clippedEnd) make(clippedStart - start, clippedEnd - clippedStart) else null
    }
    return RichText(
        text = text.substring(start, end),
        spans = spans.mapNotNull { span -> clip(span.start, span.end) { at, length -> span.copy(start = at, length = length) } },
        emojis =
            emojis
                .filter { it.start >= start && it.start + it.length <= end && (it.length > 0 || it.start < end) }
                .map { it.copy(start = it.start - start) },
        highlights = highlights.mapNotNull { clip(it.start, it.end) { at, length -> RichTextHighlight(at, length) } },
        styles = styles.mapNotNull { style -> clip(style.start, style.end) { at, length -> style.copy(start = at, length = length) } },
    )
}

private val PLAIN_URL = Regex("""https?://\S+""")
private val PLAIN_TIMESTAMP = Regex("""\b(?:(\d{1,2}):)?(\d{1,2}):(\d{2})\b""")
private val PLAIN_HASHTAG = Regex("""(?<![\w#])#[\p{L}\p{N}_]+""")

/**
 * [text] with its links, timestamps and hashtags marked the way the server marks them, for text
 * that came without spans: the original description `/player` sends is plain (#1230).
 */
fun plainRichText(text: String): RichText {
    val spans = mutableListOf<RichTextSpan>()
    val taken = mutableListOf<IntRange>()

    fun add(
        range: IntRange,
        target: RichTextTarget,
    ) {
        if (taken.any { it.first <= range.last && range.first <= it.last }) return
        taken += range
        spans += RichTextSpan(range.first, range.last - range.first + 1, target)
    }
    PLAIN_URL.findAll(text).forEach { add(it.range, RichTextTarget.Url(it.value)) }
    PLAIN_TIMESTAMP.findAll(text).forEach { match ->
        val (hours, minutes, seconds) = match.destructured
        val total = (hours.toLongOrNull() ?: 0L) * 3_600 + minutes.toLong() * 60 + seconds.toLong()
        add(match.range, RichTextTarget.Timestamp(total))
    }
    PLAIN_HASHTAG.findAll(text).forEach { add(it.range, RichTextTarget.Hashtag(it.value.removePrefix("#"))) }
    return RichText(text = text, spans = spans.sortedBy { it.start })
}
