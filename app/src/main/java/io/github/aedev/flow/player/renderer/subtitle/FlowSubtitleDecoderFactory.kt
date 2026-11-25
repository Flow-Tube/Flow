package io.github.aedev.flow.player.renderer.subtitle

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleDecoder
import io.github.aedev.flow.player.subtitle.SubtitleDelay

/**
 * Media3's decoders plus srv3 (YouTube's styled, positioned caption XML), with every track a
 * Media3 parser reads shifted by [subtitleDelay]. CEA-608/708 keep Media3's own decoders.
 */
@OptIn(UnstableApi::class)
internal class FlowSubtitleDecoderFactory(
    private val subtitleDelay: SubtitleDelay = SubtitleDelay(),
) : SubtitleDecoderFactory {
    private val parsers = DefaultSubtitleParserFactory()

    override fun supportsFormat(format: Format): Boolean =
        format.sampleMimeType == Srv3SubtitleParser.MIME_TYPE || SubtitleDecoderFactory.DEFAULT.supportsFormat(format)

    override fun createDecoder(format: Format): SubtitleDecoder =
        when {
            format.sampleMimeType == Srv3SubtitleParser.MIME_TYPE -> {
                DelayedSubtitleDecoder("Srv3SubtitleDecoder", Srv3SubtitleParser(), subtitleDelay)
            }

            parsers.supportsFormat(format) -> {
                DelayedSubtitleDecoder("Delayed${format.sampleMimeType}", parsers.create(format), subtitleDelay)
            }

            else -> {
                SubtitleDecoderFactory.DEFAULT.createDecoder(format)
            }
        }
}
