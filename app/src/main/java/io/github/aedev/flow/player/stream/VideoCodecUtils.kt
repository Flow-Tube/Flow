package io.github.aedev.flow.player.stream

import android.net.Uri
import org.schabi.newpipe.extractor.stream.VideoStream

object VideoCodecUtils {
    private val QUALITY_HEIGHT_REGEX = Regex("""(\d+)p""")
    private val FRAME_RATE_LABEL_REGEX = Regex("""\d+p(\d+)""")

    /**
     * YouTube only spells the frame rate out on its high-frame-rate ladder ("1080p60"); 24/25/30 fps
     * stay bare. Flow's quality selector follows the same convention.
     */
    private const val HIGH_FRAME_RATE_FPS = 50
    private val CODECS_PARAMETER_REGEX = Regex("""codecs\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private val AV1_ITAGS = setOf(394, 395, 396, 397, 398, 399, 400, 401, 402, 571, 694, 695, 696, 697, 698, 699, 700, 701)
    private val VP9_ITAGS =
        setOf(
            242,
            243,
            244,
            245,
            246,
            247,
            248,
            271,
            272,
            278,
            302,
            303,
            308,
            313,
            315,
            330,
            331,
            332,
            333,
            334,
            335,
            336,
            337,
        )
    private val H264_ITAGS =
        setOf(
            133,
            134,
            135,
            136,
            137,
            138,
            160,
            212,
            264,
            266,
            298,
            299,
            300,
            301,
            304,
            305,
            18,
            22,
            34,
            35,
            37,
            38,
            59,
            78,
        )
    private val VP8_ITAGS = setOf(43)

    fun codecKeyFromMimeType(mimeType: String): String {
        val m = mimeType.lowercase()
        val codecs = codecStringFromMimeType(m)
        return when {
            "av01" in codecs -> "av1"
            "vp09" in codecs || "vp9" in codecs -> "vp9"
            "vp08" in codecs || "vp8" in codecs -> "vp8"
            "hev1" in codecs || "hvc1" in codecs -> "hevc"
            "avc1" in codecs -> "h264"
            "webm" in m -> "vp9"
            else -> "h264"
        }
    }

    fun codecStringFromMimeType(mimeType: String): String {
        val m = mimeType.lowercase()
        return CODECS_PARAMETER_REGEX
            .find(m)
            ?.groupValues
            ?.getOrNull(1)
            ?.substringBefore(",")
            ?.trim()
            .orEmpty()
    }

    fun codecKeyFromStream(stream: VideoStream): String {
        val url =
            try {
                stream.content.takeIf { it.isNotBlank() } ?: stream.url ?: ""
            } catch (_: Exception) {
                ""
            }
        val itag = itagFromUrl(url) ?: itagFromId(stream)

        when (itag) {
            in AV1_ITAGS -> return "av1"
            in VP9_ITAGS -> return "vp9"
            in VP8_ITAGS -> return "vp8"
            in H264_ITAGS -> return "h264"
        }

        val fmtMime =
            try {
                stream.format?.mimeType?.lowercase() ?: ""
            } catch (_: Exception) {
                ""
            }
        val fmtName =
            try {
                stream.format?.name?.lowercase() ?: ""
            } catch (_: Exception) {
                ""
            }
        return when {
            "av01" in fmtMime || "av01" in fmtName || "av1" in fmtName -> "av1"
            "vp09" in fmtMime || "vp9" in fmtMime || "vp9" in fmtName -> "vp9"
            "vp08" in fmtMime || "vp8" in fmtMime || "vp8" in fmtName -> "vp8"
            "webm" in fmtName || "webm" in fmtMime -> "vp9"
            "hev1" in fmtMime || "hvc1" in fmtMime || "hevc" in fmtName -> "hevc"
            else -> "h264"
        }
    }

    fun codecLabelFromKey(key: String): String =
        when (key) {
            "av1" -> "AV1"
            "vp9" -> "VP9"
            "vp8" -> "VP8"
            "hevc" -> "HEVC"
            "h264" -> "H264"
            else -> key.uppercase()
        }

    /**
     * Composite key for every "(resolution, codec) -> value" lookup table in the app: the download
     * dialog's size map and the player's format grouping alike.
     *
     * Format: `"${height}_${codecKey}"`, e.g. `"2160_av1"`, `"1080_vp9"`.
     */
    fun streamSizeKey(
        height: Int,
        codecKey: String,
    ): String = "${height}_$codecKey"

    /**
     * Resolution class of an InnerTube format. `qualityLabel` wins over the raw pixel height:
     * portrait media (Shorts) reports the *long* side as the height, so keying off it alone files a
     * 1080p Short under 1920 and nothing ever matches it again.
     */
    fun qualityHeightFromFormat(
        qualityLabel: String?,
        fallbackHeight: Int,
    ): Int = normalizeQualityHeight(parseQualityHeight(qualityLabel) ?: fallbackHeight)

    fun qualityHeightFromStream(stream: VideoStream): Int {
        parseQualityHeight(stream.resolution)?.let { return it }
        parseQualityHeight(stream.quality)?.let { return it }
        parseQualityHeight(stream.itagItem?.resolutionString)?.let { return it }
        return normalizeQualityHeight(stream.height)
    }

    fun frameRateFromStream(stream: VideoStream): Int {
        if (stream.fps > 0) return stream.fps
        // A stream built without ItagItem metadata carries the frame rate only inside its label.
        return frameRateFromLabel(stream.resolution)
            ?: frameRateFromLabel(stream.itagItem?.resolutionString)
            ?: 0
    }

    /** YouTube names an HDR format in its label ("2160p60 HDR") and never an SDR one. */
    fun isHdr(stream: VideoStream): Boolean =
        listOfNotNull(stream.resolution, stream.itagItem?.resolutionString).any { it.contains("HDR", ignoreCase = true) }

    fun frameRateFromLabel(label: String?): Int? {
        if (label.isNullOrBlank()) return null
        return FRAME_RATE_LABEL_REGEX
            .find(label)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    /**
     * Resolution label for the quality selector: "1080p", or "1080p60" once the stream is above the
     * standard frame rate, so a 60 fps ladder is recognisable without opening the stream.
     */
    fun qualityLabelWithFrameRate(
        height: Int,
        fps: Int,
    ): String = if (fps >= HIGH_FRAME_RATE_FPS) "${height}p$fps" else "${height}p"

    fun playbackCodecRank(stream: VideoStream): Int = playbackCodecRank(codecKeyFromStream(stream))

    fun playbackCodecRank(codecKey: String): Int =
        when (codecKey) {
            "h264" -> 0
            "vp9" -> 1
            "vp8" -> 2
            "hevc" -> 3
            "av1" -> 4
            else -> 5
        }

    /**
     * Parses the user's codec preference into an ordered priority list, most-preferred first.
     *
     * The preference is a comma-separated list of codec keys ("av1,vp9") so a single string can
     * carry the preferred codec plus the fallback the user picked for videos that do not offer it.
     * "auto" and blanks mean "no preference" and yield an empty list, which leaves every ranking on
     * the built-in [playbackCodecRank] order.
     */
    fun codecPriorityList(preference: String?): List<String> {
        if (preference.isNullOrBlank()) return emptyList()
        return preference
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "auto" }
            .distinct()
    }

    fun codecRankWithPreference(
        codecKey: String,
        preferred: String?,
    ): Int {
        val priority = codecPriorityList(preferred)
        val index = priority.indexOf(codecKey)
        // Negative so any listed codec outranks the built-in order, earlier entries ranking lower.
        return if (index >= 0) index - priority.size else playbackCodecRank(codecKey)
    }

    fun codecRankWithPreference(
        stream: VideoStream,
        preferred: String?,
    ): Int = codecRankWithPreference(codecKeyFromStream(stream), preferred)

    private val DEFAULT_VIDEO_MIME_TYPES =
        arrayOf(
            "video/avc",
            "video/x-vnd.on2.vp9",
            "video/x-vnd.on2.vp8",
            "video/hevc",
            "video/av01",
        )

    private val MIME_TYPE_BY_CODEC_KEY =
        mapOf(
            "h264" to "video/avc",
            "vp9" to "video/x-vnd.on2.vp9",
            "vp8" to "video/x-vnd.on2.vp8",
            "hevc" to "video/hevc",
            "av1" to "video/av01",
        )

    @JvmOverloads
    fun preferredVideoMimeTypes(preferredCodecKey: String? = null): Array<String> {
        val preferred =
            codecPriorityList(preferredCodecKey)
                .mapNotNull { MIME_TYPE_BY_CODEC_KEY[it] }
        if (preferred.isEmpty()) return DEFAULT_VIDEO_MIME_TYPES
        return (preferred + DEFAULT_VIDEO_MIME_TYPES.filterNot { it in preferred }).toTypedArray()
    }

    private fun itagFromUrl(url: String): Int? {
        if (url.isBlank()) return null
        return try {
            Uri.parse(url).getQueryParameter("itag")?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun itagFromId(stream: VideoStream): Int? =
        try {
            stream.id?.toIntOrNull()
        } catch (_: Exception) {
            null
        }

    private fun parseQualityHeight(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        return QUALITY_HEIGHT_REGEX
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    fun normalizeQualityHeight(rawHeight: Int): Int =
        when {
            rawHeight <= 0 -> 0
            rawHeight in setOf(2160, 1440, 1080, 720, 480, 360, 240, 144) -> rawHeight
            else -> rawHeight
        }

    /** A frame's quality class is its short side: a portrait 720x1280 frame is 720p, not 1280p. */
    fun qualityClass(
        width: Int,
        height: Int,
    ): Int = if (width > 0 && height > 0) minOf(width, height) else maxOf(width, height, 0)
}
