package io.github.aedev.flow.player.stream

import io.github.aedev.flow.data.local.MusicAudioQuality
import io.github.aedev.flow.player.stream.model.AudioStream
import io.github.aedev.flow.player.stream.model.AudioTrackType
import java.util.Locale
import kotlin.math.abs

object AudioStreamSelector {
    fun selectPreferredAudioStream(
        streams: List<AudioStream>,
        preferredAudioLanguage: String,
        preferredMusicAudioQuality: MusicAudioQuality = MusicAudioQuality.AUTO,
        compatibilityFilter: ((AudioStream) -> Boolean)? = null,
    ): AudioStream? {
        if (streams.isEmpty()) return null

        val compatibleStreams =
            compatibilityFilter
                ?.let { filter -> streams.filter(filter).ifEmpty { streams } }
                ?: streams

        val preferredCandidates = preferredCandidates(compatibleStreams, preferredAudioLanguage)
        return selectByQuality(preferredCandidates, preferredMusicAudioQuality)
            ?: selectByQuality(compatibleStreams, preferredMusicAudioQuality)
    }

    private fun selectByQuality(
        streams: List<AudioStream>,
        preferredMusicAudioQuality: MusicAudioQuality,
    ): AudioStream? {
        if (streams.isEmpty()) return null

        val streamsWithKnownBitrate = streams.filter { it.audioBitrate() > 0 }.ifEmpty { streams }
        return when (preferredMusicAudioQuality) {
            MusicAudioQuality.AUTO,
            MusicAudioQuality.HIGH,
            -> streamsWithKnownBitrate.maxByOrNull { it.audioBitrate() }

            MusicAudioQuality.MEDIUM -> streamsWithKnownBitrate.minByOrNull { abs(it.audioBitrate() - MEDIUM_BITRATE_TARGET) }

            MusicAudioQuality.LOW -> streamsWithKnownBitrate.minByOrNull { it.audioBitrate() }
        }
    }

    private fun AudioStream.audioBitrate(): Int = averageBitrate.takeIf { it > 0 } ?: bitrate

    private fun preferredCandidates(
        streams: List<AudioStream>,
        preferredAudioLanguage: String,
    ): List<AudioStream> {
        val normalizedPreference = preferredAudioLanguage.trim().lowercase(Locale.ROOT)

        if (normalizedPreference.isBlank() || normalizedPreference == "original") {
            val originals = streams.filter { it.audioTrackType == AudioTrackType.ORIGINAL }
            if (originals.isNotEmpty()) return originals

            val nonDubbed = streams.filter { it.audioTrackType != AudioTrackType.DUBBED }
            if (nonDubbed.isNotEmpty()) return nonDubbed

            return streams
        }

        val languageMatches =
            streams.filter { stream ->
                languageMatches(stream.audioLocale?.toLanguageTag(), normalizedPreference)
            }
        if (languageMatches.isNotEmpty()) return languageMatches

        val originals = streams.filter { it.audioTrackType == AudioTrackType.ORIGINAL }
        if (originals.isNotEmpty()) return originals

        val nonDubbed = streams.filter { it.audioTrackType != AudioTrackType.DUBBED }
        if (nonDubbed.isNotEmpty()) return nonDubbed

        return streams
    }

    /**
     * True when [languageTag] is [preference] itself or a regional form of it ("pt" matches "pt-BR").
     * Track display names are never matched: "French" contains "en" and "Chinese" contains "hi".
     */
    fun languageMatches(
        languageTag: String?,
        preference: String,
    ): Boolean {
        if (languageTag.isNullOrBlank() || preference.isBlank()) return false
        return languageTag.equals(preference, ignoreCase = true) ||
            languageTag.startsWith("$preference-", ignoreCase = true)
    }

    private const val MEDIUM_BITRATE_TARGET = 128_000
}
