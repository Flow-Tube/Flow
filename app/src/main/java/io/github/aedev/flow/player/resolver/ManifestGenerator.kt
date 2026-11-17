package io.github.aedev.flow.player.resolver

import android.util.Log
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.Stream

object ManifestGenerator {
    private const val TAG = "ManifestGenerator"

    fun generateProgressiveManifest(
        stream: Stream,
        itagItem: ItagItem,
        durationSeconds: Long,
    ): String? =
        try {
            ProgressiveDashManifest.create(stream.content, itagItem.manifestInput(), durationSeconds)
                ?: run {
                    Log.w(TAG, "Progressive manifest cannot describe itag=${itagItem.id}")
                    null
                }
        } catch (e: Exception) {
            Log.w(TAG, "Progressive manifest generation failed for itag=${itagItem.id}: ${e.javaClass.simpleName}: ${e.message}")
            null
        }

    private fun ItagItem.manifestInput() =
        ProgressiveDashManifest.Input(
            itag = id,
            isAudio = itagType == ItagItem.ItagType.AUDIO,
            containerMimeType = mediaFormat?.mimeType,
            codec = codec,
            bitrate = bitrate,
            width = width,
            height = height,
            fps = fps,
            audioChannels = audioChannels,
            initStart = initStart,
            initEnd = initEnd,
            indexStart = indexStart,
            indexEnd = indexEnd,
            approxDurationMs = approxDurationMs,
        )
}
