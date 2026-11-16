package io.github.aedev.flow.player.crossfade

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** Where a skip goes: the queue slot, and the song that has to be there or found elsewhere in the queue. */
internal data class SkipTarget(
    val index: Int,
    val mediaId: String,
)

/**
 * A song the user skips away from has no tail waiting, so one is built from where the song is. A skip
 * from inside the app ([skipTo]) can wait for it: the session goes on playing the old song while the
 * tail starts muted, and changes song once the tail is playing, so the old song never goes quiet. This
 * runs with crossfade off too, as a [CrossfadePolicy.SKIP_BLEND_MS] overlap. A skip from the notification
 * or a headset has already changed the song by the time it gets here ([beginSkipFade]), so it only gets a
 * tail while the settings ask for a fade, and the next song stays muted until the tail plays. Either way
 * the fade runs once both are playing, on the [MusicCrossfader] that owns this. A next song still loading
 * [CrossfadePolicy.LATE_NEXT_SONG_MS] after the hand-off doesn't hold the old one at full volume: that one
 * fades out anyway, and the next one comes in quickly once it plays (#2076). A song picked from a list
 * ([holdForNewQueue]) is held the same way, with the app replacing the queue once the tail plays.
 */
internal class CrossfadeSkip(
    private val session: ExoPlayer,
    private val handler: Handler,
    private val moment: (settings: CrossfadePolicy.Settings) -> CrossfadePolicy.Moment,
    private val host: Host,
) {
    /** What a skip reads and moves on the crossfader. */
    interface Host {
        val stage: CrossfadeStage
        val settings: CrossfadePolicy.Settings
        val tail: ExoPlayer?
        val sessionScale: Float

        fun scaleSession(scale: Float)

        /** Builds the tail that carries the old song for [clockMs], set up by [setUp], and starts waiting for it. */
        fun startSkipTail(
            mediaId: String,
            gain: Float,
            clockMs: Long,
            setUp: ExoPlayer.() -> Unit,
        )

        fun restoreTailGain()

        fun waitForNextSong()

        /** Starts the fade; a [late] next song isn't playing yet and comes in over [CrossfadePolicy.lateFadeInClockMs]. */
        fun startFade(late: Boolean)

        /** Holds the old song's volume where it is while the session moves to another song. */
        fun holdFade()

        fun seekSession(index: Int)

        /** Drops the tail and brings the session's song in over [CrossfadePolicy.QUICK_FADE_IN_MS], at [index] if given. */
        fun fadeNextSongIn(
            reason: String,
            index: Int?,
        )

        fun abort(reason: String?)

        fun schedule()
    }

    /** Where a skip goes once the tail plays; the session stays on the old song until then. */
    private var heldTarget: SkipTarget? = null

    /** Told whether the session may take a new queue, once the old song plays on its tail or can't. */
    private var queueHold: ((Boolean) -> Unit)? = null

    /** True while the tail carries the old song and the session, muted, waits for a new queue. */
    var awaitingQueue = false
        private set

    /** Where a second skip goes once the song that was fading in has eased out. */
    private var easingTo: SkipTarget? = null
    private var easeFrom = 0f
    private var easeBeganAtMs = 0L
    private var skipPressedAtMs = 0L
    private var skipLeadMs = DEFAULT_SKIP_LEAD_MS
    private var skippedId: String? = null
    private var overlapMs = 0L
    private var waitingSinceMs = 0L

    // A started player is heard a moment after it reports playing, and only this says the audio has begun.
    @OptIn(UnstableApi::class)
    private val skipTailListener =
        object : AnalyticsListener {
            override fun onAudioPositionAdvancing(
                eventTime: AnalyticsListener.EventTime,
                playoutStartSystemTimeMs: Long,
            ) = skipTailPlaying()
        }

    private val easeStep = Runnable { ease() }
    private val lateSongStep = Runnable { nextSongLate() }
    private val skipTimeoutStep = Runnable { if (host.stage == CrossfadeStage.SKIP_STARTING) skipTailOutOfTime() }
    private val queueTimeoutStep = Runnable { if (awaitingQueue) host.abort("the new queue never came") }

    /** True while a skip waits for its tail, with the session still on the old song. */
    val holding: Boolean get() = heldTarget != null

    fun forgetHeld() {
        heldTarget = null
    }

    /** Lets go of where the skip was going; the caller still takes the session there. A new queue goes ahead unfaded. */
    fun dropTargets(): SkipTarget? {
        val held = heldTarget ?: easingTo
        heldTarget = null
        easingTo = null
        forgetQueue()
        queueHold?.let { answer ->
            queueHold = null
            answer(false)
        }
        return held
    }

    fun forgetQueue() {
        awaitingQueue = false
        handler.removeCallbacks(queueTimeoutStep)
    }

    fun cancelSteps() {
        handler.removeCallbacks(easeStep)
        handler.removeCallbacks(skipTimeoutStep)
        handler.removeCallbacks(lateSongStep)
    }

    /**
     * A song picked from a list replaces the whole queue (#2163). Before it does, the old song moves to a
     * tail from where it is, so it plays on while the new one loads. [answer] gets true once the tail
     * plays and the session can take the new queue, false when the queue should go ahead without a fade.
     */
    fun holdForNewQueue(answer: (Boolean) -> Unit) {
        if (awaitingQueue) {
            handler.removeCallbacks(queueTimeoutStep)
            handler.postDelayed(queueTimeoutStep, QUEUE_TIMEOUT_MS)
            answer(true)
            return
        }
        if (queueHold != null) {
            queueHold = answer
            return
        }
        val left = session.currentMediaItem
        val durationMs = left?.let { durationOf(it, session.currentMediaItemIndex) }
        val resumeAt =
            left
                ?.takeIf { heldTarget == null && CrossfadePolicy.newQueueFades(host.settings, host.stage, session.isPlaying) }
                ?.let { skipResumeAt(session.currentPosition, durationMs) }
        if (left == null || durationMs == null || resumeAt == null) {
            answer(false)
            return
        }
        Log.d(CROSSFADE_TAG, "new queue: holding ${left.mediaId} on a tail")
        val gain = session.volume
        host.abort(null)
        queueHold = answer
        startSkipTail(left, durationMs, resumeAt, gain, tailVolume = 0f)
    }

    /** The new queue has replaced the old one under the tail; the fade waits for its first song. */
    fun queueArrived() {
        if (!awaitingQueue) return
        forgetQueue()
        if (session.isPlaying) startFade() else waitForLateSong()
    }

    /** Next or Previous from inside the app, which plays the song at [index] (still [mediaId]) whatever else happens. */
    fun skipTo(
        index: Int,
        mediaId: String,
    ) {
        val target = SkipTarget(index, mediaId)
        val left = session.currentMediaItem
        val durationMs = left?.let { durationOf(it, session.currentMediaItemIndex) }
        val resumeAt = left?.takeIf { heldTarget == null && session.isPlaying }?.let { skipResumeAt(session.currentPosition, durationMs) }
        val press = CrossfadePolicy.onSkipPress(skipPhase(), session.currentMediaItemIndex == index, resumeAt != null)
        Log.d(CROSSFADE_TAG, "skip to $mediaId: $press")
        when (press) {
            CrossfadePolicy.SkipPress.START_TAIL -> {
                holdSkip(target, requireNotNull(left), requireNotNull(durationMs), requireNotNull(resumeAt))
            }

            CrossfadePolicy.SkipPress.MOVE_TARGET -> {
                heldTarget = target
            }

            CrossfadePolicy.SkipPress.BACK_TO_PLAYING_SONG -> {
                stayOnPlayingSong()
            }

            CrossfadePolicy.SkipPress.SEEK_UNDER_TAIL -> {
                seekUnderTail(target)
            }

            CrossfadePolicy.SkipPress.PLAIN -> {
                skipPlainly(target)
            }
        }
    }

    private fun holdSkip(
        target: SkipTarget,
        left: MediaItem,
        durationMs: Long,
        resumeAt: Long,
    ) {
        val gain = session.volume
        host.abort(null)
        heldTarget = target
        startSkipTail(left, durationMs, resumeAt, gain, tailVolume = 0f)
    }

    private fun stayOnPlayingSong() {
        heldTarget = null
        host.abort("the skip came back to the song still playing")
        host.schedule()
    }

    private fun skipPlainly(target: SkipTarget) {
        host.abort(null)
        indexOf(target)?.let(host::seekSession)
        session.play()
        host.schedule()
    }

    private fun skipPhase(): CrossfadePolicy.SkipPhase {
        val stage = host.stage
        val tailCarriesOldSong =
            stage == CrossfadeStage.SKIP_STARTING || stage == CrossfadeStage.WAITING_FOR_NEXT_SONG || stage == CrossfadeStage.FADING
        return when {
            heldTarget != null -> CrossfadePolicy.SkipPhase.HOLDING
            tailCarriesOldSong -> CrossfadePolicy.SkipPhase.UNDER_TAIL
            else -> CrossfadePolicy.SkipPhase.NONE
        }
    }

    private fun skipResumeAt(
        positionMs: Long,
        durationMs: Long?,
    ): Long? {
        if (!session.playWhenReady || moment(host.settings).showsVideo) return null
        return CrossfadePolicy.skipResumeMs(positionMs, skipLeadMs, durationMs, session.playerError != null)
    }

    /** Most songs carry their length; one that doesn't takes what the player found once it loaded the song at [index]. */
    private fun durationOf(
        item: MediaItem,
        index: Int,
    ): Long? {
        val timeline = session.currentTimeline
        val window = if (index in 0 until timeline.windowCount) timeline.getWindow(index, Timeline.Window()) else null
        val playerMs = window?.takeIf { it.mediaItem.mediaId == item.mediaId }?.durationMs ?: C.TIME_UNSET
        return CrossfadePolicy.knownDurationMs(item.mediaMetadata.durationMs, playerMs)
    }

    /** The song has already changed by hand: the old one returns on a tail from where it was and fades under the new one. */
    fun beginSkipFade(
        left: MediaItem,
        leftIndex: Int,
        positionMs: Long,
    ): Boolean {
        if (!host.settings.fadesOnSkip) return false
        val durationMs = durationOf(left, leftIndex)
        val resumeAt = skipResumeAt(positionMs, durationMs) ?: return false
        val gain = session.volume
        host.abort(null)
        host.scaleSession(0f)
        startSkipTail(left, requireNotNull(durationMs), resumeAt, gain, tailVolume = gain)
        return true
    }

    @OptIn(UnstableApi::class)
    private fun startSkipTail(
        item: MediaItem,
        durationMs: Long,
        resumeAt: Long,
        gain: Float,
        tailVolume: Float,
    ) {
        skipPressedAtMs = SystemClock.elapsedRealtime()
        val clockMs = CrossfadePolicy.skipClockMs(host.settings, resumeAt, durationMs, session.playbackParameters.speed)
        skippedId = item.mediaId
        overlapMs = clockMs
        host.startSkipTail(item.mediaId, gain, clockMs) {
            volume = tailVolume
            addAnalyticsListener(skipTailListener)
            if (CrossfadePolicy.tailStartsAtSyncPoint(host.settings, heldTarget != null || queueHold != null)) startAtSyncPoint(resumeAt)
            setMediaItem(item, resumeAt)
            playWhenReady = true
            prepare()
        }
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
        if (host.tail?.isPlaying == true) {
            skipTailPlaying()
            return
        }
        if (queueHold != null) {
            host.abort("the tail took too long before a new queue")
            host.schedule()
            return
        }
        val target = heldTarget
        val index = target?.let(::indexOf)
        if (target != null && index == null) {
            host.abort("the tail took too long after a skip")
            host.schedule()
            return
        }
        // Without a tail the old song can only fade by holding the skip back even longer, so the next one fades in instead.
        heldTarget = null
        easingTo = null
        host.fadeNextSongIn("the tail took too long after a skip", index)
    }

    private fun skipTailPlaying() {
        if (host.stage != CrossfadeStage.SKIP_STARTING) return
        val waited = SystemClock.elapsedRealtime() - skipPressedAtMs
        skipLeadMs = CrossfadePolicy.learnedHeadStartMs(skipLeadMs, skipLeadMs - waited)
        val apart = (host.tail?.currentPosition ?: 0L) - session.currentPosition
        Log.d(
            CROSSFADE_TAG,
            "skipped $skippedId, its tail played after ${waited}ms, ${apart}ms from the session, fading over ${overlapMs}ms",
        )
        handler.removeCallbacks(skipTimeoutStep)
        queueHold?.let { answer ->
            queueHold = null
            host.restoreTailGain()
            host.scaleSession(0f)
            host.waitForNextSong()
            awaitingQueue = true
            handler.postDelayed(queueTimeoutStep, QUEUE_TIMEOUT_MS)
            answer(true)
            return
        }
        val target = heldTarget
        heldTarget = null
        if (target != null) {
            val index = indexOf(target)
            if (index == null) {
                host.abort("the skipped-to song left the queue")
                return
            }
            host.restoreTailGain()
            host.scaleSession(0f)
            host.seekSession(index)
        }
        if (session.isPlaying) {
            startFade()
        } else {
            host.waitForNextSong()
            waitForLateSong()
        }
    }

    private fun waitForLateSong() {
        waitingSinceMs = SystemClock.elapsedRealtime()
        handler.removeCallbacks(lateSongStep)
        handler.postDelayed(lateSongStep, CrossfadePolicy.LATE_NEXT_SONG_MS)
    }

    /** The next song is still loading, so the old one fades out now and the next one comes in quickly once it plays. */
    private fun nextSongLate() {
        if (heldTarget != null || easingTo != null || host.stage != CrossfadeStage.WAITING_FOR_NEXT_SONG || session.isPlaying) return
        val waited = SystemClock.elapsedRealtime() - waitingSinceMs
        Log.d(CROSSFADE_TAG, "the song after $skippedId is late, fading it out ${waited}ms after the hand-off")
        handler.removeCallbacks(skipTimeoutStep)
        host.startFade(late = true)
    }

    /** The next song has started: a tail already playing fades it in, and one still starting has [SKIP_START_TIMEOUT_MS] to. */
    fun nextSongPlaying() {
        if (heldTarget == null && easingTo == null && !awaitingQueue && host.stage == CrossfadeStage.WAITING_FOR_NEXT_SONG) startFade()
    }

    private fun startFade() {
        handler.removeCallbacks(skipTimeoutStep)
        handler.removeCallbacks(lateSongStep)
        host.startFade(late = false)
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
        if (host.stage == CrossfadeStage.FADING && host.sessionScale > 0f) {
            host.holdFade()
            easingTo = target
            easeFrom = host.sessionScale
            easeBeganAtMs = SystemClock.elapsedRealtime()
            handler.post(easeStep)
            return
        }
        moveUnderTail(target)
    }

    private fun ease() {
        val target = easingTo ?: return
        val progress = CrossfadePolicy.progress(SystemClock.elapsedRealtime() - easeBeganAtMs, CrossfadePolicy.EASE_OUT_MS)
        host.scaleSession(easeFrom * (1f - progress))
        if (progress < 1f) {
            handler.postDelayed(easeStep, FADE_STEP_MS)
            return
        }
        easingTo = null
        moveUnderTail(target)
    }

    private fun moveUnderTail(target: SkipTarget) {
        val index = indexOf(target) ?: return
        host.holdFade()
        host.seekSession(index)
        if (host.stage == CrossfadeStage.WAITING_FOR_NEXT_SONG && !session.isPlaying) waitForLateSong()
    }

    fun indexOf(target: SkipTarget): Int? {
        val count = session.mediaItemCount
        if (target.index < count && session.getMediaItemAt(target.index).mediaId == target.mediaId) return target.index
        return (0 until count).firstOrNull { session.getMediaItemAt(it).mediaId == target.mediaId }
    }

    private companion object {
        const val DEFAULT_SKIP_LEAD_MS = 400L
        const val SKIP_START_TIMEOUT_MS = 1_500L

        /** How long a muted session waits for the app to hand it the new queue before the old song comes back. */
        const val QUEUE_TIMEOUT_MS = 2_500L
    }
}
