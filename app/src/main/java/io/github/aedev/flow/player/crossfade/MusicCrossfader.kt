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
 * the fade shortens to what is left of the song. When no tail can carry the song (it wasn't ready, couldn't
 * line up before [CrossfadePolicy.latestHandOffMs], or the song is too short for one), the session player
 * fades its own song out instead, and the next song comes in over [CrossfadePolicy.QUICK_FADE_IN_MS]. The
 * outgoing song fades until it ends and the incoming one over the chosen length, each on its own clock,
 * so a next song that is slow to start still gets its whole fade.
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
    private enum class Stage {
        IDLE,
        PREPARED,
        WAITING_FOR_TAIL,
        ALIGNING,
        SKIP_STARTING,
        WAITING_FOR_NEXT_SONG,
        FADING,

        /** No tail: the session fades its own song out. */
        THROUGH,

        /** No tail: the next song comes in quickly. */
        THROUGH_IN,
    }

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
    private var deadlineMessage: PlayerMessage? = null
    private var mediaId: String? = null
    private var outBeganAtMs = 0L
    private var outClockMs = 1L

    // The incoming clock runs only while the session plays, so a next song that buffers keeps its whole fade.
    private var inElapsedMs = 0L
    private var inRunningSinceMs: Long? = null
    private var inClockMs = 1L
    private var fadeProgress = 0f
    private var skipPressedAtMs = 0L
    private var skipFade = false

    /** Where a skip goes once the tail plays; the session stays on the old song until then. */
    private var heldTarget: SkipTarget? = null

    /** Where a second skip goes once the song that was fading in has eased out. */
    private var easingTo: SkipTarget? = null
    private var easeFrom = 0f
    private var easeBeganAtMs = 0L
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
            onGaveUp = ::fadeThrough,
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
                val songEnded = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                if (stage == Stage.THROUGH && songEnded) {
                    startQuickFadeIn()
                    return
                }
                if (fading) abort("the song changed during the fade")
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
                runIncomingClock(isPlaying)
                if (isPlaying) nextSongPlaying()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (fading || skipFade) return
                schedule()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (!fading && !skipFade) schedule()
            }

            override fun onTimelineChanged(
                timeline: Timeline,
                reason: Int,
            ) {
                if (!fading && !skipFade && reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) schedule()
            }
        }

    private val tailListener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && stage == Stage.WAITING_FOR_TAIL) lineUp()
                if (playbackState == Player.STATE_ENDED && stage == Stage.WAITING_FOR_NEXT_SONG) finish()
            }

            override fun onPlayerError(error: PlaybackException) {
                when (stage) {
                    Stage.IDLE -> Unit
                    Stage.PREPARED, Stage.WAITING_FOR_TAIL, Stage.ALIGNING -> fadeThrough("the tail failed: ${error.errorCodeName}")
                    else -> abort("the tail failed: ${error.errorCodeName}")
                }
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
    private val easeStep = Runnable { ease() }
    private val skipTimeoutStep = Runnable { if (stage == Stage.SKIP_STARTING) skipTailOutOfTime() }

    fun attach() = session.addListener(listener)

    fun setSettings(chosen: CrossfadePolicy.Settings) {
        val rescheduleNeeded = chosen.copy(curve = settings.curve) != settings
        settings = chosen
        if (rescheduleNeeded && !fading) schedule()
    }

    private val fading: Boolean get() = stage == Stage.FADING || stage == Stage.THROUGH || stage == Stage.THROUGH_IN

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
        outClockMs = CrossfadePolicy.skipClockMs(settings, resumeAt, durationMs, session.playbackParameters.speed)
        inClockMs = outClockMs
        tail =
            buildTail(outClockMs).apply {
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
        Log.d(TAG, "skipped $mediaId, its tail played after ${waited}ms, ${apart}ms from the session, fading over ${outClockMs}ms")
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
        if (heldTarget == null && easingTo == null && stage == Stage.WAITING_FOR_NEXT_SONG) startFade()
    }

    /**
     * A tail already carries the old song, so only the song under it changes, and the fade waits for that one.
     * A song already fading in eases out first, so it doesn't stop mid-word.
     */
    private fun seekUnderTail(target: SkipTarget) {
        if (easingTo != null) {
            easingTo = target
            return
        }
        if (stage == Stage.FADING && sessionScale > 0f) {
            pauseFade()
            skipFade = true
            easingTo = target
            easeFrom = sessionScale
            easeBeganAtMs = SystemClock.elapsedRealtime()
            handler.post(easeStep)
            return
        }
        moveUnderTail(target)
    }

    private fun ease() {
        val target = easingTo ?: return
        val progress = CrossfadePolicy.progress(SystemClock.elapsedRealtime() - easeBeganAtMs, CrossfadePolicy.EASE_OUT_MS)
        sessionScale = easeFrom * (1f - progress)
        onSessionScaleChanged()
        if (progress < 1f) {
            handler.postDelayed(easeStep, FADE_STEP_MS)
            return
        }
        easingTo = null
        moveUnderTail(target)
    }

    private fun moveUnderTail(target: SkipTarget) {
        val index = indexOf(target) ?: return
        if (stage == Stage.FADING) pauseFade()
        skipFade = true
        seekSession(index)
    }

    /** Holds the old song's volume where it is while the session moves to another song. */
    private fun pauseFade() {
        fadeProgress = CrossfadePolicy.progress(SystemClock.elapsedRealtime() - outBeganAtMs, outClockMs)
        handler.removeCallbacks(fadeStep)
        stage = Stage.WAITING_FOR_NEXT_SONG
    }

    private fun startFade() {
        handler.removeCallbacks(skipTimeoutStep)
        stage = Stage.FADING
        val now = SystemClock.elapsedRealtime()
        outBeganAtMs = CrossfadePolicy.fadeBeganAtMs(now, fadeProgress, outClockMs)
        inElapsedMs = (fadeProgress * inClockMs).toLong()
        inRunningSinceMs = now
        handler.post(fadeStep)
    }

    private fun runIncomingClock(playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (playing) {
            if (inRunningSinceMs == null) inRunningSinceMs = now
        } else {
            inRunningSinceMs?.let { inElapsedMs += now - it }
            inRunningSinceMs = null
        }
    }

    private fun incomingProgress(nowMs: Long): Float =
        CrossfadePolicy.progress(inElapsedMs + (inRunningSinceMs?.let { nowMs - it } ?: 0L), inClockMs)

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
        if (fading || skipFade) return
        if (stage != Stage.IDLE) abort(null)
        cancelMessages()
        if (!settings.onAutoAdvance) return
        val now = moment(settings)
        val start = CrossfadePolicy.fadeStartMs(now) ?: return
        val item = session.currentMediaItem ?: return
        val position = session.currentPosition
        val index = session.currentMediaItemIndex
        mediaId = item.mediaId
        val alignAt = CrossfadePolicy.alignStartMs(start, position, now.durationMs, now.speed, now.fadeMs)
        if (alignAt == null) {
            if (position >= start) {
                handler.post { fadeThroughIfStillOn(item.mediaId) }
            } else {
                alignMessage = message(index, start) { fadeThrough("the song is too short for a tail") }
            }
            return
        }
        val latest = CrossfadePolicy.latestHandOffMs(now.durationMs, now.speed, now.fadeMs)
        aligner.reset(CrossfadePolicy.earliestHandOffMs(start, now.speed), latest)
        val prepareAt = alignAt - CrossfadePolicy.PREPARE_LEAD_MS
        if (position >= prepareAt) {
            prepare(item.mediaId, alignAt)
        } else {
            prepareMessage = message(index, prepareAt) { prepare(item.mediaId, alignAt) }
        }
        alignMessage = message(index, alignAt) { beginAlign(item.mediaId) }
        deadlineMessage =
            message(index, latest) {
                if (stage == Stage.PREPARED || stage == Stage.WAITING_FOR_TAIL) fadeThrough("the tail was not ready in time")
            }
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
        if (session.currentMediaItem?.mediaId != id || !session.playWhenReady) {
            abort(null)
            return
        }
        if (player == null) {
            fadeThrough("no tail was built")
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
        val speed = session.playbackParameters.speed
        outClockMs = CrossfadePolicy.fadeOutClockMs(session.currentPosition, session.duration, speed)
        inClockMs = settings.fadeMs.coerceAtLeast(1L)
        cancelMessages()
        tailGain = session.volume
        player.volume = tailGain
        player.setPlaybackSpeed(speed)
        sessionScale = 0f
        onSessionScaleChanged()
        handingOff = true
        try {
            if (session.repeatMode == Player.REPEAT_MODE_ONE) {
                session.seekTo(session.currentMediaItemIndex, 0L)
            } else {
                session.seekToNextMediaItem()
            }
        } finally {
            handingOff = false
        }
        stage = Stage.FADING
        val now = SystemClock.elapsedRealtime()
        outBeganAtMs = now
        inElapsedMs = 0L
        inRunningSinceMs = now.takeIf { session.isPlaying }
        Log.d(TAG, "handed off $mediaId to the tail ${deltaMs}ms apart, out over ${outClockMs}ms, in over ${inClockMs}ms")
        handler.post(fadeStep)
    }

    /** No tail can carry the song, so the session fades it out itself; the next song then comes in quickly. */
    private fun fadeThrough(reason: String) {
        val id = mediaId
        if (session.currentMediaItem?.mediaId != id || !session.playWhenReady) {
            abort(reason)
            return
        }
        cancelMessages()
        releaseTail()
        val plan =
            CrossfadePolicy.fadeThrough(session.currentPosition, session.duration, session.playbackParameters.speed, settings.fadeMs)
        Log.d(TAG, "fading $id through: $reason, out over ${plan.outMs}ms after ${plan.holdMs}ms")
        stage = Stage.THROUGH
        outClockMs = plan.outMs.coerceAtLeast(1L)
        outBeganAtMs = SystemClock.elapsedRealtime() + plan.holdMs
        if (plan.outMs > 0L) handler.postDelayed(fadeStep, plan.holdMs)
    }

    private fun fadeThroughIfStillOn(id: String) {
        if (stage == Stage.IDLE && session.currentMediaItem?.mediaId == id) fadeThrough("too little song left for a tail")
    }

    private fun startQuickFadeIn() {
        handler.removeCallbacks(fadeStep)
        stage = Stage.THROUGH_IN
        sessionScale = 0f
        onSessionScaleChanged()
        inClockMs = CrossfadePolicy.QUICK_FADE_IN_MS
        inElapsedMs = 0L
        inRunningSinceMs = SystemClock.elapsedRealtime().takeIf { session.isPlaying }
        handler.post(fadeStep)
    }

    private fun fade() {
        val now = SystemClock.elapsedRealtime()
        val done =
            when (stage) {
                Stage.FADING -> {
                    fadeBoth(now)
                }

                Stage.THROUGH -> {
                    sessionScale = CrossfadePolicy.fadeOutGain(CrossfadePolicy.progress(now - outBeganAtMs, outClockMs), settings.curve)
                    onSessionScaleChanged()
                    // Silent until the next song arrives, which starts the quick fade-in.
                    if (sessionScale <= 0f) return
                    false
                }

                Stage.THROUGH_IN -> {
                    sessionScale = CrossfadePolicy.fadeInGain(incomingProgress(now), settings.curve)
                    onSessionScaleChanged()
                    sessionScale >= 1f
                }

                else -> {
                    return
                }
            }
        if (done) finish() else handler.postDelayed(fadeStep, FADE_STEP_MS)
    }

    /** Steps both songs; true once the old song is gone and the new one is at full volume. */
    private fun fadeBoth(nowMs: Long): Boolean {
        val outProgress = CrossfadePolicy.progress(nowMs - outBeganAtMs, outClockMs)
        val player = tail
        if (player != null) {
            player.volume = tailGain * CrossfadePolicy.fadeOutGain(outProgress, settings.curve)
            if (outProgress >= 1f || player.playbackState == Player.STATE_ENDED) {
                player.release()
                tail = null
            }
        }
        val inProgress = incomingProgress(nowMs)
        sessionScale = CrossfadePolicy.fadeInGain(inProgress, settings.curve)
        onSessionScaleChanged()
        return tail == null && inProgress >= 1f
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
        val held = heldTarget ?: easingTo
        heldTarget = null
        easingTo = null
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
        handler.removeCallbacks(easeStep)
        handler.removeCallbacks(skipTimeoutStep)
        tail?.release()
        tail = null
        fadeProgress = 0f
        inRunningSinceMs = null
        inElapsedMs = 0L
    }

    private fun restoreScale() {
        if (sessionScale == 1f) return
        sessionScale = 1f
        onSessionScaleChanged()
    }

    private fun cancelMessages() {
        prepareMessage?.cancel()
        alignMessage?.cancel()
        deadlineMessage?.cancel()
        prepareMessage = null
        alignMessage = null
        deadlineMessage = null
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
