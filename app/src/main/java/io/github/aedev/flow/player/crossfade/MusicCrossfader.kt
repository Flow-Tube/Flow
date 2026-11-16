package io.github.aedev.flow.player.crossfade

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage

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
 * A skip has no tail waiting, so [CrossfadeSkip] builds one from where the song is; the fade then runs here.
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
    private val handler = Handler(Looper.getMainLooper())
    private var stage = CrossfadeStage.IDLE
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
    private var skipFade = false
    private var tailGain = 1f
    private var handingOff = false
    private val aligner =
        TailAligner(
            session = session,
            handler = handler,
            tail = { tail },
            onTailStarted = { stage = CrossfadeStage.ALIGNING },
            onAligned = ::handOff,
            onGaveUp = ::fadeThrough,
        )
    private val skip = CrossfadeSkip(session, handler, moment, SkipHost())

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
                if (stage == CrossfadeStage.THROUGH && songEnded) {
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
                if (skip.holding && (changedSong || byHand)) {
                    abort("the player moved while a skip waited for its tail")
                    return
                }
                if (changedSong && byHand && skip.beginSkipFade(left, oldPosition.positionMs)) return
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                if (stage != CrossfadeStage.IDLE) abort("a seek")
                schedule()
            }

            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) {
                if (!playWhenReady && stage != CrossfadeStage.IDLE) abort("a pause")
                schedule()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && stage == CrossfadeStage.IDLE) schedule()
                // Closing the player stops it without a pause, and the old song must stop with it.
                if (skipFade && (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED)) {
                    // A failed player's recovery goes back to its song, so a seek here would only be undone.
                    if (playbackState == Player.STATE_IDLE) skip.forgetHeld()
                    abort("the player stopped")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                runIncomingClock(isPlaying)
                if (isPlaying) skip.nextSongPlaying()
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
                if (playbackState == Player.STATE_READY && stage == CrossfadeStage.WAITING_FOR_TAIL) lineUp()
                if (playbackState == Player.STATE_ENDED && stage == CrossfadeStage.WAITING_FOR_NEXT_SONG) finish()
            }

            override fun onPlayerError(error: PlaybackException) {
                when (stage) {
                    CrossfadeStage.IDLE -> {
                        Unit
                    }

                    CrossfadeStage.PREPARED, CrossfadeStage.WAITING_FOR_TAIL, CrossfadeStage.ALIGNING -> {
                        fadeThrough(
                            "the tail failed: ${error.errorCodeName}",
                        )
                    }

                    else -> {
                        abort("the tail failed: ${error.errorCodeName}")
                    }
                }
            }
        }

    private val fadeStep = Runnable { fade() }

    fun attach() = session.addListener(listener)

    fun setSettings(chosen: CrossfadePolicy.Settings) {
        val rescheduleNeeded = chosen.copy(curve = settings.curve) != settings
        settings = chosen
        if (rescheduleNeeded && !fading) schedule()
    }

    private val fading: Boolean get() =
        stage == CrossfadeStage.FADING || stage == CrossfadeStage.THROUGH ||
            stage == CrossfadeStage.THROUGH_IN

    fun release() {
        session.removeListener(listener)
        skip.forgetHeld()
        abort(null)
    }

    /** Next or Previous from inside the app, which plays the song at [index] (still [mediaId]) whatever else happens. */
    fun skipTo(
        index: Int,
        mediaId: String,
    ) = skip.skipTo(index, mediaId)

    private fun startFade() {
        stage = CrossfadeStage.FADING
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
        if (stage != CrossfadeStage.IDLE) abort(null)
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
                if (stage == CrossfadeStage.PREPARED ||
                    stage == CrossfadeStage.WAITING_FOR_TAIL
                ) {
                    fadeThrough("the tail was not ready in time")
                }
            }
    }

    private fun prepare(
        id: String,
        alignAt: Long,
    ) {
        val item = session.currentMediaItem?.takeIf { it.mediaId == id } ?: return
        tail?.release()
        tail =
            newTail(null).apply {
                volume = 0f
                setMediaItem(item, alignAt + aligner.headStartMs)
                playWhenReady = false
                prepare()
            }
        stage = CrossfadeStage.PREPARED
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
        if (player.playbackState == Player.STATE_READY) lineUp() else stage = CrossfadeStage.WAITING_FOR_TAIL
    }

    private fun lineUp() {
        stage = CrossfadeStage.WAITING_FOR_TAIL
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
        stage = CrossfadeStage.FADING
        val now = SystemClock.elapsedRealtime()
        outBeganAtMs = now
        inElapsedMs = 0L
        inRunningSinceMs = now.takeIf { session.isPlaying }
        Log.d(CROSSFADE_TAG, "handed off $mediaId to the tail ${deltaMs}ms apart, out over ${outClockMs}ms, in over ${inClockMs}ms")
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
        Log.d(CROSSFADE_TAG, "fading $id through: $reason, out over ${plan.outMs}ms after ${plan.holdMs}ms")
        stage = CrossfadeStage.THROUGH
        outClockMs = plan.outMs.coerceAtLeast(1L)
        outBeganAtMs = SystemClock.elapsedRealtime() + plan.holdMs
        if (plan.outMs > 0L) handler.postDelayed(fadeStep, plan.holdMs)
    }

    private fun fadeThroughIfStillOn(id: String) {
        if (stage == CrossfadeStage.IDLE && session.currentMediaItem?.mediaId == id) fadeThrough("too little song left for a tail")
    }

    private fun startQuickFadeIn() {
        handler.removeCallbacks(fadeStep)
        stage = CrossfadeStage.THROUGH_IN
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
                CrossfadeStage.FADING -> {
                    fadeBoth(now)
                }

                CrossfadeStage.THROUGH -> {
                    sessionScale = CrossfadePolicy.fadeOutGain(CrossfadePolicy.progress(now - outBeganAtMs, outClockMs), settings.curve)
                    onSessionScaleChanged()
                    // Silent until the next song arrives, which starts the quick fade-in.
                    if (sessionScale <= 0f) return
                    false
                }

                CrossfadeStage.THROUGH_IN -> {
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
        stage = CrossfadeStage.IDLE
        restoreScale()
        schedule()
    }

    private fun abort(reason: String?) {
        if (reason != null) Log.d(CROSSFADE_TAG, "crossfade dropped: $reason")
        val held = skip.dropTargets()
        skipFade = false
        cancelMessages()
        releaseTail()
        stage = CrossfadeStage.IDLE
        restoreScale()
        // The press still counts, it only lost its fade.
        held?.let(skip::indexOf)?.let(::seekSession)
    }

    private fun releaseTail() {
        aligner.cancel()
        handler.removeCallbacks(fadeStep)
        skip.cancelSteps()
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

    private fun newTail(skipOverlapMs: Long?): ExoPlayer =
        buildTail(skipOverlapMs).apply {
            playbackParameters = session.playbackParameters
            addListener(tailListener)
        }

    private inner class SkipHost : CrossfadeSkip.Host {
        override val stage get() = this@MusicCrossfader.stage
        override val settings get() = this@MusicCrossfader.settings
        override val tail get() = this@MusicCrossfader.tail
        override val sessionScale get() = this@MusicCrossfader.sessionScale

        override fun scaleSession(scale: Float) {
            this@MusicCrossfader.sessionScale = scale
            onSessionScaleChanged()
        }

        override fun startSkipTail(
            mediaId: String,
            gain: Float,
            clockMs: Long,
            setUp: ExoPlayer.() -> Unit,
        ) {
            skipFade = true
            this@MusicCrossfader.mediaId = mediaId
            tailGain = gain
            outClockMs = clockMs
            inClockMs = clockMs
            this@MusicCrossfader.tail = newTail(clockMs).apply(setUp)
            this@MusicCrossfader.stage = CrossfadeStage.SKIP_STARTING
        }

        override fun restoreTailGain() {
            this@MusicCrossfader.tail?.volume = tailGain
        }

        override fun waitForNextSong() {
            this@MusicCrossfader.stage = CrossfadeStage.WAITING_FOR_NEXT_SONG
        }

        override fun startFade() = this@MusicCrossfader.startFade()

        override fun holdFade() {
            if (this@MusicCrossfader.stage == CrossfadeStage.FADING) {
                fadeProgress = CrossfadePolicy.progress(SystemClock.elapsedRealtime() - outBeganAtMs, outClockMs)
                handler.removeCallbacks(fadeStep)
                this@MusicCrossfader.stage = CrossfadeStage.WAITING_FOR_NEXT_SONG
            }
            skipFade = true
        }

        override fun seekSession(index: Int) = this@MusicCrossfader.seekSession(index)

        override fun abort(reason: String?) = this@MusicCrossfader.abort(reason)

        override fun schedule() = this@MusicCrossfader.schedule()
    }
}

internal enum class CrossfadeStage {
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

internal const val CROSSFADE_TAG = "MusicCrossfader"
internal const val FADE_STEP_MS = 20L
