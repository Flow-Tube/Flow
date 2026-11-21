package io.github.aedev.flow.utils

import io.github.aedev.flow.innertube.YouTube
import java.util.Locale

/** A card's finished date and view-count text. [views] is empty when the count is not reported. */
data class CardByline(
    val date: String,
    val views: String,
)

internal data class BylineKey(
    val uploadDate: String,
    val timestamp: Long,
    val timestampIsExact: Boolean,
    val viewCount: Long,
    val settings: DateDisplaySettings,
    val labels: DateLabels,
    val viewsTemplate: String,
    val locale: Locale,
    val hl: String?,
)

/**
 * Bounded, least-recently-used byline texts. An entry is reused for [freshMs] only, so a relative
 * age ("5 minutes ago") never outlives the minute it describes; a locale change drops everything.
 */
internal class BylineCache(
    private val capacity: Int,
    private val freshMs: Long,
    private val clock: () -> Long,
    private val compute: (BylineKey, Long) -> CardByline,
) {
    private class Entry(
        val byline: CardByline,
        val madeAtMs: Long,
    )

    private val entries =
        object : LinkedHashMap<BylineKey, Entry>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BylineKey, Entry>?): Boolean = size > capacity
        }
    private var locale: Locale? = null

    @Synchronized
    fun get(key: BylineKey): CardByline {
        if (key.locale != locale) {
            entries.clear()
            locale = key.locale
        }
        val now = clock()
        entries[key]?.takeIf { now - it.madeAtMs < freshMs }?.let { return it.byline }
        return compute(key, now).also { entries[key] = Entry(it, now) }
    }

    @Synchronized
    fun size(): Int = entries.size
}

/**
 * Formats a video card's date and view count once per video instead of on every scroll-in: the
 * relative-date parse and the ICU formatters behind it are the costly part of binding a card.
 */
object CardTextFormatter {
    private const val CAPACITY = 512
    private const val FRESH_MS = 60_000L

    private val cache = BylineCache(CAPACITY, FRESH_MS, System::currentTimeMillis, ::format)

    private var labelsLocale: Locale? = null
    private var labels: DateLabels? = null

    /** The labels for [locale], built by [resolve] only when the locale differs from the last one. */
    @Synchronized
    fun dateLabels(
        locale: Locale,
        resolve: () -> DateLabels,
    ): DateLabels =
        labels?.takeIf { labelsLocale == locale } ?: resolve().also {
            labels = it
            labelsLocale = locale
        }

    fun byline(
        uploadDate: String,
        timestamp: Long,
        timestampIsExact: Boolean,
        viewCount: Long,
        settings: DateDisplaySettings,
        labels: DateLabels,
        viewsTemplate: String,
        locale: Locale = Locale.getDefault(),
    ): CardByline =
        cache.get(
            BylineKey(
                uploadDate = uploadDate,
                timestamp = timestamp,
                timestampIsExact = timestampIsExact,
                viewCount = viewCount,
                settings = settings,
                labels = labels,
                viewsTemplate = viewsTemplate,
                locale = locale,
                hl = YouTube.locale.hl,
            ),
        )

    private fun format(
        key: BylineKey,
        nowMs: Long,
    ): CardByline =
        CardByline(
            date =
                formatUploadDateConfigured(
                    date = key.uploadDate,
                    mode = key.settings.resolve(DateContext.LISTS),
                    style = key.settings.formatStyle,
                    labels = key.labels,
                    timestampFallbackMs = key.timestamp,
                    locale = key.locale,
                    hl = key.hl,
                    timestampIsExact = key.timestampIsExact,
                    nowMillis = nowMs,
                ),
            views = if (key.viewCount > 0L) key.viewsTemplate.format(formatCompactCount(key.viewCount, key.locale)) else "",
        )
}
