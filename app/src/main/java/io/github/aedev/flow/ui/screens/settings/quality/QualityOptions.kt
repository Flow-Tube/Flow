package io.github.aedev.flow.ui.screens.settings.quality

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.MusicAudioQuality
import io.github.aedev.flow.data.local.ThumbnailQuality
import io.github.aedev.flow.data.local.VideoCodec
import io.github.aedev.flow.data.local.VideoQuality

/** Resolutions offered for regular video and Shorts, best first. */
internal val VideoQualities =
    listOf(
        VideoQuality.AUTO,
        VideoQuality.Q_2160P,
        VideoQuality.Q_1440P,
        VideoQuality.Q_1080P,
        VideoQuality.Q_720P,
        VideoQuality.Q_480P,
        VideoQuality.Q_360P,
        VideoQuality.Q_240P,
        VideoQuality.Q_144P,
    )

internal val MusicQualities = MusicAudioQuality.entries.toList()

internal val ThumbnailQualities = ThumbnailQuality.entries.toList()

@StringRes
internal fun thumbnailQualityLabel(quality: ThumbnailQuality): Int =
    when (quality) {
        ThumbnailQuality.HIGH -> R.string.thumbnail_quality_high
        ThumbnailQuality.MEDIUM -> R.string.thumbnail_quality_medium
        ThumbnailQuality.LOW -> R.string.thumbnail_quality_low
        ThumbnailQuality.OFF -> R.string.thumbnail_quality_off
    }

@StringRes
internal fun thumbnailQualityDescription(quality: ThumbnailQuality): Int =
    when (quality) {
        ThumbnailQuality.HIGH -> R.string.thumbnail_quality_high_desc
        ThumbnailQuality.MEDIUM -> R.string.thumbnail_quality_medium_desc
        ThumbnailQuality.LOW -> R.string.thumbnail_quality_low_desc
        ThumbnailQuality.OFF -> R.string.thumbnail_quality_off_desc
    }

@StringRes
internal fun videoQualityLabel(quality: VideoQuality): Int =
    when (quality) {
        VideoQuality.AUTO -> R.string.quality_auto
        VideoQuality.Q_144P -> R.string.quality_144p
        VideoQuality.Q_240P -> R.string.quality_240p
        VideoQuality.Q_360P -> R.string.quality_360p
        VideoQuality.Q_480P -> R.string.quality_480p
        VideoQuality.Q_720P -> R.string.quality_720p_hd
        VideoQuality.Q_1080P -> R.string.quality_1080p_full_hd
        VideoQuality.Q_1440P -> R.string.quality_1440p_qhd
        VideoQuality.Q_2160P -> R.string.quality_2160p_4k
    }

@StringRes
internal fun musicQualityLabel(quality: MusicAudioQuality): Int =
    when (quality) {
        MusicAudioQuality.AUTO -> R.string.music_quality_auto
        MusicAudioQuality.HIGH -> R.string.music_quality_high
        MusicAudioQuality.MEDIUM -> R.string.music_quality_medium
        MusicAudioQuality.LOW -> R.string.music_quality_low
    }

/** The codecs the setting lists: AV1 only on a device that decodes it in hardware, or while it is [selected]. */
internal fun codecChoices(
    hardwareAv1: Boolean,
    selected: VideoCodec,
): List<VideoCodec> = VideoCodec.entries.filter { it != VideoCodec.AV1 || hardwareAv1 || it == selected }

/** The fallback choices for [preferred]: every listed codec but the preferred one. */
internal fun fallbackCodecs(
    preferred: VideoCodec,
    choices: List<VideoCodec> = VideoCodec.entries,
): List<VideoCodec> = choices.filter { it != preferred }

/** A fallback equal to the new preferred codec is meaningless, so it drops back to automatic. */
internal fun fallbackAfterPreferredChange(
    preferred: VideoCodec,
    fallback: VideoCodec,
): VideoCodec = if (fallback == preferred) VideoCodec.AUTO else fallback

/** Codec names are product names and stay as they are; only "automatic" is translated. */
@Composable
internal fun codecLabel(codec: VideoCodec): String =
    if (codec == VideoCodec.AUTO) stringResource(R.string.player_settings_video_codec_fallback_auto) else codec.label
