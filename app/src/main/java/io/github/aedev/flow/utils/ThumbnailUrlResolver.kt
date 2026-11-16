package io.github.aedev.flow.utils

import io.github.aedev.flow.data.local.ThumbnailQuality
import io.github.aedev.flow.data.localmedia.LocalMediaIds

object ThumbnailUrlResolver {
    private val youtubeVideoThumbnailPattern =
        Regex("""(?:https?:)?//(?:i\d*\.ytimg\.com|img\.youtube\.com)/(?:vi|vi_webp)/([^/?#]+)/[^/?#]+""")
    private val googleCdnSizePattern = Regex("""w\d+-h\d+""")
    private val youtubePagePattern = Regex("""^(?:https?:)?//(?:www\.|m\.)?youtube\.com/""", RegexOption.IGNORE_CASE)
    private val googleCdnParamStartPattern = Regex("""=(?:w|s|h)""")

    fun buildHighQualityYoutubeThumbnail(videoId: String): String {
        val id = videoId.trim()
        return if (id.isEmpty() || LocalMediaIds.isLocal(id)) "" else "https://i.ytimg.com/vi/$id/hq720.jpg"
    }

    fun buildFallbackYoutubeThumbnail(videoId: String): String {
        val id = videoId.trim()
        return if (id.isEmpty() || LocalMediaIds.isLocal(id)) "" else "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    }

    fun buildMaxResYoutubeThumbnail(videoId: String): String {
        val id = videoId.trim()
        return if (id.isEmpty()) "" else "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
    }

    /**
     * Candidate tiers for a feed/list/grid card, best first.
     *
     * maxresdefault is deliberately excluded: YouTube only generates it for a subset of videos,
     * so requesting it from a card costs a failed round trip before the fallback on every video
     * that lacks it, and when it does exist it is 1920x1080 for a surface that never shows more
     * than roughly a third of those pixels. hq720 is already >= the widest phone card.
     */
    fun youtubeThumbnailCandidates(
        videoId: String,
        quality: ThumbnailQuality = ThumbnailQuality.HIGH,
        portrait: Boolean = false,
    ): List<String> {
        val id = videoId.trim()
        if (id.isEmpty() || LocalMediaIds.isLocal(id)) return emptyList()
        return youtubeThumbnailTiers(quality, portrait).map { tier -> "https://i.ytimg.com/vi/$id/$tier.jpg" }
    }

    /**
     * Best tier first, always ending in hqdefault, the one every video has. A portrait card crops a
     * 16:9 frame to about a third of its width, so it never drops below hqdefault.
     */
    private fun youtubeThumbnailTiers(
        quality: ThumbnailQuality,
        portrait: Boolean,
    ): List<String> =
        when (quality) {
            ThumbnailQuality.HIGH -> listOf("hq720", "hqdefault")
            ThumbnailQuality.MEDIUM -> listOf("sddefault", "hqdefault")
            ThumbnailQuality.LOW -> if (portrait) listOf("hqdefault") else listOf("mqdefault", "hqdefault")
            ThumbnailQuality.OFF -> emptyList()
        }

    /** A cover stored on the device: a download's saved thumbnail, or a MediaStore item. */
    fun isDeviceUri(url: String): Boolean = url.startsWith("file:") || url.startsWith("content:") || url.startsWith("/")

    fun resolveVideoThumbnailCandidates(
        videoId: String,
        rawUrl: String?,
        quality: ThumbnailQuality = ThumbnailQuality.HIGH,
        portrait: Boolean = false,
    ): List<String> {
        val raw = rawUrl?.trim().orEmpty()
        val resolvedVideoId = resolveYoutubeThumbnailVideoId(videoId, raw)
        val youtubeCandidates = youtubeThumbnailCandidates(resolvedVideoId, quality, portrait)

        val candidates =
            when {
                raw.isEmpty() -> youtubeCandidates
                isYoutubeVideoThumbnail(raw) -> youtubeCandidates
                quality == ThumbnailQuality.OFF && !isDeviceUri(raw) -> youtubeCandidates
                else -> listOf(raw) + youtubeCandidates
            }

        return candidates
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun preferredVideoThumbnail(
        videoId: String,
        urls: List<String?>,
    ): String =
        urls
            .asSequence()
            .map { it?.trim().orEmpty() }
            .filter { it.isNotBlank() }
            .map { normalizeVideoThumbnail(videoId, it) }
            .maxWithOrNull(compareBy<String> { videoThumbnailQualityRank(it) }.thenBy { it.length })
            ?: normalizeVideoThumbnail(videoId, null)

    fun normalizeVideoThumbnail(
        videoId: String,
        rawUrl: String?,
    ): String {
        val raw = rawUrl?.trim().orEmpty()
        if (raw.isEmpty()) return buildHighQualityYoutubeThumbnail(videoId)

        if (!youtubeVideoThumbnailPattern.containsMatchIn(raw)) return raw
        val resolvedVideoId = resolveYoutubeThumbnailVideoId(videoId, raw)
        if (raw.contains("maxresdefault", ignoreCase = true)) {
            return buildMaxResYoutubeThumbnail(resolvedVideoId).ifEmpty { raw }
        }

        return buildHighQualityYoutubeThumbnail(resolvedVideoId).ifEmpty { raw }
    }

    fun resolveMusicThumbnail(
        videoId: String,
        rawUrl: String?,
        size: Int = 1080,
    ): String {
        val raw = rawUrl?.trim().orEmpty()
        val id = videoId.trim()

        if (raw.isEmpty()) return buildHighQualityYoutubeThumbnail(id)

        return when {
            isYoutubeVideoThumbnail(raw) -> {
                normalizeVideoThumbnail(id, raw)
            }

            raw.contains("googleusercontent.com") || raw.contains("ggpht.com") -> {
                resizeImageThumbnail(raw, size, size)
            }

            else -> {
                raw
            }
        }
    }

    fun resolveChannelBanner(
        rawUrl: String?,
        targetWidth: Int = 1060,
    ): String {
        val raw = rawUrl?.trim().orEmpty()
        if (raw.isEmpty()) return ""

        val isGoogleCdn = raw.contains("googleusercontent.com") || raw.contains("ggpht.com")
        if (!isGoogleCdn) return raw

        val sizeParamRegex = Regex("""=([wsh])\d+""")
        val match = sizeParamRegex.find(raw)
        if (match != null) {
            val paramType = match.groupValues[1]
            return raw.replaceFirst(match.value, "=$paramType$targetWidth")
        }

        val paramStart = googleCdnParamStartPattern.find(raw)?.range?.first
        return if (paramStart != null) {
            val baseUrl = raw.substring(0, paramStart)
            "$baseUrl=w$targetWidth"
        } else {
            "$raw=w$targetWidth"
        }
    }

    /**
     * Edge length for channel avatars on list/card surfaces, where they render at roughly
     * 24-48 dp. 176 px stays sharp past 4x density while requesting ~8x fewer pixels than the
     * previous 512 px default. Pass an explicit [size] for genuinely large avatar surfaces.
     */
    const val AVATAR_SIZE_LIST = 176

    fun resolveChannelAvatar(
        rawUrl: String?,
        size: Int = AVATAR_SIZE_LIST,
    ): String {
        val raw = rawUrl?.trim().orEmpty()
        if (raw.isEmpty()) return ""

        val isGoogleCdn = raw.contains("googleusercontent.com") || raw.contains("ggpht.com")
        if (!isGoogleCdn) return raw

        if (googleCdnSizePattern.containsMatchIn(raw)) {
            return raw.replace(googleCdnSizePattern, "s$size")
        }

        val sizeParamRegex = Regex("""=([wsh])\d+""")
        val match = sizeParamRegex.find(raw)
        if (match != null) {
            return raw.replaceFirst(match.value, "=s$size")
        }

        val paramStart = googleCdnParamStartPattern.find(raw)?.range?.first
        val baseUrl = if (paramStart != null) raw.substring(0, paramStart) else raw
        return "$baseUrl=s$size"
    }

    /**
     * A post image on Google's image CDN at [targetWidth]. Its URLs also carry the crop YouTube made for
     * the feed (`-c-fcrop64=…`); [uncropped] drops every option but the width, which serves the whole
     * image at its own shape.
     */
    fun resolveCommunityPostImage(
        rawUrl: String?,
        targetWidth: Int = 2048,
        uncropped: Boolean = false,
    ): String {
        val raw =
            rawUrl?.trim().orEmpty().let { url ->
                if (url.startsWith("//")) "https:$url" else url
            }
        if (raw.isEmpty()) return ""

        val isGoogleCdn = raw.contains("googleusercontent.com") || raw.contains("ggpht.com")
        if (!isGoogleCdn) return raw

        if (!uncropped) {
            val sizeParamRegex = Regex("""=([wsh])\d+""")
            sizeParamRegex.find(raw)?.let { match ->
                return raw.replaceFirst(match.value, "=w$targetWidth")
            }
        }
        val paramStart = googleCdnParamStartPattern.find(raw)?.range?.first
        val baseUrl = if (paramStart != null) raw.substring(0, paramStart) else raw
        return "$baseUrl=w$targetWidth"
    }

    fun fallbackVideoThumbnail(
        videoId: String,
        rawUrl: String?,
    ): String? {
        val raw = rawUrl?.trim().orEmpty()
        val resolvedVideoId =
            youtubeVideoThumbnailPattern
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: videoId.trim()

        val fallback = buildFallbackYoutubeThumbnail(resolvedVideoId)
        return fallback.takeIf { it.isNotEmpty() && it != raw }
    }

    /**
     * A stored channel avatar that cannot be shown as one: a video thumbnail, or a YouTube page
     * (some imported subscriptions carry the channel's own URL in the avatar field).
     */
    fun isUnusableChannelAvatar(rawUrl: String?): Boolean {
        val raw = rawUrl?.trim().orEmpty()
        return isYoutubeVideoThumbnail(raw) || youtubePagePattern.containsMatchIn(raw)
    }

    fun isYoutubeVideoThumbnail(rawUrl: String?): Boolean {
        val raw = rawUrl?.trim().orEmpty()
        return youtubeVideoThumbnailPattern.containsMatchIn(raw)
    }

    private fun resolveYoutubeThumbnailVideoId(
        videoId: String,
        rawUrl: String,
    ): String =
        youtubeVideoThumbnailPattern
            .find(rawUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: videoId.trim()

    private fun videoThumbnailQualityRank(rawUrl: String): Int {
        val raw = rawUrl.lowercase()
        return when {
            "maxresdefault" in raw -> 5
            "hq720" in raw || "sddefault" in raw -> 4
            "hqdefault" in raw -> 3
            "mqdefault" in raw -> 2
            "default" in raw -> 1
            else -> 0
        }
    }

    fun resizeImageThumbnail(
        rawUrl: String?,
        width: Int? = null,
        height: Int? = null,
    ): String {
        val raw = rawUrl?.trim().orEmpty()
        if (raw.isEmpty() || (width == null && height == null)) return raw

        val isGoogleCdn = raw.contains("googleusercontent.com") || raw.contains("ggpht.com")
        val isYtimg = raw.contains("i.ytimg.com") || raw.contains("img.youtube.com")

        return when {
            isGoogleCdn -> resizeGoogleCdnThumbnail(raw, width, height)
            isYtimg -> resizeYoutubeThumbnail(raw, width ?: height ?: 0)
            else -> raw
        }
    }

    private fun resizeGoogleCdnThumbnail(
        rawUrl: String,
        width: Int?,
        height: Int?,
    ): String {
        val w = width ?: height ?: return rawUrl
        val h = height ?: width ?: return rawUrl

        if (googleCdnSizePattern.containsMatchIn(rawUrl)) {
            return rawUrl.replace(googleCdnSizePattern, "w$w-h$h")
        }

        val paramStart = googleCdnParamStartPattern.find(rawUrl)?.range?.first
        val baseUrl = if (paramStart != null) rawUrl.substring(0, paramStart) else rawUrl

        return if (width != null && height != null) {
            "$baseUrl=w$w-h$h-p-l90-rj"
        } else {
            "$baseUrl=s$w-p-l90-rj"
        }
    }

    private fun resizeYoutubeThumbnail(
        rawUrl: String,
        width: Int,
    ): String =
        when {
            width > 480 -> {
                rawUrl
                    .replace("mqdefault.jpg", "hq720.jpg")
                    .replace("hqdefault.jpg", "hq720.jpg")
                    .replace("sddefault.jpg", "hq720.jpg")
                    .replace("default.jpg", "hq720.jpg")
                    .replace("mqdefault.webp", "hq720.jpg")
                    .replace("hqdefault.webp", "hq720.jpg")
                    .replace("sddefault.webp", "hq720.jpg")
                    .replace("default.webp", "hq720.jpg")
            }

            width > 320 -> {
                rawUrl
                    .replace("mqdefault.jpg", "hqdefault.jpg")
                    .replace("default.jpg", "hqdefault.jpg")
                    .replace("mqdefault.webp", "hqdefault.jpg")
                    .replace("default.webp", "hqdefault.jpg")
            }

            else -> {
                rawUrl
            }
        }
}
