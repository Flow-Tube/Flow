package io.github.aedev.flow.ui.screens.settings.quality

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.MusicAudioQuality
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.ThumbnailQuality
import io.github.aedev.flow.data.local.VideoCodec
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.player.quality.VideoDecoderSupport
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

@HiltViewModel
@OptIn(UnstableApi::class)
class QualitySettingsViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        val videoWifi = preferences.defaultQualityWifi.asState(VideoQuality.AUTO)
        val videoMobile = preferences.defaultQualityCellular.asState(VideoQuality.AUTO)
        val rememberVideo = preferences.rememberVideoQuality.asState(false)
        val lowerWhenSmall = preferences.lowerQualityWhenSmall.asState(true)
        val shortsWifi = preferences.shortsQualityWifi.asState(VideoQuality.Q_720P)
        val shortsMobile = preferences.shortsQualityCellular.asState(VideoQuality.Q_480P)
        val music = preferences.musicAudioQuality.asState(MusicAudioQuality.AUTO)
        val thumbnailsWifi = preferences.thumbnailQualityWifi.asState(ThumbnailQuality.HIGH)
        val thumbnailsMobile = preferences.thumbnailQualityCellular.asState(ThumbnailQuality.HIGH)
        val codec = preferences.defaultVideoCodec.asState(VideoCodec.H264)
        val fallbackCodec = preferences.fallbackVideoCodec.asState(VideoCodec.AUTO)
        val hardwareAv1 =
            flow { emit(VideoDecoderSupport.hasHardwareDecoder(MimeTypes.VIDEO_AV1)) }
                .flowOn(Dispatchers.Default)
                .asState(false)

        fun setVideoWifi(value: VideoQuality) = write { preferences.setDefaultQualityWifi(value) }

        fun setVideoMobile(value: VideoQuality) = write { preferences.setDefaultQualityCellular(value) }

        fun setRememberVideo(enabled: Boolean) = write { preferences.setRememberVideoQuality(enabled) }

        fun setLowerWhenSmall(enabled: Boolean) = write { preferences.setLowerQualityWhenSmall(enabled) }

        fun setShortsWifi(value: VideoQuality) = write { preferences.setShortsQualityWifi(value) }

        fun setShortsMobile(value: VideoQuality) = write { preferences.setShortsQualityCellular(value) }

        fun setMusic(value: MusicAudioQuality) = write { preferences.setMusicAudioQuality(value) }

        fun setThumbnailsWifi(value: ThumbnailQuality) = write { preferences.setThumbnailQualityWifi(value) }

        fun setThumbnailsMobile(value: ThumbnailQuality) = write { preferences.setThumbnailQualityCellular(value) }

        fun setCodec(value: VideoCodec) =
            write {
                preferences.setDefaultVideoCodec(value)
                val fallback = fallbackAfterPreferredChange(value, fallbackCodec.value)
                if (fallback != fallbackCodec.value) preferences.setFallbackVideoCodec(fallback)
            }

        fun setFallbackCodec(value: VideoCodec) = write { preferences.setFallbackVideoCodec(value) }
    }
