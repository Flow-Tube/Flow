package io.github.aedev.flow.player.stream

import kotlin.math.abs

/**
 * One scrub-preview frame: where to find it and which part of the sheet it occupies.
 *
 * [sheetWidth] and [sheetHeight] are the sheet's real dimensions, which the caller must draw the
 * image at before cropping to [left]/[top] — the final sheet of a video is short, holding only the
 * rows it needs, so sizing every sheet at columns x rows misplaces every tile near the end.
 */
data class StoryboardTile(
    val sheetUrl: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val sheetWidth: Int,
    val sheetHeight: Int,
)

/** Where a seek bar position falls in a live stream: its segment, and the newest segment so far. */
data class LiveSegmentPosition(
    val sequence: Long,
    val headSequence: Long,
)

/** Finds the live segment under a seek bar position, or null while the stream is not on its segments. */
fun interface LiveSegmentLocator {
    fun at(positionMs: Long): LiveSegmentPosition?
}

/**
 * One resolution of a video's storyboard.
 *
 * YouTube renders scrub previews ahead of time into sprite sheets and describes them in
 * `playerStoryboardSpecRenderer.spec`, several levels deep: the coarsest is a single sheet of
 * postage stamps, the finest is readable but spread over more sheets.
 *
 * A live stream has one level, with one frame per segment: frame N is segment N, so its frames are
 * found through [liveSegments] rather than by time.
 */
data class StoryboardLevel(
    val index: Int,
    val thumbnailWidth: Int,
    val thumbnailHeight: Int,
    val frameCount: Int,
    val columns: Int,
    val rows: Int,
    val intervalMs: Long,
    private val baseUrl: String,
    private val nameTemplate: String,
    private val signature: String,
    private val liveSegments: LiveSegmentLocator? = null,
) {
    private val framesPerSheet = columns * rows

    val isLive: Boolean
        get() = liveSegments != null

    /** The frame covering [positionMs], or null when the level describes nothing usable. */
    fun tileAt(positionMs: Long): StoryboardTile? {
        liveSegments?.let { locator -> return locator.at(positionMs)?.let(::liveTile) }
        if (framesPerSheet <= 0 || frameCount <= 0 || intervalMs <= 0L) return null
        val frame = (positionMs.coerceAtLeast(0L) / intervalMs).toInt().coerceIn(0, frameCount - 1)
        val sheet = frame / framesPerSheet
        val withinSheet = frame % framesPerSheet
        val column = withinSheet % columns
        val row = withinSheet / columns

        val framesOnSheet = (frameCount - sheet * framesPerSheet).coerceAtMost(framesPerSheet)
        val rowsOnSheet = (framesOnSheet + columns - 1) / columns

        return StoryboardTile(
            sheetUrl = sheetUrl(sheet.toLong()),
            left = column * thumbnailWidth,
            top = row * thumbnailHeight,
            width = thumbnailWidth,
            height = thumbnailHeight,
            sheetWidth = columns * thumbnailWidth,
            sheetHeight = rowsOnSheet * thumbnailHeight,
        )
    }

    /**
     * YouTube draws a live sheet once all its segments exist, and its player shows nothing from the
     * newest [LIVE_PENDING_SHEETS] sheets' worth of segments.
     */
    private fun liveTile(position: LiveSegmentPosition): StoryboardTile? {
        if (framesPerSheet <= 0 || position.sequence < 0) return null
        if (position.sequence > position.headSequence - framesPerSheet * LIVE_PENDING_SHEETS) return null
        val withinSheet = (position.sequence % framesPerSheet).toInt()
        return StoryboardTile(
            sheetUrl = sheetUrl(position.sequence / framesPerSheet),
            left = withinSheet % columns * thumbnailWidth,
            top = withinSheet / columns * thumbnailHeight,
            width = thumbnailWidth,
            height = thumbnailHeight,
            sheetWidth = columns * thumbnailWidth,
            sheetHeight = rows * thumbnailHeight,
        )
    }

    private fun sheetUrl(sheet: Long): String {
        val url =
            baseUrl
                .replace(LEVEL_TOKEN, index.toString())
                .replace(NAME_TOKEN, nameTemplate.replace(SHEET_TOKEN, sheet.toString()))
        return if (signature.isBlank()) url else "$url&sigh=$signature"
    }

    internal companion object {
        const val LEVEL_TOKEN = "\$L"
        const val NAME_TOKEN = "\$N"
        const val SHEET_TOKEN = "\$M"
        const val LIVE_PENDING_SHEETS = 2
    }
}

