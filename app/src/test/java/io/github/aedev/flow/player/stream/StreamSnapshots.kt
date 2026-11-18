package io.github.aedev.flow.player.stream

import androidx.media3.exoplayer.dash.manifest.DashManifest
import io.github.aedev.flow.data.shorts.ShortsDashManifest
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.innertube.models.response.VideoChapter
import io.github.aedev.flow.player.resolver.AdaptiveDashManifest
import io.github.aedev.flow.player.resolver.ManifestGenerator
import io.github.aedev.flow.player.stream.model.AudioStream
import io.github.aedev.flow.player.stream.model.VideoStream
import io.github.aedev.flow.ui.components.videoplayer.controls.ScrubInfo
import io.github.aedev.flow.ui.components.videoplayer.controls.scrubInfoAt
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.applyChapters

/*
 * The one place the stream, manifest and chapter safety-net tests touch the extractor's types.
 *
 * Every test feeds InnerTube formats in and asserts these plain snapshots, so swapping the app's
 * stream model rewrites this file and nothing else: a test that then fails is a real regression.
 */

/** Every field the app reads off a converted video stream. */
internal data class VideoSnapshot(
    val id: String,
    val url: String,
    val itag: Int,
    val containerMime: String,
    val label: String,
    val videoOnly: Boolean,
    val codec: String?,
    val bitrate: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val quality: String?,
    val initStart: Int,
    val initEnd: Int,
    val indexStart: Int,
    val indexEnd: Int,
    val contentLength: Long,
    val approxDurationMs: Long,
    val lastModified: Long,
    val described: Boolean,
)

/** Every field the app reads off a converted audio stream. */
internal data class AudioSnapshot(
    val id: String,
    val url: String,
    val itag: Int,
    val containerMime: String,
    val averageBitrate: Int,
    val bitrate: Int,
    val codec: String?,
    val initStart: Int,
    val initEnd: Int,
    val indexStart: Int,
    val indexEnd: Int,
    val contentLength: Long,
    val approxDurationMs: Long,
    val lastModified: Long,
    val sampleRate: Int,
    val channels: Int,
    val trackId: String?,
    val trackName: String?,
    val trackKind: String?,
    val locale: String?,
    val described: Boolean,
)

/** What InnerTubeVideoMapper.streamType reports, by name: VIDEO_STREAM, LIVE_STREAM or POST_LIVE_STREAM. */
internal fun liveKindOf(result: InnerTubeVideoStreamExtractor.VideoExtractionResult): String = InnerTubeVideoMapper.streamType(result).name

internal fun convertVideo(formats: List<Format>): List<VideoSnapshot> =
    InnerTubeStreamBridge.convertVideoFormats(formats).map {
        it.snapshot()
    }

internal fun convertAudio(formats: List<Format>): List<AudioSnapshot> =
    InnerTubeStreamBridge.convertAudioFormats(formats).map {
        it.snapshot()
    }

/** The progressive DASH manifest the player builds for one video format, or null when it cannot. */
internal fun progressiveVideoManifest(
    format: Format,
    durationSeconds: Long,
): String? {
    val stream = InnerTubeStreamBridge.convertVideoFormats(listOf(format)).singleOrNull() ?: return null
    val itag = stream.itagItem ?: return null
    return ManifestGenerator.generateProgressiveManifest(stream, itag, durationSeconds)
}

/** The progressive DASH manifest the player builds for one audio format, or null when it cannot. */
internal fun progressiveAudioManifest(
    format: Format,
    durationSeconds: Long,
): String? {
    val stream = InnerTubeStreamBridge.convertAudioFormats(listOf(format)).singleOrNull() ?: return null
    val itag = stream.itagItem ?: return null
    return ManifestGenerator.generateProgressiveManifest(stream, itag, durationSeconds)
}

/** ShortsDashManifest.forStream over the converted stream, as the NewPipe-leg callers use it. */
internal fun shortsManifestForConvertedVideo(
    format: Format,
    durationMs: Long?,
): String? =
    InnerTubeStreamBridge
        .convertVideoFormats(listOf(format))
        .singleOrNull()
        ?.let { ShortsDashManifest.forStream(it, durationMs) }

