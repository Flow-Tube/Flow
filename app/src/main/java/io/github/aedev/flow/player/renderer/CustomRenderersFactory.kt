package io.github.aedev.flow.player.renderer

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.player.renderer.subtitle.FlowSubtitleDecoderFactory
import io.github.aedev.flow.player.subtitle.SubtitleDelay
import java.util.ArrayList

/**
 * A [DefaultRenderersFactory] that builds the video renderer itself and optionally installs custom
 * [AudioProcessor]s (e.g. the parametric EQ) into the audio sink.
 */
open class CustomRenderersFactory(
    context: Context,
    private val audioProcessors: Array<AudioProcessor> = emptyArray(),
    private val subtitleDelay: SubtitleDelay = SubtitleDelay(),
) : DefaultRenderersFactory(context) {
    private val subtitleDecoderFactory = FlowSubtitleDecoderFactory(subtitleDelay)

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        if (audioProcessors.isEmpty()) {
            return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        }
        return DefaultAudioSink
            .Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(audioProcessors)
            .build()
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        // Mirrors DefaultRenderersFactory.buildVideoRenderers for the MediaCodec renderer alone; this
        // override is the reason setEnableMediaCodecVideoRendererDurationToProgressUs on the factory
        // does nothing for us, so the flag is applied here instead.
        out.add(
            MediaCodecVideoRenderer
                .Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                .setEnableDurationToProgressUs(PlayerConfig.ENABLE_DYNAMIC_SCHEDULING)
                .build(),
        )
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            TextRenderer(output, outputLooper, subtitleDecoderFactory).apply {
                experimentalSetLegacyDecodingEnabled(true)
            },
        )
    }
}
