package io.github.aedev.flow.player.crossfade

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
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
 * again within a second, so the fade can start up to that window early; nothing repeats or skips. A
 * tail that cannot line up in time is dropped and the song ends as it does without crossfade.
 *
 * Every step is driven by the session player: [PlayerMessage]s at a position, its listener for seeks,
 * skips and pauses. The only timed loops run while a song is changing hands: [ALIGN_STEP_MS] checks for
 * at most two seconds, then [FADE_STEP_MS] volume steps for the fade. Positions are compared through
 * [PositionAnchor], since a player reports them in coarse steps.
 */
internal class MusicCrossfader(
    private val session: ExoPlayer,
    private val buildTail: () -> ExoPlayer,
    private val moment: (fadeSeconds: Int, keepAlbumsGapless: Boolean) -> CrossfadePolicy.Moment,
    private val onSessionScaleChanged: () -> Unit,
) {
    private enum class Stage { IDLE, PREPARED, ALIGNING, FADING }

    private val handler = Handler(Looper.getMainLooper())
    private var stage = Stage.IDLE
    private var fadeSeconds = 0
    private var keepAlbumsGapless = true

    private var tail: ExoPlayer? = null
    private var prepareMessage: PlayerMessage? = null
    private var alignMessage: PlayerMessage? = null
    private var mediaId: String? = null
    private var fadeStartMs = 0L
    private var fadeBeganAtMs = 0L
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
                if (stage == Stage.FADING) abort("the song changed during the fade")
                schedule()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (handingOff || reason != Player.DISCONTINUITY_REASON_SEEK) return
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
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (stage == Stage.FADING) return
                schedule()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (stage != Stage.FADING) schedule()
            }

            override fun onTimelineChanged(
                timeline: Timeline,
                reason: Int,
            ) {
                if (stage != Stage.FADING && reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) schedule()
            }
        }

    private val alignStep = Runnable { align() }
    private val fadeStep = Runnable { fade() }

    fun attach() = session.addListener(listener)

    /** The chosen fade length (0 is off) and whether an album's tracks stay gapless. */
    fun setSettings(
        seconds: Int,
        keepAlbums: Boolean,
    ) {
        if (seconds == fadeSeconds && keepAlbums == keepAlbumsGapless) return
        fadeSeconds = seconds
        keepAlbumsGapless = keepAlbums
        if (stage != Stage.FADING) schedule()
    }

    fun release() {
        session.removeListener(listener)
        abort(null)
    }

    private fun schedule() {
        if (stage == Stage.FADING) return
        if (stage != Stage.IDLE) abort(null)
        cancelMessages()
        val start = CrossfadePolicy.fadeStartMs(moment(fadeSeconds, keepAlbumsGapless)) ?: return
        val item = session.currentMediaItem ?: return
        val alignAt = start - CrossfadePolicy.ALIGN_WINDOW_MS
        val prepareAt = alignAt - CrossfadePolicy.PREPARE_LEAD_MS
        val position = session.currentPosition
        if (position >= alignAt) return
        val index = session.currentMediaItemIndex
        mediaId = item.mediaId
        fadeStartMs = start
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
        if (player.playbackState != Player.STATE_READY) {
            abort("the tail was not buffered in time")
            return
        }
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
        val remaining = fadeStartMs - sessionMs
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
        Log.d(TAG, "handed off $mediaId to the tail ${deltaMs}ms apart, fading over ${fadeSeconds}s")
        handler.post(fadeStep)
    }

    private fun fade() {
        val player = tail ?: return
        if (stage != Stage.FADING) return
        val progress = (SystemClock.elapsedRealtime() - fadeBeganAtMs) / (fadeSeconds * MILLIS_PER_SECOND).coerceAtLeast(1f)
        player.volume = tailGain * CrossfadePolicy.fadeOutGain(progress)
        sessionScale = CrossfadePolicy.fadeInGain(progress)
        onSessionScaleChanged()
        if (progress >= 1f || player.playbackState == Player.STATE_ENDED) {
            finish()
        } else {
            handler.postDelayed(fadeStep, FADE_STEP_MS)
        }
    }

    private fun finish() {
        releaseTail()
        stage = Stage.IDLE
        restoreScale()
        schedule()
    }

    private fun abort(reason: String?) {
        if (reason != null) Log.d(TAG, "crossfade dropped: $reason")
        cancelMessages()
        releaseTail()
        stage = Stage.IDLE
        restoreScale()
    }

    private fun releaseTail() {
        handler.removeCallbacks(alignStep)
        handler.removeCallbacks(fadeStep)
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
        const val MILLIS_PER_SECOND = 1_000f
    }
}