/**
 * Parses `playerStoryboardSpecRenderer.spec` into its levels.
 *
 * The spec is `baseUrl|level|level|...`, each level eight `#`-separated fields:
 * width, height, frame count, columns, rows, interval, name template, signature. The coarsest level
 * reports an interval of zero and instead spreads its frames evenly across the whole video, so a
 * duration is needed to place its frames at all.
 */
object StoryboardSpec {
    fun parse(
        spec: String?,
        durationMs: Long,
    ): List<StoryboardLevel> {
        if (spec.isNullOrBlank()) return emptyList()
        val segments = spec.split('|')
        val baseUrl = segments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (!baseUrl.contains(StoryboardLevel.LEVEL_TOKEN) || !baseUrl.contains(StoryboardLevel.NAME_TOKEN)) {
            return emptyList()
        }
        return segments.drop(1).mapIndexedNotNull { index, segment ->
            parseLevel(index, segment, baseUrl, durationMs)
        }
    }

    /**
     * Parses `playerLiveStoryboardSpecRenderer.spec`, `url#width#height#columns#rows`, into the one
     * level a live stream has. [segmentDurationMs] is how far apart its frames are.
     */
    fun parseLive(
        spec: String?,
        segmentDurationMs: Long,
        segments: LiveSegmentLocator,
    ): List<StoryboardLevel> {
        val fields = spec?.split('#') ?: return emptyList()
        if (fields.size < LIVE_FIELD_COUNT) return emptyList()
        val url = fields[0].takeIf { it.contains(StoryboardLevel.SHEET_TOKEN) } ?: return emptyList()
        val width = fields[1].toIntOrNull() ?: return emptyList()
        val height = fields[2].toIntOrNull() ?: return emptyList()
        val columns = fields[3].toIntOrNull() ?: return emptyList()
        val rows = fields[4].toIntOrNull() ?: return emptyList()
        if (width <= 0 || height <= 0 || columns <= 0 || rows <= 0 || segmentDurationMs <= 0L) return emptyList()
        return listOf(
            StoryboardLevel(
                index = 0,
                thumbnailWidth = width,
                thumbnailHeight = height,
                frameCount = 0,
                columns = columns,
                rows = rows,
                intervalMs = segmentDurationMs,
                baseUrl = StoryboardLevel.NAME_TOKEN,
                nameTemplate = url,
                signature = "",
                liveSegments = segments,
            ),
        )
    }

    /** The level whose frames are closest to [targetWidthPx], the size actually drawn. */
    fun levelFor(
        levels: List<StoryboardLevel>,
        targetWidthPx: Int,
    ): StoryboardLevel? {
        if (levels.isEmpty()) return null
        if (targetWidthPx <= 0) return levels.last()
        return levels.minByOrNull { abs(it.thumbnailWidth - targetWidthPx) }
    }

    private fun parseLevel(
        index: Int,
        segment: String,
        baseUrl: String,
        durationMs: Long,
    ): StoryboardLevel? {
        val fields = segment.split('#')
        if (fields.size < FIELD_COUNT) return null
        val width = fields[0].toIntOrNull() ?: return null
        val height = fields[1].toIntOrNull() ?: return null
        val frameCount = fields[2].toIntOrNull() ?: return null
        val columns = fields[3].toIntOrNull() ?: return null
        val rows = fields[4].toIntOrNull() ?: return null
        val declaredInterval = fields[5].toLongOrNull() ?: return null
        val name = fields[6].takeIf { it.isNotBlank() } ?: return null
        val signature = fields[7].takeIf { it.isNotBlank() } ?: return null
        if (width <= 0 || height <= 0 || frameCount <= 0 || columns <= 0 || rows <= 0) return null

        val interval =
            if (declaredInterval > 0L) {
                declaredInterval
            } else {
                (durationMs / frameCount).takeIf { it > 0L } ?: return null
            }

        return StoryboardLevel(
            index = index,
            thumbnailWidth = width,
            thumbnailHeight = height,
            frameCount = frameCount,
            columns = columns,
            rows = rows,
            intervalMs = interval,
            baseUrl = baseUrl,
            nameTemplate = name,
            signature = signature,
        )
    }

    private const val FIELD_COUNT = 8
    private const val LIVE_FIELD_COUNT = 5
}
