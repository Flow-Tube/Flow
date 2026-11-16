package io.github.aedev.flow.player.dlna

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.manifest.BaseUrl
import androidx.media3.exoplayer.dash.manifest.RangedUri
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.dash.manifest.SegmentBase
import androidx.media3.extractor.ChunkIndex
import okhttp3.OkHttpClient
import java.util.Locale
import kotlin.math.ceil

/** The HLS playlists [StreamProxyServer] serves to a renderer for one cast. */
internal object CastPlaylists {
    fun segmentFileExtension(contentType: String): String =
        when {
            contentType.startsWith("audio/") && contentType.contains("webm") -> "weba"
            contentType.startsWith("audio/") -> "m4a"
            contentType.contains("webm") -> "webm"
            else -> "mp4"
        }

    /** fMP4 HLS media playlist: the init segment at [init], then one byte-range segment per [index] entry. */
    @OptIn(UnstableApi::class)
    fun segmentedMediaPlaylist(
        proxyStreamUrl: String,
        init: LongRange,
        index: ChunkIndex,
    ): String {
        val durations = index.durationsUs.map { it / 1_000_000.0 }
        return buildString {
            appendLine("#EXTM3U")
            // EXT-X-MAP outside I-frame playlists requires protocol version 6.
            appendLine("#EXT-X-VERSION:6")
            appendLine("#EXT-X-TARGETDURATION:${ceil(durations.max()).toLong()}")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-MAP:URI=\"$proxyStreamUrl\",BYTERANGE=\"${init.length}@${init.first}\"")
            for (i in 0 until index.length) {
                appendLine("#EXTINF:${"%.3f".format(Locale.ROOT, durations[i])},")
                appendLine("#EXT-X-BYTERANGE:${index.sizes[i]}@${index.offsets[i]}")
                appendLine(proxyStreamUrl)
            }
            appendLine("#EXT-X-ENDLIST")
        }
    }

    /**
     * Builds an HLS media playlist for a single stream (video or audio).
     * Uses a single segment covering the entire file since YouTube streams
     * are progressive MP4 downloads, not segmented.
     */
    fun buildMediaPlaylist(
        proxyStreamUrl: String,
        durationSeconds: Long,
        targetDuration: Long,
    ): String =
        buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:$targetDuration")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXTINF:$durationSeconds.0,")
            appendLine(proxyStreamUrl)
            appendLine("#EXT-X-ENDLIST")
        }

    /**
     * Builds an fMP4 HLS media playlist with one byte-range segment per sidx entry, so the renderer
     * can start after the first few seconds instead of downloading the whole file. Throws when the
     * segment index can't be loaded, so a segmented cast fails instead of downloading the whole file.
     */
    @OptIn(UnstableApi::class)
    fun buildSegmentedMediaPlaylist(
        http: OkHttpClient,
        proxyStreamUrl: String,
        realUrl: String,
        contentType: String,
        ranges: CastSegmentRanges,
    ): String {
        val representation =
            Representation.newInstance(
                0,
                Format.Builder().setContainerMimeType(contentType).build(),
                listOf(BaseUrl(realUrl)),
                SegmentBase.SingleSegmentBase(
                    RangedUri(null, ranges.init.first, ranges.init.length),
                    1,
                    0,
                    ranges.index.first,
                    ranges.index.length,
                ),
            )
        val trackType = if (contentType.startsWith("audio/")) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_VIDEO
        val index =
            DashUtil
                .loadChunkIndex(OkHttpDataSource.Factory(http).createDataSource(), trackType, representation)
                ?.takeIf { it.length > 0 } ?: error("No segment index in $contentType stream")

        return segmentedMediaPlaylist(proxyStreamUrl, ranges.init, index)
    }

    /**
     * Builds the HLS master playlist with:
     * - Audio declared via #EXT-X-MEDIA
     * - Video variants declared via #EXT-X-STREAM-INF (sorted by resolution)
     */
    fun buildMasterPlaylist(
        variantEntries: List<Triple<String, CastStreamVariant, String>>,
        audioMediaUrl: String,
        audioBitrate: Int,
        audioCodec: String,
    ): String {
        val sorted = variantEntries.sortedBy { it.second.height }

        return buildString {
            appendLine("#EXTM3U")
            // EXT-X-MEDIA rendition groups require protocol version 4.
            appendLine("#EXT-X-VERSION:4")
            appendLine()

            // Audio group
            appendLine(
                "#EXT-X-MEDIA:TYPE=AUDIO," +
                    "GROUP-ID=\"audio\"," +
                    "NAME=\"Default\"," +
                    "DEFAULT=YES," +
                    "AUTOSELECT=YES," +
                    "URI=\"$audioMediaUrl\"",
            )
            appendLine()

            // Video variants
            for ((mediaUrl, variant, _) in sorted) {
                val totalBandwidth = variant.bitrate + audioBitrate
                appendLine(
                    "#EXT-X-STREAM-INF:" +
                        "BANDWIDTH=$totalBandwidth," +
                        "RESOLUTION=${variant.width}x${variant.height}," +
                        "CODECS=\"${variant.codec},$audioCodec\"," +
                        "AUDIO=\"audio\"",
                )
                appendLine(mediaUrl)
            }
        }
    }
}
