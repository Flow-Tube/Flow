package io.github.aedev.flow.data.lyrics

import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.repository.TimedSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val CACHE_SIZE = 32
private const val MILLIS_PER_SECOND = 1000.0

/** A segment voted on for a cut this far from the playing video's length no longer fits it. */
private const val MAX_DURATION_GAP_SECONDS = 5.0

/**
 * Fits lyrics to music videos that have parts with no song playing, using SponsorBlock. The player
 * screen and the music service both ask for the current song and share one lookup per video.
 */
@Singleton
class MusicVideoLyricsTiming
    @Inject
    constructor(
        preferences: PlayerPreferences,
        private val sponsorBlock: SponsorBlockRepository,
    ) {
        val enabled: Flow<Boolean> = preferences.lyricsSponsorBlockTiming

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val inFlight = InFlightRuns<String, List<TimedSegment>?>(scope)
        private val cache: MutableMap<String, List<TimedSegment>> =
            Collections.synchronizedMap(
                object : LinkedHashMap<String, List<TimedSegment>>(CACHE_SIZE, 0.75f, true) {
                    override fun removeEldestEntry(eldest: Map.Entry<String, List<TimedSegment>>) = size > CACHE_SIZE
                },
            )

        /** [entries] on the video's clock, or unchanged when the setting is off. */
        suspend fun fit(
            videoId: String,
            entries: List<LyricsEntry>,
            durationSeconds: Int,
        ): List<LyricsEntry> = if (enabled.first()) lookUpAndFit(videoId, entries, durationSeconds) else entries

        /** A failed lookup leaves the lyrics as they are, and the next song or setting change tries again. */
        suspend fun lookUpAndFit(
            videoId: String,
            entries: List<LyricsEntry>,
            durationSeconds: Int,
        ): List<LyricsEntry> {
            val segments =
                cache[videoId]
                    ?: inFlight.join(videoId) { sponsorBlock.getNonMusicSegments(videoId)?.also { cache[videoId] = it } }
                    ?: return entries
            val fitting = segments.filter { it.fits(durationSeconds) }
            val parts = fitting.map { NonMusicPart(it.startSeconds.toMillis(), it.endSeconds.toMillis()) }
            val videoSeconds =
                if (durationSeconds >
                    0
                ) {
                    durationSeconds.toDouble()
                } else {
                    fitting.maxOfOrNull { it.videoDurationSeconds } ?: 0.0
                }
            return onVideoClock(entries, parts, videoSeconds.toMillis())
        }

        private fun Double.toMillis(): Long = (this * MILLIS_PER_SECOND).toLong()

        private fun TimedSegment.fits(durationSeconds: Int): Boolean =
            durationSeconds <= 0 || videoDurationSeconds <= 0.0 || abs(videoDurationSeconds - durationSeconds) <= MAX_DURATION_GAP_SECONDS
    }
