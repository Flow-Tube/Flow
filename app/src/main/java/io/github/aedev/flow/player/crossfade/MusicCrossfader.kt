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
import kotlin.math.abs

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
 * A song the user skips away from has no tail waiting, so one is built from where the song stopped. It
 * is heard a moment after the next song is chosen, then the two fade as usual; only while the settings
 * ask for it.
 *
 * Every step is driven by the players: [PlayerMessage]s at a position, their listeners for readiness,
 * seeks, skips and pauses. The only timed loops run while a song is changing hands: [ALIGN_STEP_MS]
 * checks for a few seconds, then [FADE_STEP_MS] volume steps for the fade. Positions are compared
 * through [PositionAnchor], since a player reports them in coarse steps.
 */
internal class MusicCrossfader(
    private val session: ExoPlayer,
    private val buildTail: () -> ExoPlayer,
    private val moment: (settings: CrossfadePolicy.Settings) -> CrossfadePolicy.Moment,
    private val onSessionScaleChanged: () -> Unit,
) {
    private enum class Stage { IDLE, PREPARED, WAITING_FOR_TAIL, ALIGNING, SKIP_STARTING, FADING }

    private val handler = Handler(Looper.getMainLooper())
    private var stage = Stage.IDLE
    private var settings = CrossfadePolicy.Settings()

    private var tail: ExoPlayer? = null
    private var prepareMessage: PlayerMessage? = null
    private var alignMessage: PlayerMessage? = null
    private var mediaId: String? = null
    private var latestHandOffMs = 0L
    private var fadeBeganAtMs = 0L
    private var fadeClockMs = 1L
    private var tailRetries = 0
    private var skipPressedAtMs = 0L
    private var skipFade = false
    private var skipLeadMs = DEFAULT_SKIP_LEAD_MS
    private var tailGain = 1f
    private var handingOff = false
    private var sessionAnchor = PositionAnchor()
    private var tailAnchor = PositionAnchor()
    private var alignedReadings = 0
    private var learnedThisAlign = false

    // A started player is heard a moment after play(), which differs by device (about 300 ms on HyperOS),
    // so the tail starts ahead by what the last one turned out to need.
    private var headStartMs = CrossfadePolicy.DEFAULT_HEAD_START_MS

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
                if (skipFade && (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED)) abort("the player stopped")
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
                if (playbackState == Player.STATE_READY && stage == Stage.WAITING_FOR_TAIL) startWhenLinedUp()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying || stage != Stage.SKIP_STARTING) return
                val waited = SystemClock.elapsedRealtime() - skipPressedAtMs
                skipLeadMs = CrossfadePolicy.learnedHeadStartMs(skipLeadMs, skipLeadMs - waited)
                Log.d(TAG, "skipped $mediaId, its tail played after ${waited}ms, fading over ${fadeClockMs}ms")
                handler.removeCallbacks(skipTimeoutStep)
                stage = Stage.FADING
                fadeBeganAtMs = SystemClock.elapsedRealtime()
                handler.post(fadeStep)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (stage != Stage.IDLE) abort("the tail failed: ${error.errorCodeName}")
            }
        }

    private val alignStep = Runnable { align() }
    private val fadeStep = Runnable { fade() }
    private val startTailStep = Runnable { startTail() }
    private val skipTimeoutStep = Runnable { if (stage == Stage.SKIP_STARTING) abort("the tail took too long after a skip") }

    fun attach() = session.addListener(listener)

    fun setSettings(chosen: CrossfadePolicy.Settings) {
        val rescheduleNeeded = chosen.copy(curve = settings.curve) != settings
        settings = chosen
        if (rescheduleNeeded && stage != Stage.FADING) schedule()
    }

    fun release() {
        session.removeListener(listener)
        abort(null)
    }

    /** The song was changed by hand: the old one returns on a tail from where it was and fades under the new one. */
    private fun beginSkipFade(
        left: MediaItem,
        positionMs: Long,
    ): Boolean {
        if (settings.seconds <= 0 || !settings.onManualSkip || !session.playWhenReady || moment(settings).showsVideo) return false
        val durationMs = left.mediaMetadata.durationMs
        val failed = session.playerError != null
        val resumeAt = CrossfadePolicy.skipResumeMs(positionMs, skipLeadMs, durationMs, failed) ?: return false
        val gain = session.volume
        abort(null)
        skipFade = true
        skipPressedAtMs = SystemClock.elapsedRealtime()
        mediaId = left.mediaId
        tailGain = gain
        fadeClockMs = CrossfadePolicy.fadeClockMs(settings.seconds, resumeAt, durationMs ?: 0L, session.playbackParameters.speed)
        sessionScale = 0f
        onSessionScaleChanged()
        tail =
            buildTail().apply {
                volume = gain
                playbackParameters = session.playbackParameters
                addListener(tailListener)
                setMediaItem(left, resumeAt)
                playWhenReady = true
                prepare()
            }
        stage = Stage.SKIP_STARTING
        handler.postDelayed(skipTimeoutStep, SKIP_START_TIMEOUT_MS)
        return true
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
        latestHandOffMs = CrossfadePolicy.latestHandOffMs(now.durationMs, now.speed)
        tailRetries = 0
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
            buildTail().apply {
                volume = 0f
                playbackParameters = session.playbackParameters
                addListener(tailListener)
                setMediaItem(item, alignAt + headStartMs)
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
        if (player.playbackState == Player.STATE_READY) startWhenLinedUp() else stage = Stage.WAITING_FOR_TAIL
    }

    /** Starts a buffered tail at the moment it will be heard level with the session player. */
    private fun startWhenLinedUp() {
        val player = tail ?: return
        val sessionMs = session.currentPosition
        if (sessionMs >= latestHandOffMs) {
            abort("the tail was not buffered in time")
            return
        }
        val aheadByMs = player.currentPosition - sessionMs
        stage = Stage.WAITING_FOR_TAIL
        when {
            aheadByMs >= headStartMs -> {
                handler.removeCallbacks(startTailStep)
                val waitMs = ((aheadByMs - headStartMs) / session.playbackParameters.speed.coerceAtLeast(0.1f)).toLong()
                handler.postDelayed(startTailStep, waitMs)
            }

            tailRetries < MAX_TAIL_RETRIES -> {
                // Buffering took longer than the lead it was given, so the session player went past it.
                tailRetries++
                player.seekTo(sessionMs + CrossfadePolicy.LATE_READY_LEAD_MS + headStartMs)
            }

            else -> {
                abort("the tail kept falling behind")
            }
        }
    }

    private fun startTail() {
        val player = tail ?: return
        player.play()
        sessionAnchor = PositionAnchor()
        tailAnchor = PositionAnchor()
        alignedReadings = 0
        learnedThisAlign = false
        stage = Stage.ALIGNING
        handler.post(alignStep)
    }

    private fun align() {
        val player = tail ?: return
        if (stage != Stage.ALIGNING) return
        val now = SystemClock.elapsedRealtime()
        val baseSpeed = session.playbackParameters.speed
        val sessionMs = sessionAnchor.estimate(session.currentPosition, now, baseSpeed)
        val remaining = latestHandOffMs - sessionMs
        if (!player.isPlaying) {
            if (remaining <= 0) abort("the tail did not start") else handler.postDelayed(alignStep, ALIGN_STEP_MS)
            return
        }
        val tailMs = tailAnchor.estimate(player.currentPosition, now, player.playbackParameters.speed)
        if (!sessionAnchor.anchored || !tailAnchor.anchored) {
            if (remaining <= 0) abort("the positions never moved") else handler.postDelayed(alignStep, ALIGN_STEP_MS)
            return
        }
        val delta = tailMs - sessionMs
        if (!learnedThisAlign) {
            learnedThisAlign = true
            headStartMs = CrossfadePolicy.learnedHeadStartMs(headStartMs, delta)
        }
        alignedReadings = if (CrossfadePolicy.isAligned(delta)) alignedReadings + 1 else 0
        when {
            alignedReadings >= ALIGNED_READINGS -> {
                handOff(delta)
            }

            remaining <= 0 && abs(delta) <= CrossfadePolicy.LATE_ALIGNED_WITHIN_MS -> {
                handOff(delta)
            }

            remaining <= 0 -> {
                abort("the tail stayed ${delta}ms apart")
            }

            else -> {
                val speed = CrossfadePolicy.nudgedSpeed(baseSpeed, delta)
                if (abs(speed - player.playbackParameters.speed) >= SPEED_STEP) player.setPlaybackSpeed(speed)
                handler.postDelayed(alignStep, ALIGN_STEP_MS)
            }
        }
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
        skipFade = false
        cancelMessages()
        releaseTail()
        stage = Stage.IDLE
        restoreScale()
    }

    private fun releaseTail() {
        handler.removeCallbacks(alignStep)
        handler.removeCallbacks(fadeStep)
        handler.removeCallbacks(startTailStep)
        handler.removeCallbacks(skipTimeoutStep)
        tail?.release()
        tail = null
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
        const val ALIGN_STEP_MS = 10L
        const val ALIGNED_READINGS = 2
        const val SPEED_STEP = 0.005f
        const val FADE_STEP_MS = 20L
        const val MAX_TAIL_RETRIES = 2
        const val DEFAULT_SKIP_LEAD_MS = 400L
        const val SKIP_START_TIMEOUT_MS = 1_500L
    }
}
