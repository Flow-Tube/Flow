package io.github.aedev.flow.player.shorts

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import io.github.aedev.flow.player.MediaButtonSessionCallback
import io.github.aedev.flow.player.analytics.PlaybackAnalyticsLogger
import io.github.aedev.flow.player.audio.eq.EqualizerAudioProcessor
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.player.config.VideoSizeCap
import io.github.aedev.flow.player.datasource.YouTubeHttpDataSource
import io.github.aedev.flow.player.factory.LoadControlFactory

/** Builds what the Shorts pool runs on: its players, the cached data source and the one media session. */
@OptIn(UnstableApi::class)
internal object ShortsPlayerFactory {
    private const val SESSION_ID = "flow_shorts_session"
    private const val TAG = "ShortsPlayerPool"

    fun cachedDataSourceFactory(
        appContext: Context,
        cache: Cache,
    ): DataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(appContext, YouTubeHttpDataSource.Factory()))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun mediaSession(
        context: Context,
        player: ExoPlayer,
    ): MediaSession? =
        try {
            MediaSession
                .Builder(context, player)
                .setId(SESSION_ID)
                .setCallback(MediaButtonSessionCallback)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Shorts MediaSession unavailable; media buttons will not reach the reel", e)
            null
        }

    fun player(
        context: Context,
        equalizer: EqualizerAudioProcessor,
        dataSourceFactory: DataSource.Factory,
        preferredAudioLanguage: String,
        currentVideoId: () -> String?,
    ): ExoPlayer {
        // The device cap is landscape; a Short is portrait, so its axes swap.
        val (maxVideoWidth, maxVideoHeight) = VideoSizeCap.forDevice(context).let { it.maxHeight to it.maxWidth }

        val loadControl = LoadControlFactory.forShorts()

        val trackSelector =
            DefaultTrackSelector(
                context,
                AdaptiveTrackSelection.Factory(),
            ).apply {
                val builder =
                    buildUponParameters()
                        .setPreferredVideoMimeTypes(*PlayerConfig.PREFERRED_VIDEO_MIME_TYPES)
                        .setAllowVideoMixedMimeTypeAdaptiveness(true)
                        .setForceHighestSupportedBitrate(false)
                        .setViewportSizeToPhysicalDisplaySize(context, true)
                        .setMaxVideoSize(maxVideoWidth, maxVideoHeight)

                if (preferredAudioLanguage != "original" && preferredAudioLanguage.isNotEmpty()) {
                    builder.setPreferredAudioLanguage(preferredAudioLanguage)
                }

                setParameters(builder.build())
            }

        // One processor per pooled player: they are all live at once and each keeps its own filter history.
        val renderersFactory =
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink =
                    DefaultAudioSink
                        .Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(arrayOf(equalizer))
                        .build()
            }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)

        return ExoPlayer
            .Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                false,
            ).setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = false
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                addAnalyticsListener(PlaybackAnalyticsLogger(TAG) { currentVideoId() })
                addListener(
                    object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            ShortsStartupTrace.onFirstFrame(currentVideoId())
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            ShortsStartupTrace.onState(
                                currentVideoId(),
                                when (playbackState) {
                                    Player.STATE_IDLE -> "IDLE"
                                    Player.STATE_BUFFERING -> "BUFFERING"
                                    Player.STATE_READY -> "READY"
                                    else -> "ENDED"
                                },
                            )
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            ShortsStartupTrace.onState(currentVideoId(), "ERROR ${error.errorCodeName}: ${error.message}")
                        }
                    },
                )
            }
    }
}
