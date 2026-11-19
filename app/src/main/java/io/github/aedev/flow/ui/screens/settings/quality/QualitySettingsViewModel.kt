package io.github.aedev.flow.ui.screens.settings.quality

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.MusicAudioQuality
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.ThumbnailQuality
import io.github.aedev.flow.data.local.VideoCodec
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import javax.inject.Inject

@HiltViewModel
class QualitySettingsViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        val videoWifi = preferences.defaultQualityWifi.asState(VideoQuality.AUTO)
        val videoMobile = preferences.defaultQualityCellular.asState(VideoQuality.AUTO)
        val rememberVideo = preferences.rememberVideoQuality.asState(false)
        val shortsWifi = preferences.shortsQualityWifi.asState(VideoQuality.Q_720P)
        val shortsMobile = preferences.shortsQualityCellular.asState(VideoQuality.Q_480P)
        val music = preferences.musicAudioQuality.asState(MusicAudioQuality.AUTO)
        val thumbnailsWifi = preferences.thumbnailQualityWifi.asState(ThumbnailQuality.HIGH)
        val thumbnailsMobile = preferences.thumbnailQualityCellular.asState(ThumbnailQuality.HIGH)
        val codec = preferences.defaultVideoCodec.asState(VideoCodec.H264)
        val fallbackCodec = preferences.fallbackVideoCodec.asState(VideoCodec.AUTO)

        fun setVideoWifi(value: VideoQuality) = write { preferences.setDefaultQualityWifi(value) }

        fun setVideoMobile(value: VideoQuality) = write { preferences.setDefaultQualityCellular(value) }

        fun setRememberVideo(enabled: Boolean) = write { preferences.setRememberVideoQuality(enabled) }

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
