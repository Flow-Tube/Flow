package io.github.aedev.flow.player.shorts

import androidx.media3.exoplayer.dash.manifest.DashManifest

/** What each pooled player holds, kept together so a slot is assigned and cleared as one. */
internal class ShortsSlots(
    size: Int,
) {
    /** The absolute list position that owns each slot. */
    val ownerIndices = arrayOfNulls<Int>(size)
    val videoIds = arrayOfNulls<String>(size)

    // The last streams per slot, so audio or quality can be swapped without a new lookup.
    val videoUrls = arrayOfNulls<String>(size)
    val audioUrls = arrayOfNulls<String?>(size)
    val videoManifests = arrayOfNulls<String?>(size)
    val audioManifests = arrayOfNulls<String?>(size)
    val adaptiveManifests = arrayOfNulls<DashManifest?>(size)

    /** YouTube's loudness for each slot's reel, in dB; null when its response carried none. */
    val loudnessGainsDb = arrayOfNulls<Float>(size)

    fun owns(
        slot: Int,
        index: Int,
        videoId: String,
    ): Boolean = ownerIndices[slot] == index && videoIds[slot] == videoId

    fun assign(
        slot: Int,
        index: Int,
        videoId: String,
        videoUrl: String,
        audioUrl: String?,
        videoManifest: String?,
        audioManifest: String?,
        loudnessGainDb: Float? = null,
        adaptiveManifest: DashManifest? = null,
    ) {
        ownerIndices[slot] = index
        videoIds[slot] = videoId
        videoUrls[slot] = videoUrl
        audioUrls[slot] = audioUrl
        videoManifests[slot] = videoManifest
        audioManifests[slot] = audioManifest
        loudnessGainsDb[slot] = loudnessGainDb
        adaptiveManifests[slot] = adaptiveManifest
    }

    fun clear(slot: Int) {
        ownerIndices[slot] = null
        videoIds[slot] = null
        videoUrls[slot] = null
        audioUrls[slot] = null
        videoManifests[slot] = null
        audioManifests[slot] = null
        loudnessGainsDb[slot] = null
        adaptiveManifests[slot] = null
    }
}
