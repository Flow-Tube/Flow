package io.github.aedev.flow.player.shorts

import androidx.media3.exoplayer.dash.manifest.DashManifest

/** The streams a slot last loaded, so audio or quality can be swapped without a new lookup. */
internal data class ShortsSlotStreams(
    val videoUrl: String,
    val audioUrl: String?,
    val videoManifest: String? = null,
    val audioManifest: String? = null,
    /** YouTube's loudness for the reel, in dB; null when its response carried none. */
    val loudnessGainDb: Float? = null,
    val adaptiveManifest: DashManifest? = null,
)

/** What each pooled player holds, kept together so a slot is assigned and cleared as one. */
internal class ShortsSlots(
    size: Int,
) {
    /** The absolute list position that owns each slot. */
    val ownerIndices = arrayOfNulls<Int>(size)
    val videoIds = arrayOfNulls<String>(size)
    val streams = arrayOfNulls<ShortsSlotStreams>(size)

    fun owns(
        slot: Int,
        index: Int,
        videoId: String,
    ): Boolean = ownerIndices[slot] == index && videoIds[slot] == videoId

    fun assign(
        slot: Int,
        index: Int,
        videoId: String,
        streams: ShortsSlotStreams,
    ) {
        ownerIndices[slot] = index
        videoIds[slot] = videoId
        this.streams[slot] = streams
    }

    fun clear(slot: Int) {
        ownerIndices[slot] = null
        videoIds[slot] = null
        streams[slot] = null
    }
}
