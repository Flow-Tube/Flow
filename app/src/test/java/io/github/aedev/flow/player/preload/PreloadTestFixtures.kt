package io.github.aedev.flow.player.preload

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.stream.ResolvedStreamData
import io.github.aedev.flow.player.stream.model.StreamType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope

internal fun video(id: String) =
    Video(
        id = id,
        title = "Video $id",
        channelName = "Channel",
        channelId = "UC123",
        thumbnailUrl = "https://example.test/$id.jpg",
        duration = 120,
        viewCount = 10L,
        uploadDate = "today",
    )

internal fun resolved(
    id: String,
    streamType: StreamType? = StreamType.VIDEO_STREAM,
) = ResolvedStreamData(
    enrichedVideo = video(id),
    videoStream = null,
    audioStream = null,
    videoStreams = emptyList(),
    audioStreams = emptyList(),
    subtitles = emptyList(),
    durationSeconds = 120L,
    dashManifestUrl = null,
    streamType = streamType,
    preferredCodec = "avc",
    itVideoFormats = emptyList(),
    itAudioFormats = emptyList(),
)

internal fun playerWith(
    mediaItemCount: Int = 1,
    currentIndex: Int = 0,
): ExoPlayer =
    mockk(relaxed = true) {
        every { getMediaItemCount() } returns mediaItemCount
        every { getCurrentMediaItem() } returns mockk<MediaItem>()
        every { getCurrentMediaItemIndex() } returns currentIndex
    }

/** Wires a controller whose dependencies are all overridable per test. */
internal class PreloadHarness(
    val scope: CoroutineScope,
    val player: ExoPlayer? = null,
    var currentVideoId: String? = "current",
    var target: PreloadTarget? = null,
    var isLooping: Boolean = false,
    var isLive: Boolean = false,
    val resolve: suspend (Video) -> ResolvedStreamData? = { null },
    val source: MediaSource? = mockk(relaxed = true),
    val downloaded: Set<String> = emptySet(),
    val gone: Set<String> = emptySet(),
    var foreground: Boolean = false,
    var dataSaver: Boolean = false,
) {
    val preloadConfiguration = ExoPlayer.PreloadConfiguration(3_000_000L)
    val logs = mutableListOf<String>()
    var resolveCalls = 0

    val controller =
        GaplessPreloadController(
            scope = scope,
            player = { player },
            context = { mockk(relaxed = true) },
            currentVideoId = { currentVideoId },
            nextTarget = { target },
            isLooping = { isLooping },
            isLiveStream = { isLive },
            resolveStreams = { v, _ ->
                resolveCalls++
                resolve(v)
            },
            hasLocalCopy = { it.id in downloaded },
            buildMediaSource = { _, _ -> source },
            isForegroundVideo = { foreground },
            isDataSaverOn = { dataSaver },
            nextVideoPreload = { preloadConfiguration },
            log = { logs += it },
            isGone = { it in gone },
        )
}
