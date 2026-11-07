package io.github.aedev.flow.player.crossfade

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Blends the end of a song into the next one (#1675). Media3 plays one item at a time, so the last
 * seconds of the outgoing song move to a short-lived tail player while the session player goes on to
 * the next song. The session player is never swapped: the queue, the media session, the notification,
 * Android Auto and every listener keep the one player they have.
 *
 * The tail is built and buffered [CrossfadePolicy.PREPARE_LEAD_MS] early, starts muted
 * [CrossfadePolicy.ALIGN_WINDOW_MS] before the fade, and nudges its speed until its position matches
 * the session player's. The song changes hands the moment they match, since two players drift apart
 * again within a second, so the fade can start up to that window early; nothing repeats or skips.
 *
 * After a seek close to the end there is less time, so the tail starts as soon as it has buffered and
 * the fade shortens to what is left of the song. A tail that cannot line up before
 * [CrossfadePolicy.latestHandOffMs] is dropped and the song ends as it does without crossfade.
 *
 * A song the user skips away from has no tail waiting, so one is built from where the song is. A skip
 * from inside the app ([skipTo]) can wait for it: the session goes on playing the old song while the
 * tail starts muted, and changes song once the tail is playing, so the old song never goes quiet. This
 * runs with crossfade off too, as a [CrossfadePolicy.SKIP_BLEND_MS] overlap. A skip from the notification
 * or a headset has already changed the song by the time it gets here, so it only gets a tail while the
 * settings ask for a fade, and the next song stays muted until the tail plays. Either way the fade runs
 * once both are playing.
 *
 * Every step is driven by the players: [PlayerMessage]s at a position, their listeners for readiness,
 * seeks, skips and pauses. The only timed loops run while a song is changing hands: [TailAligner]'s
 * checks for a few seconds, then [FADE_STEP_MS] volume steps for the fade.
 */
internal class MusicCrossfader(
    private val session: ExoPlayer,
    private val buildTail: (skipOverlapMs: Long?) -> ExoPlayer,
    private val moment: (settings: CrossfadePolicy.Settings) -> CrossfadePolicy.Moment,
    private val onSessionScaleChanged: () -> Unit,
) {
    private enum class Stage { IDLE, PREPARED, WAITING_FOR_TAIL, ALIGNING, SKIP_STARTING, WAITING_FOR_NEXT_SONG, FADING }

    private data class SkipTarget(
        val index: Int,
        val mediaId: String,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var stage = Stage.IDLE
    private var settings = CrossfadePolicy.Settings()

    private var tail: ExoPlayer? = null
    private var prepareMessage: PlayerMessage? = null
    private var alignMessage: PlayerMessage? = null
    private var mediaId: String? = null
    private var fadeBeganAtMs = 0L
    private var fadeClockMs = 1L
    private var fadeProgress = 0f
    private var skipPressedAtMs = 0L
    private var skipFade = false

    /** Where a skip goes once the tail plays; the session stays on the old song until then. */
    private var heldTarget: SkipTarget? = null
    private var skipLeadMs = DEFAULT_SKIP_LEAD_MS
    private var tailGain = 1f
    private var handingOff = false
    private val aligner =
        TailAligner(
            session = session,
            handler = handler,
            tail = { tail },
            onTailStarted = { stage = Stage.ALIGNING },
            onAligned = ::handOff,
            onGaveUp = ::abort,
        )

    /** What the session player's own volume is multiplied by: below 1 only while the next song fades in. */
    var sessionScale = 1f
        private set

    private val listener =
        object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                if (handingOff) return
                if (skipFade) return
                if (stage == Stage.FADING) abort("the song changed during the fade")
                schedule()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (handingOff) return
                val left = oldPosition.mediaItem
                val changedSong = left != null && left.mediaId != newPosition.mediaItem?.mediaId
                val byHand = reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_REMOVE
                if (heldTarget != null && (changedSong || byHand)) {
                    abort("the player moved while a skip waited for its tail")
                    return
                }
                if (changedSong && byHand && beginSkipFade(left, oldPosition.positionMs)) return
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                if (stage != Stage.IDLE) abort("a seek")
                schedule()
            }

            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) {
                if (!playWhenReady && stage != Stage.IDLE) abort("a pause")
                schedule()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && stage == Stage.IDLE) schedule()
                // Closing the player stops it without a pause, and the old song must stop with it.
                if (skipFade && (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED)) {
                    // A failed player's recovery goes back to its song, so a seek here would only be undone.
                    if (playbackState == Player.STATE_IDLE) heldTarget = null
                    abort("the player stopped")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) nextSongPlaying()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (stage == Stage.FADING || skipFade) return
                schedule()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (stage != Stage.FADING && !skipFade) schedule()
            }

            override fun onTimelineChanged(
                timeline: Timeline,
                reason: Int,
            ) {
                if (stage != Stage.FADING && !skipFade && reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) schedule()
            }
        }

    private val tailListener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && stage == Stage.WAITING_FOR_TAIL) lineUp()
                if (playbackState == Player.STATE_ENDED && stage == Stage.WAITING_FOR_NEXT_SONG) finish()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (stage != Stage.IDLE) abort("the tail failed: ${error.errorCodeName}")
            }
        }

    // A started player is heard a moment after it reports playing, and only this says the audio has begun.
    @OptIn(UnstableApi::class)
    private val skipTailListener =
        object : AnalyticsListener {
            override fun onAudioPositionAdvancing(
                eventTime: AnalyticsListener.EventTime,
                playoutStartSystemTimeMs: Long,
            ) = skipTailPlaying()
        }

    private val fadeStep = Runnable { fade() }
    private val skipTimeoutStep = Runnable { if (stage == Stage.SKIP_STARTING) skipTailOutOfTime() }

    fun attach() = session.addListener(listener)

    fun setSettings(chosen: CrossfadePolicy.Settings) {
        val rescheduleNeeded = chosen.copy(curve = settings.curve) != settings
        settings = chosen
        if (rescheduleNeeded && stage != Stage.FADING) schedule()
    }

    fun release() {
        session.removeListener(listener)
        heldTarget = null
        abort(null)
    }

    /** Next or Previous from inside the app, which plays the song at [index] (still [mediaId]) whatever else happens. */
    fun skipTo(
        index: Int,
        mediaId: String,
    ) {
        val target = SkipTarget(index, mediaId)
        val left = session.currentMediaItem
        val resumeAt =
            left?.takeIf { heldTarget == null && session.isPlaying }?.let { skipResumeAt(it, session.currentPosition) }
        val press = CrossfadePolicy.onSkipPress(skipPhase(), session.currentMediaItemIndex == index, resumeAt != null)
        Log.d(TAG, "skip to $mediaId: $press")
        when (press) {
            CrossfadePolicy.SkipPress.START_TAIL -> holdSkip(target, requireNotNull(left), requireNotNull(resumeAt))
            CrossfadePolicy.SkipPress.MOVE_TARGET -> heldTarget = target
            CrossfadePolicy.SkipPress.BACK_TO_PLAYING_SONG -> stayOnPlayingSong()
            CrossfadePolicy.SkipPress.SEEK_UNDER_TAIL -> seekUnderTail(target)
            CrossfadePolicy.SkipPress.PLAIN -> skipPlainly(target)
        }
    }

    private fun holdSkip(
        target: SkipTarget,
        left: MediaItem,
        resumeAt: Long,
    ) {
        val gain = session.volume
        abort(null)
        heldTarget = target
        startSkipTail(left, resumeAt, gain, tailVolume = 0f)
    }

    private fun stayOnPlayingSong() {
        heldTarget = null
        abort("the skip came back to the song still playing")
        schedule()
    }

    private fun skipPlainly(target: SkipTarget) {
        abort(null)
        indexOf(target)?.let(::seekSession)
        session.play()
        schedule()
    }

    private fun skipPhase(): CrossfadePolicy.SkipPhase {
        val tailCarriesOldSong = stage == Stage.SKIP_STARTING || stage == Stage.WAITING_FOR_NEXT_SONG || stage == Stage.FADING
        return when {
            heldTarget != null -> CrossfadePolicy.SkipPhase.HOLDING
            tailCarriesOldSong -> CrossfadePolicy.SkipPhase.UNDER_TAIL
            else -> CrossfadePolicy.SkipPhase.NONE
        }
    }

    private fun skipResumeAt(
        left: MediaItem,
        positionMs: Long,
    ): Long? {
        if (!session.playWhenReady || moment(settings).showsVideo) return null
        return CrossfadePolicy.skipResumeMs(positionMs, skipLeadMs, left.mediaMetadata.durationMs, session.playerError != null)
    }

    /** The song has already changed by hand: the old one returns on a tail from where it was and fades under the new one. */
    private fun beginSkipFade(
        left: MediaItem,
        positionMs: Long,
    ): Boolean {
        if (!settings.fadesOnSkip) return false
        val resumeAt = skipResumeAt(left, positionMs) ?: return false
        val gain = session.volume
        abort(null)
        sessionScale = 0f
        onSessionScaleChanged()
        startSkipTail(left, resumeAt, gain, tailVolume = gain)
        return true
    }

    @OptIn(UnstableApi::class)
    private fun startSkipTail(
        item: MediaItem,
        resumeAt: Long,
        gain: Float,
        tailVolume: Float,
    ) {
        skipFade = true
        skipPressedAtMs = SystemClock.elapsedRealtime()
        mediaId = item.mediaId
        tailGain = gain
        val durationMs = item.mediaMetadata.durationMs ?: 0L
        fadeClockMs = CrossfadePolicy.skipClockMs(settings, resumeAt, durationMs, session.playbackParameters.speed)
        tail =
            buildTail(fadeClockMs).apply {
                volume = tailVolume
                playbackParameters = session.playbackParameters
                addListener(tailListener)
                addAnalyticsListener(skipTailListener)
                if (CrossfadePolicy.tailStartsAtSyncPoint(settings, heldTarget != null)) startAtSyncPoint(resumeAt)
                setMediaItem(item, resumeAt)
                playWhenReady = true
                prepare()
            }
        stage = Stage.SKIP_STARTING
        handler.postDelayed(skipTimeoutStep, SKIP_START_TIMEOUT_MS)
    }

    /**
     * Starting between sync points decodes from the one before first, about 500 ms the skip would wait.
     * A start position ignores seek parameters, so the seek comes as soon as the tail knows its tracks.
     */
    private fun ExoPlayer.startAtSyncPoint(positionMs: Long) {
        setSeekParameters(SeekParameters.CLOSEST_SYNC)
        addListener(
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    if (tracks.isEmpty) return
                    removeListener(this)
                    seekTo(positionMs)
                }
            },
        )
    }

    private fun skipTailOutOfTime() {
        if (tail?.isPlaying == true) {
            skipTailPlaying()
        } else {
            abort("the tail took too long after a skip")
            schedule()
        }
    }

    private fun skipTailPlaying() {
        if (stage != Stage.SKIP_STARTING) return
        val waited = SystemClock.elapsedRealtime() - skipPressedAtMs
        skipLeadMs = CrossfadePolicy.learnedHeadStartMs(skipLeadMs, skipLeadMs - waited)
        val apart = (tail?.currentPosition ?: 0L) - session.currentPosition
        Log.d(TAG, "skipped $mediaId, its tail played after ${waited}ms, ${apart}ms from the session, fading over ${fadeClockMs}ms")
        handler.removeCallbacks(skipTimeoutStep)
        val target = heldTarget
        heldTarget = null
        if (target != null) {
            val index = indexOf(target)
            if (index == null) {
                abort("the skipped-to song left the queue")
                return
            }
            tail?.volume = tailGain
            sessionScale = 0f
            onSessionScaleChanged()
            seekSession(index)
        }
        if (session.isPlaying) startFade() else stage = Stage.WAITING_FOR_NEXT_SONG
    }

    /** The next song has started: a tail already playing fades it in, and one still starting has [SKIP_START_TIMEOUT_MS] to. */
    private fun nextSongPlaying() {
        if (heldTarget == null && stage == Stage.WAITING_FOR_NEXT_SONG) startFade()
    }

    /** A tail already carries the old song, so only the song under it changes, and the fade waits for that one. */
    private fun seekUnderTail(target: SkipTarget) {
        val index = indexOf(target) ?: return
        if (stage == Stage.FADING) {
            fadeProgress = ((SystemClock.elapsedRealtime() - fadeBeganAtMs).toFloat() / fadeClockMs).coerceIn(0f, 1f)
            handler.removeCallbacks(fadeStep)
            stage = Stage.WAITING_FOR_NEXT_SONG
        }
        skipFade = true
        seekSession(index)
    }

    private fun startFade() {
        handler.removeCallbacks(skipTimeoutStep)
        stage = Stage.FADING
        fadeBeganAtMs = CrossfadePolicy.fadeBeganAtMs(SystemClock.elapsedRealtime(), fadeProgress, fadeClockMs)
        handler.post(fadeStep)
    }

    private fun indexOf(target: SkipTarget): Int? {
        val count = session.mediaItemCount
        if (target.index < count && session.getMediaItemAt(target.index).mediaId == target.mediaId) return target.index
        return (0 until count).firstOrNull { session.getMediaItemAt(it).mediaId == target.mediaId }
    }

    private fun seekSession(index: Int) {
        handingOff = true
        try {
            session.seekTo(index, 0L)
        } finally {
            handingOff = false
        }
    }

    private fun schedule() {
        if (stage == Stage.FADING || skipFade) return
        if (stage != Stage.IDLE) abort(null)
        cancelMessages()
        if (!settings.onAutoAdvance) return
        val now = moment(settings)
        val start = CrossfadePolicy.fadeStartMs(now) ?: return
        val item = session.currentMediaItem ?: return
        val position = session.currentPosition
        val alignAt = CrossfadePolicy.alignStartMs(start, position, now.durationMs, now.speed) ?: return
        val prepareAt = alignAt - CrossfadePolicy.PREPARE_LEAD_MS
        val index = session.currentMediaItemIndex
        mediaId = item.mediaId
        aligner.reset(CrossfadePolicy.latestHandOffMs(now.durationMs, now.speed))
        if (position >= prepareAt) {
            prepare(item.mediaId, alignAt)
        } else {
            prepareMessage = message(index, prepareAt) { prepare(item.mediaId, alignAt) }
        }
        alignMessage = message(index, alignAt) { beginAlign(item.mediaId) }
    }

    private fun prepare(
        id: String,
        alignAt: Long,
    ) {
        val item = session.currentMediaItem?.takeIf { it.mediaId == id } ?: return
        tail?.release()
        tail =
            buildTail(null).apply {
                volume = 0f
                playbackParameters = session.playbackParameters
                addListener(tailListener)
                setMediaItem(item, alignAt + aligner.headStartMs)
                playWhenReady = false
                prepare()
            }
        stage = Stage.PREPARED
    }

    private fun beginAlign(id: String) {
        val player = tail
        if (player == null || session.currentMediaItem?.mediaId != id || !session.playWhenReady) {
            abort(null)
            return
        }
        if (player.playbackState == Player.STATE_READY) lineUp() else stage = Stage.WAITING_FOR_TAIL
    }

    private fun lineUp() {
        stage = Stage.WAITING_FOR_TAIL
        aligner.startWhenLinedUp()
    }

    private fun handOff(deltaMs: Long) {
        val player = tail ?: return
        fadeClockMs =
            CrossfadePolicy.fadeClockMs(settings.seconds, session.currentPosition, session.duration, session.playbackParameters.speed)
        tailGain = session.volume
        player.volume = tailGain
        player.setPlaybackSpeed(session.playbackParameters.speed)
        sessionScale = 0f
        onSessionScaleChanged()
        handingOff = true
        try {
            session.seekToNextMediaItem()
        } finally {
            handingOff = false
        }
        stage = Stage.FADING
        fadeBeganAtMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "handed off $mediaId to the tail ${deltaMs}ms apart, fading over ${fadeClockMs}ms")
        handler.post(fadeStep)
    }

    private fun fade() {
        val player = tail ?: return
        if (stage != Stage.FADING) return
        val progress = (SystemClock.elapsedRealtime() - fadeBeganAtMs).toFloat() / fadeClockMs
        player.volume = tailGain * CrossfadePolicy.fadeOutGain(progress, settings.curve)
        sessionScale = CrossfadePolicy.fadeInGain(progress, settings.curve)
        onSessionScaleChanged()
        if (progress >= 1f || player.playbackState == Player.STATE_ENDED) {
            finish()
        } else {
            handler.postDelayed(fadeStep, FADE_STEP_MS)
        }
    }

    private fun finish() {
        skipFade = false
        releaseTail()
        stage = Stage.IDLE
        restoreScale()
        schedule()
    }

    private fun abort(reason: String?) {
        if (reason != null) Log.d(TAG, "crossfade dropped: $reason")
        val held = heldTarget
        heldTarget = null
        skipFade = false
        cancelMessages()
        releaseTail()
        stage = Stage.IDLE
        restoreScale()
        // The press still counts, it only lost its fade.
        held?.let(::indexOf)?.let(::seekSession)
    }

    private fun releaseTail() {
        aligner.cancel()
        handler.removeCallbacks(fadeStep)
        handler.removeCallbacks(skipTimeoutStep)
        tail?.release()
        tail = null
        fadeProgress = 0f
    }

    private fun restoreScale() {
        if (sessionScale == 1f) return
        sessionScale = 1f
        onSessionScaleChanged()
    }

    private fun cancelMessages() {
        prepareMessage?.cancel()
        alignMessage?.cancel()
        prepareMessage = null
        alignMessage = null
    }

    private fun message(
        index: Int,
        positionMs: Long,
        action: () -> Unit,
    ): PlayerMessage =
        session
            .createMessage { _, _ -> action() }
            .setLooper(Looper.getMainLooper())
            .setPosition(index, positionMs.coerceAtLeast(0L))
            .setDeleteAfterDelivery(true)
            .send()

    private companion object {
        const val TAG = "MusicCrossfader"
        const val FADE_STEP_MS = 20L
        const val DEFAULT_SKIP_LEAD_MS = 400L
        const val SKIP_START_TIMEOUT_MS = 1_500L
    }
}
