package io.github.aedev.flow.player.resolver

import android.util.Log
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.Stream

object ManifestGenerator {
    private const val TAG = "ManifestGenerator"

    fun generateProgressiveManifest(
        stream: Stream,
        itagItem: ItagItem,
        durationSeconds: Long,
    ): String? =
        try {
            YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                stream.content,
                itagItem,
                durationSeconds,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Progressive manifest generation failed for itag=${itagItem.id}: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
}
