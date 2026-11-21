package io.github.aedev.flow.player.preview

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.player.cache.SharedPlayerCacheProvider
import io.github.aedev.flow.player.cache.StreamIdentityCacheKeyFactory
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.player.datasource.YouTubeHttpDataSource
import io.github.aedev.flow.player.resolver.MediaSourceBuilder
import javax.inject.Inject

/** What the one preview player reports back to whoever drives it. */
internal interface FeedPreviewPlayerListener {
    fun onFirstFrame()

    fun onBufferingChanged(buffering: Boolean)

    fun onPlayingChanged(playing: Boolean)

    fun onEnded()

    fun onError()
}

/**
 * The one muted preview player for Home's feed. Separate from the main, music and Shorts players:
 * no media session, no notification, no wake lock, and audio focus only while unmuted. Built on the
 * first [load] and gone after [release]; the main thread owns every call, as ExoPlayer requires.
 */
@OptIn(UnstableApi::class)
internal class FeedPreviewPlayer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private var player: ExoPlayer? = null
        private var trackSelector: DefaultTrackSelector? = null
        var listener: FeedPreviewPlayerListener? = null

        /** The player a surface attaches to, null until the first [load] and after [release]. */
        val current: Player? get() = player

        val positionMs: Long get() = player?.currentPosition ?: 0L

        fun load(
            media: FeedPreviewMedia,
            startMs: Long,
            muted: Boolean,
            captions: Boolean,
        ) {
            val exo = player ?: build().also { player = it }
            setCaptions(captions)
            exo.setMediaSource(mediaSource(media), startMs.coerceAtLeast(0L))
            setMuted(muted)
            exo.playWhenReady = true
            exo.prepare()
        }

        /** Unmuting takes audio focus, as any player with sound does; muting again gives it back. */
        fun setMuted(muted: Boolean) {
            val exo = player ?: return
            exo.setAudioAttributes(AUDIO_ATTRIBUTES, !muted)
            exo.volume = if (muted) 0f else 1f
        }

        fun setCaptions(on: Boolean) {
            val selector = trackSelector ?: return
            selector.setParameters(selector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on))
        }

        fun seekTo(positionMs: Long) {
            player?.seekTo(positionMs.coerceAtLeast(0L))
        }

        /** Stops decoding and drops the media, keeping the player for the next card. */
        fun stop() {
            val exo = player ?: return
            exo.stop()
            exo.clearMediaItems()
            exo.setAudioAttributes(AUDIO_ATTRIBUTES, false)
        }

        fun release() {
            val exo = player ?: return
            player = null
            trackSelector = null
            exo.release()
        }

        private fun build(): ExoPlayer {
            val selector =
                DefaultTrackSelector(context).apply {
                    setParameters(
                        buildUponParameters()
                            .setMaxVideoSize(MAX_WIDTH, MAX_HEIGHT)
                            .setPreferredVideoMimeTypes(*PlayerConfig.PREFERRED_VIDEO_MIME_TYPES),
                    )
                }
            trackSelector = selector
            val loadControl =
                DefaultLoadControl
                    .Builder()
                    .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
                    .build()
            return ExoPlayer
                .Builder(context, PreviewRenderersFactory(context).setEnableDecoderFallback(true))
                .setTrackSelector(selector)
                .setLoadControl(loadControl)
                .setAudioAttributes(AUDIO_ATTRIBUTES, false)
                .setWakeMode(C.WAKE_MODE_NONE)
                .setHandleAudioBecomingNoisy(false)
                .build()
                .apply {
                    volume = 0f
                    repeatMode = Player.REPEAT_MODE_OFF
                    videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    addListener(events)
                }
        }

        private val events =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    listener?.onFirstFrame()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    listener?.onBufferingChanged(playbackState == Player.STATE_BUFFERING)
                    if (playbackState == Player.STATE_ENDED) listener?.onEnded()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    listener?.onPlayingChanged(isPlaying)
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "Preview failed: ${error.errorCodeName}")
                    listener?.onError()
                }
            }

        private fun mediaSource(media: FeedPreviewMedia): MediaSource {
            val streams = streamDataSource()
            val video = sourceFor(streams, media.videoUrl, media.videoDashManifest)
            val audio = media.audioUrl?.takeIf { it != media.videoUrl }?.let { sourceFor(streams, it, media.audioDashManifest) }
            val caption = media.caption?.let { captionSource(it.url, it.format.mimeType, it.languageTag) }
            val parts = listOfNotNull(video, audio, caption)
            return if (parts.size == 1) video else MergingMediaSource(true, true, *parts.toTypedArray())
        }

        private fun sourceFor(
            factory: DataSource.Factory,
            url: String,
            dashManifest: String?,
        ): MediaSource {
            if (dashManifest != null) {
                runCatching { return MediaSourceBuilder.buildDashSource(factory, dashManifest, Uri.parse(url)) }
                    .onFailure { Log.w(TAG, "Generated DASH manifest rejected, playing progressively: ${it.message}") }
            }
            return ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url))
        }

        private fun captionSource(
            url: String,
            mimeType: String,
            language: String,
        ): MediaSource {
            val config =
                MediaItem.SubtitleConfiguration
                    .Builder(Uri.parse(url))
                    .setMimeType(mimeType)
                    .setLanguage(language)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            return SingleSampleMediaSource
                .Factory(DefaultDataSource.Factory(context, YouTubeHttpDataSource.Factory()))
                // A caption that fails to load must not stop the video.
                .setTreatLoadErrorsAsEndOfStream(true)
                .createMediaSource(config, C.TIME_UNSET)
        }

        /** Through the video cache when it is open, under the same keys as the main player, so its bytes carry over to a tap. */
        private fun streamDataSource(): DataSource.Factory {
            val upstream = DefaultDataSource.Factory(context, YouTubeHttpDataSource.Factory())
            val cache = SharedPlayerCacheProvider.existing() ?: return upstream
            return CacheDataSource
                .Factory()
                .setCache(cache)
                .setCacheKeyFactory(StreamIdentityCacheKeyFactory)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

        /** Sidecar VTT reaches the text renderer unparsed, so it decodes it the legacy way, as the main player's renderers do. */
        private class PreviewRenderersFactory(
            context: Context,
        ) : DefaultRenderersFactory(context) {
            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>,
            ) {
                out.add(TextRenderer(output, outputLooper).apply { experimentalSetLegacyDecodingEnabled(true) })
            }
        }

        private companion object {
            const val TAG = "FeedPreviewPlayer"
            const val MAX_WIDTH = 854
            const val MAX_HEIGHT = 480
            const val MIN_BUFFER_MS = 5_000
            const val MAX_BUFFER_MS = 15_000
            const val START_BUFFER_MS = 1_000

            // Must stay at or under MIN_BUFFER_MS (#788).
            const val REBUFFER_MS = 2_000

            val AUDIO_ATTRIBUTES: AudioAttributes =
                AudioAttributes
                    .Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build()
        }
    }