internal fun shortsManifestForConvertedAudio(
    format: Format,
    durationMs: Long?,
): String? =
    InnerTubeStreamBridge
        .convertAudioFormats(listOf(format))
        .singleOrNull()
        ?.let { ShortsDashManifest.forStream(it, durationMs) }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun adaptiveCanDescribe(format: Format): Boolean =
    InnerTubeStreamBridge
        .convertVideoFormats(listOf(format))
        .single()
        .let(AdaptiveDashManifest::canDescribe)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun adaptiveManifest(
    formats: List<Format>,
    durationSeconds: Long,
): DashManifest? = AdaptiveDashManifest.build(InnerTubeStreamBridge.convertVideoFormats(formats), durationSeconds)

/** A chapter as the player state holds it, read back as plain values. */
internal data class ChapterSnapshot(
    val title: String,
    val startSeconds: Int,
    val previewUrl: String?,
)

internal fun VideoPlayerUiState.chapterSnapshots(): List<ChapterSnapshot> =
    chapters.map { ChapterSnapshot(it.title, it.startTimeSeconds, it.thumbnailUrl) }

/** Chapters in the player state's shape, built the way the watch response feeds them in. */
internal fun stateWithChapters(chapters: List<VideoChapter>): VideoPlayerUiState = VideoPlayerUiState().applyChapters(chapters)

internal fun scrubInfoFor(
    positionMs: Long,
    chapters: List<VideoChapter>,
): ScrubInfo = scrubInfoAt(positionMs, stateWithChapters(chapters).chapters, emptyList(), null)

/**
 * FlowChaptersBottomSheet's private `chapterStartMs`, reached by reflection because the sheet keeps
 * it private and the safety net may not change production code.
 */
internal fun chapterStartMs(
    chapters: List<VideoChapter>,
    index: Int,
): Long? {
    val method =
        Class
            .forName("io.github.aedev.flow.ui.components.videoplayer.sheet.FlowChaptersBottomSheetKt")
            .getDeclaredMethod("chapterStartMs", List::class.java, Int::class.javaPrimitiveType)
    method.isAccessible = true
    return method.invoke(null, stateWithChapters(chapters).chapters, index) as Long?
}

private fun VideoStream.snapshot(): VideoSnapshot {
    val item = itagItem
    return VideoSnapshot(
        id = id,
        url = content,
        itag = itag,
        containerMime = format!!.mimeType,
        label = resolution,
        videoOnly = isVideoOnly,
        codec = codec,
        bitrate = bitrate,
        width = width,
        height = height,
        fps = fps,
        quality = quality,
        initStart = initStart,
        initEnd = initEnd,
        indexStart = indexStart,
        indexEnd = indexEnd,
        contentLength = item?.contentLength ?: -1L,
        approxDurationMs = item?.approxDurationMs ?: -1L,
        lastModified = item?.lastModified ?: -1L,
        described = item != null,
    )
}

private fun AudioStream.snapshot(): AudioSnapshot {
    val item = itagItem
    return AudioSnapshot(
        id = id,
        url = content,
        itag = itag,
        containerMime = format!!.mimeType,
        averageBitrate = averageBitrate,
        bitrate = bitrate,
        codec = codec,
        initStart = initStart,
        initEnd = initEnd,
        indexStart = indexStart,
        indexEnd = indexEnd,
        contentLength = item?.contentLength ?: -1L,
        approxDurationMs = item?.approxDurationMs ?: -1L,
        lastModified = item?.lastModified ?: -1L,
        sampleRate = item?.sampleRate ?: -1,
        channels = item?.audioChannels ?: -1,
        trackId = audioTrackId,
        trackName = audioTrackName,
        trackKind = audioTrackType?.name,
        locale = audioLocale?.toLanguageTag(),
        described = item != null,
    )
}
