package io.github.aedev.flow.player.stream

import io.github.aedev.flow.innertube.models.response.AudioXTags
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format

/**
 * YouTube ships every audio itag of a track in several copies: the normal one, a DRC
 * (dynamic-range-compressed, "Stable volume") one, and sometimes copies marked by other `xtags`
 * keys such as `vb`. Their bitrates differ by a few bytes/s, so every "highest bitrate wins" pick
 * downstream lands on whichever copy happens to be a hair bigger unless the copies are thinned
 * here first. Order is preserved so default-track selection is unaffected.
 */
internal object AudioVariantPolicy {
    private val PLAIN_KEYS = setOf("acont", "lang")
    private val DRC_KEYS = PLAIN_KEYS + "drc"

    private enum class Variant { PLAIN, DRC, OTHER }

    private val NORMAL_ORDER = listOf(Variant.PLAIN, Variant.DRC, Variant.OTHER)
    private val STABLE_ORDER = listOf(Variant.DRC, Variant.PLAIN, Variant.OTHER)

    private fun variantOf(format: Format): Variant {
        val keys =
            format.xtags
                ?.takeIf { it.isNotBlank() }
                ?.let(AudioXTags::decode)
                ?.keys
                .orEmpty()
        return when {
            format.isDynamicRangeCompressed -> if (DRC_KEYS.containsAll(keys)) Variant.DRC else Variant.OTHER
            PLAIN_KEYS.containsAll(keys) -> Variant.PLAIN
            else -> Variant.OTHER
        }
    }

    fun select(
        formats: List<Format>,
        stable: Boolean,
    ): List<Format> {
        val kept =
            formats
                .filter { it.isAudio }
                .groupBy { it.itag to it.audioTrack?.id }
                .values
                .flatMapTo(mutableSetOf()) { group ->
                    val byVariant = group.groupBy(::variantOf)
                    (if (stable) STABLE_ORDER else NORMAL_ORDER).firstNotNullOf { byVariant[it] }
                }
        return formats.filter { !it.isAudio || it in kept }
    }

    fun hasStableVariant(
        formats: List<Format>,
        audioTrackId: String?,
    ): Boolean = formats.any { it.isAudio && it.audioTrack?.id == audioTrackId && variantOf(it) == Variant.DRC }
}

/** One copy per itag and track: the normal one, or the DRC one when [stable] and the track has it. */
internal fun List<Format>.selectAudioVariants(stable: Boolean = false): List<Format> = AudioVariantPolicy.select(this, stable)
