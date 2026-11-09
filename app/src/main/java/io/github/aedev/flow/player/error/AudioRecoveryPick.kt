package io.github.aedev.flow.player.error

import io.github.aedev.flow.player.stream.StreamProcessor
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream

/**
 * The stream to retry after an audio decoder failure: another format of the track that failed,
 * the other codec first, so a broken AAC decoder moves to Opus and the reverse. Every dub shares
 * one itag and bitrate, so a pick across tracks lands on an arbitrary language; none is made.
 */
internal object AudioRecoveryPick {
    fun pick(
        streams: List<AudioStream>,
        failed: AudioStream,
        failedUrls: Set<String>,
    ): AudioStream? {
        val failedIsOpus = failed.isOpus()
        // With the failed stream in the pool, streamsOfTrack always finds its track and never falls back to another.
        return StreamProcessor
            .streamsOfTrack(streams + failed, failed)
            .filter { it.getContent() != failed.getContent() && it.getContent() !in failedUrls }
            .sortedWith(compareBy<AudioStream> { it.isOpus() == failedIsOpus }.thenByDescending { it.averageBitrate })
            .firstOrNull()
    }

    private fun AudioStream.isOpus(): Boolean =
        when (format) {
            MediaFormat.WEBMA_OPUS, MediaFormat.OPUS, MediaFormat.WEBMA -> true
            else -> codec?.contains("opus", ignoreCase = true) == true
        }
}
