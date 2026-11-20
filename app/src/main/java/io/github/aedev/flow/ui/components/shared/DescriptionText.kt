package io.github.aedev.flow.ui.components.shared

import android.text.style.URLSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.text.HtmlCompat
import io.github.aedev.flow.ui.theme.DescriptionLinkBlue
import io.github.aedev.flow.utils.RichTextLinkActions
import io.github.aedev.flow.utils.linkStyles

private val HtmlTimestamp = Regex("""\b(?:[0-9]{1,2}:)?[0-9]{1,2}:[0-9]{2}\b""")
private val HtmlPlainUrl = Regex("""https?://[^\s]+""")

/**
 * A description saved as HTML, which downloads made before the native client still carry. Anchors
 * keep their href, a printed "1:57" seeks, and a URL wins over a time that is part of it.
 */
fun parseHtmlDescription(
    rawHtml: String,
    linkColor: Color = DescriptionLinkBlue,
    actions: RichTextLinkActions = RichTextLinkActions(),
): AnnotatedString {
    val spanned = HtmlCompat.fromHtml(rawHtml, HtmlCompat.FROM_HTML_MODE_COMPACT)
    val text = spanned.toString()
    val taken = mutableListOf<IntRange>()
    val urlStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)
    val timeStyle = SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)

    return buildAnnotatedString {
        append(text)

        fun mark(
            start: Int,
            end: Int,
            style: SpanStyle,
            onClick: (() -> Unit)?,
        ) {
            if (start >= end || taken.any { it.first < end && start <= it.last }) return
            taken += start until end
            if (onClick == null) {
                addStyle(style, start, end)
            } else {
                addLink(LinkAnnotation.Clickable(text.substring(start, end), linkStyles(style, linkColor)) { onClick() }, start, end)
            }
        }

        fun seek(printed: String) = actions.onSeekMs?.let { seek -> { seek(commentTimestampToMs(printed)) } }

        fun open(url: String) = actions.onOpenUrl?.let { open -> { open(url) } }

        spanned.getSpans(0, spanned.length, URLSpan::class.java).forEach { span ->
            val start = spanned.getSpanStart(span).coerceAtMost(text.length)
            val end = spanned.getSpanEnd(span).coerceAtMost(text.length)
            if (start >= end) return@forEach
            val label = text.substring(start, end)
            if (HtmlTimestamp.matches(label)) {
                mark(start, end, timeStyle, seek(label))
            } else {
                val url = span.url.let { if (it.startsWith("/")) "https://www.youtube.com$it" else it }
                mark(start, end, urlStyle, open(url))
            }
        }
        HtmlPlainUrl.findAll(text).forEach { match ->
            mark(match.range.first, match.range.last + 1, urlStyle, open(match.value))
        }
        HtmlTimestamp.findAll(text).forEach { match ->
            mark(match.range.first, match.range.last + 1, timeStyle, seek(match.value))
        }
    }
}
