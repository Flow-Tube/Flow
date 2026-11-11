package io.github.aedev.flow.data.local

import java.io.Reader

// Every Takeout activity entry sits in this cell, whatever language the archive is in.
private const val TAKEOUT_ACTIVITY_MARKUP = "content-cell"
private const val ENTRY_START = "<div class=\"outer-cell"
private const val READ_SIZE = 65_536
private const val BATCH_SIZE = 500
private const val CHANNEL_LINK_REACH = 2_000

// HTML without Takeout's entry layout is read in spans of about this size, cut after a closing tag.
private const val MAX_SPAN = 262_144

private val watchLinkPattern =
    Regex("""href="https://(www|music)\.youtube\.com/watch\?v=([\w-]{10,12})"[^>]*?>([^<]+)</a>""", RegexOption.IGNORE_CASE)
private val channelLinkPattern =
    Regex("""href="https://(?:www|music)\.youtube\.com/channel/([^"&\s]+)"[^>]*?>([^<]+)</a>""", RegexOption.IGNORE_CASE)
private val searchLinkPattern =
    Regex("""href="(https://(?:www|music)\.youtube\.com/(?:results|search)\?[^"]+)"""", RegexOption.IGNORE_CASE)
private val captionPattern = Regex("""mdl-typography--caption">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)

/** A video watched, as a Takeout activity file records it. */
internal data class TakeoutWatch(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val watchedAt: Long,
    val isMusic: Boolean,
)

/**
 * What a Takeout activity HTML file held. [watches] counts what was handed to the batch callback;
 * [adVideoIds] are the videos it lists only as ads, which were not handed over.
 */
internal data class TakeoutHtmlActivity(
    val watches: Int,
    val searches: List<TakeoutSearch>,
    val adVideoIds: Set<String> = emptySet(),
)

/**
 * Streams a Takeout activity HTML file, handing the videos watched on YouTube and YouTube Music to
 * [onWatches] in batches and keeping the searches. Takeout names the watch and search history files
 * in the account's language, so both are read from every file. Neither carries a date Flow can read
 * in every language, so entries are stamped newest first from [now], as Takeout lists them. With
 * [requireActivityMarkup], a file that never shows the My Activity layout gives nothing, so other
 * HTML in the archive is ignored.
 *
 * Each entry is read whole, so a video's channel is never taken from the next entry and ads can be
 * told apart: Takeout gives an ad a Details block ("From Google Ads"), which is skipped (#1944).
 */
internal suspend fun readTakeoutHtmlActivity(
    reader: Reader,
    now: Long,
    requireActivityMarkup: Boolean,
    onWatches: suspend (List<TakeoutWatch>) -> Unit,
): TakeoutHtmlActivity {
    var markupSeen = !requireActivityMarkup
    var watched = 0
    val batch = mutableListOf<TakeoutWatch>()
    val searches = mutableListOf<TakeoutSearch>()
    val adVideoIds = HashSet<String>()
    val buffer = CharArray(READ_SIZE)
    val pending = StringBuilder()

    fun readSpan(span: String) {
        val links = watchLinkPattern.findAll(span).toList()
        if (links.isNotEmpty() && isTakeoutAd(span)) {
            links.mapTo(adVideoIds) { it.groupValues[2].trim() }
        } else {
            val channels = channelLinkPattern.findAll(span).toList()
            links.forEachIndexed { index, match ->
                val end = links.getOrNull(index + 1)?.range?.first ?: span.length
                val channel =
                    channels.firstOrNull {
                        it.range.first > match.range.last && it.range.first < end && it.range.first - match.range.last < CHANNEL_LINK_REACH
                    }
                batch +=
                    TakeoutWatch(
                        videoId = match.groupValues[2].trim(),
                        title = match.groupValues[3].trim().unescapedHtml(),
                        channelName =
                            channel
                                ?.groupValues
                                ?.get(2)
                                ?.trim()
                                ?.unescapedHtml()
                                .orEmpty(),
                        channelId =
                            channel
                                ?.groupValues
                                ?.get(1)
                                ?.trim()
                                .orEmpty(),
                        watchedAt = now - (watched + batch.size),
                        isMusic = match.groupValues[1].equals("music", ignoreCase = true),
                    )
            }
        }
        for (match in searchLinkPattern.findAll(span)) {
            val (query, isMusic) = takeoutSearchOf(match.groupValues[1].unescapedHtml()) ?: continue
            searches += TakeoutSearch(query, now - searches.size, isMusic)
        }
    }

    suspend fun flush() {
        if (batch.isEmpty()) return
        watched += batch.size
        onWatches(batch.toList())
        batch.clear()
    }

    fun readCompleteSpans() {
        var start = pending.indexOf(ENTRY_START)
        if (start < 0) {
            if (pending.length < MAX_SPAN) return
            val cut = pending.lastIndexOf("</div>").takeIf { it > 0 }?.plus("</div>".length) ?: pending.length
            readSpan(pending.substring(0, cut))
            pending.delete(0, cut)
            return
        }
        if (start > 0) readSpan(pending.substring(0, start))
        while (true) {
            val next = pending.indexOf(ENTRY_START, start + ENTRY_START.length)
            if (next < 0) break
            readSpan(pending.substring(start, next))
            start = next
        }
        pending.delete(0, start)
        if (pending.length >= MAX_SPAN) {
            readSpan(pending.toString())
            pending.setLength(0)
        }
    }

    while (true) {
        val count = reader.read(buffer)
        if (count == -1) break
        pending.appendRange(buffer, 0, count)
        if (!markupSeen) markupSeen = pending.contains(TAKEOUT_ACTIVITY_MARKUP)
        readCompleteSpans()
        if (markupSeen && batch.size >= BATCH_SIZE) flush()
    }
    if (pending.isNotEmpty()) readSpan(pending.toString())
    if (!markupSeen) return TakeoutHtmlActivity(0, emptyList())
    flush()
    return TakeoutHtmlActivity(watched, searches, adVideoIds)
}

/**
 * An ad's caption has a Details block next to Products, and neither holds a link. The labels are in
 * the account's language, so the blocks are counted, not read: every other block, such as "Why is
 * this here?", links to the activity controls.
 */
internal fun isTakeoutAd(entry: String): Boolean {
    val caption = captionPattern.find(entry)?.groupValues?.get(1) ?: return false
    return caption.split("<b>").drop(1).count { "<a " !in it } >= 2
}

internal fun String.unescapedHtml(): String =
    replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&#x27;", "'")
        .replace("&amp;", "&")
