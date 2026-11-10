package io.github.aedev.flow.player.preload

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import io.github.aedev.flow.player.stream.ResolvedStreamData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.StreamType

internal data class PreloadTarget(
    val video: Video,
    val fromQueue: Boolean,
)

/** A resolved next video already appended to the player as a second window. */
internal data class PreloadedNext(
    val data: ResolvedStreamData,
    val fromQueue: Boolean,
)

/**
 * Resolves the next video ahead of time and appends it to the player as a second window, so
 * advancing is a `seekToNextMediaItem` instead of a fresh network round trip.
 *
 * Owns only the preload bookkeeping: the appended item, the running jobs, which attempt is in
 * flight and how many retries are left. Everything the promoted video then becomes -- player state,
 * quality manager, session metadata -- stays with the caller, which takes the result via [consume].
 *
 * Main-thread confined, matching the manager it was lifted out of.
 */
@UnstableApi
internal class GaplessPreloadController(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val context: () -> Context?,
    private val currentVideoId: () -> String?,
    private val nextTarget: () -> PreloadTarget?,
    private val isLooping: () -> Boolean,
    private val isLiveStream: () -> Boolean,
    private val resolveStreams: suspend (Video, Context) -> ResolvedStreamData?,
    private val hasLocalCopy: suspend (Video) -> Boolean,
    private val buildMediaSource: (ResolvedStreamData, Context) -> MediaSource?,
    private val log: (String) -> Unit,
    private val isGone: (String) -> Boolean = InnerTubeVideoStreamExtractor::isGone,
) {
    private companion object {
        const val TAG = "GaplessPreload"
        const val RETRY_DELAY_MS = 10_000L
        const val MAX_RETRIES = 3
    }

    @Volatile
    var preloaded: PreloadedNext? = null
        private set

    private var job: Job? = null
    private var retryJob: Job? = null
    private var attemptVideoId: String? = null
    private var attemptNextVideoId: String? = null
    private var retryCount: Int = 0

    /** Preload fields for the manager's diagnostic snapshot. */
    val diagnostics: String
        get() =
            "preloaded=${preloaded?.data?.enrichedVideo?.id} " +
                "attempt=$attemptVideoId->$attemptNextVideoId retry=$retryCount"

    /**
     * Re-targets the preload after something changed what plays next, dropping an already appended
     * item or an in-flight attempt aimed at a video that is no longer the target. With no target at
     * all, the appended item goes too, or the player would still roll into it.
     */
    fun request(reason: String) {
        val target =
            nextTarget() ?: run {
                log("requestPreloadNext no target reason=$reason")
                clear()
                return
            }
        log("requestPreloadNext reason=$reason target=${target.video.id} fromQueue=${target.fromQueue}")

        val existing = preloaded
        if (existing != null && existing.data.enrichedVideo.id != target.video.id) {
            Log.d(TAG, "Replacing stale preload ${existing.data.enrichedVideo.id} with ${target.video.id} ($reason)")
            clear()
        }

        if (job?.isActive == true && attemptNextVideoId != target.video.id) {
            Log.d(TAG, "Cancelling stale preload attempt for $attemptNextVideoId; next is ${target.video.id} ($reason)")
            job?.cancel()
            job = null
            attemptVideoId = null
            attemptNextVideoId = null
            retryCount = 0
        }

        schedule()
    }

    /** Starts preloading the next video, unless the player or the session says not to. */
    fun schedule() {
        if (isLooping()) {
            log("schedulePreloadNext skipped looping")
            return
        }
        val p =
            player() ?: run {
                log("schedulePreloadNext skipped no player")
                return
            }
        if (isLiveStream()) {
            log("schedulePreloadNext skipped live")
            return
        }
        if (preloaded != null || job?.isActive == true) {
            log("schedulePreloadNext skipped already busy")
            return
        }
        if (p.currentMediaItem == null || p.mediaItemCount == 0) {
            log("schedulePreloadNext skipped no current media item")
            return
        }
        if (p.mediaItemCount > 1) {
            log("schedulePreloadNext skipped mediaItemCount=${p.mediaItemCount}")
            return
        }
        val currentId =
            currentVideoId() ?: run {
                log("schedulePreloadNext skipped no currentId")
                return
            }
        val target =
            nextTarget() ?: run {
                log("schedulePreloadNext skipped no target")
                return
            }
        if (attemptVideoId == currentId && attemptNextVideoId == target.video.id) {
            log("schedulePreloadNext skipped duplicate attempt next=${target.video.id}")
            return
        }

        attemptVideoId = currentId
        attemptNextVideoId = target.video.id
        retryJob?.cancel()
        retryJob = null
        log("schedulePreloadNext start next=${target.video.id} fromQueue=${target.fromQueue}")
        job = scope.launch { runPreload(currentId, target) }
    }

    private suspend fun runPreload(
        currentId: String,
        target: PreloadTarget,
    ) {
        val nextVideo = target.video
        var success = false
        var shouldRetry = false
        try {
            val ctx = context() ?: return
            if (hasLocalCopy(nextVideo)) {
                log("schedulePreloadNext next=${nextVideo.id} is downloaded; advance plays the file")
                return
            }
            val resolved =
                resolveStreams(nextVideo, ctx) ?: run {
                    // Every client already said the video is removed; another ladder run says it again.
                    shouldRetry = !isGone(nextVideo.id)
                    log("schedulePreloadNext resolve failed next=${nextVideo.id} retry=$shouldRetry")
                    return
                }
            if (resolved.streamType == StreamType.LIVE_STREAM) {
                log("schedulePreloadNext resolved live next=${nextVideo.id}; skip")
                return
            }
            val pl = player() ?: return
            if (isStaleBeforeAppend(currentId, target, pl)) {
                log("schedulePreloadNext stale before append next=${nextVideo.id}")
                return
            }
            val source =
                buildMediaSource(resolved, ctx) ?: run {
                    shouldRetry = true
                    log("schedulePreloadNext mediaSource failed next=${nextVideo.id}")
                    return
                }
            pl.addMediaSource(source)
            preloaded = PreloadedNext(resolved, target.fromQueue)
            retryCount = 0
            success = true
            log("schedulePreloadNext appended next=${resolved.enrichedVideo.id} fromQueue=${target.fromQueue}")
            Log.d(
                TAG,
                "Preloaded next ${resolved.enrichedVideo.id} (fromQueue=${target.fromQueue}) as window ${pl.mediaItemCount - 1}",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            shouldRetry = true
            Log.w(TAG, "Gapless preload failed", e)
        } finally {
            job = null
            if (!success && currentVideoId() == currentId && preloaded == null) {
                attemptVideoId = null
                attemptNextVideoId = null
                if (shouldRetry) scheduleRetry(currentId, nextVideo.id)
            }
        }
    }

    /** Resolving takes seconds, in which the session may have moved on or already grown a window. */
    private fun isStaleBeforeAppend(
        currentId: String,
        target: PreloadTarget,
        p: ExoPlayer,
    ): Boolean {
        val latest = nextTarget()
        return currentVideoId() != currentId ||
            latest == null ||
            latest.video.id != target.video.id ||
            latest.fromQueue != target.fromQueue ||
            p.mediaItemCount > 1 ||
            isLooping()
    }

    private fun scheduleRetry(
        anchorVideoId: String,
        nextVideoId: String,
    ) {
        if (retryCount >= MAX_RETRIES) {
            log("schedulePreloadRetry giving up next=$nextVideoId")
            Log.w(TAG, "Giving up preloading $nextVideoId after $retryCount retries")
            return
        }
        retryCount++
        log("schedulePreloadRetry scheduled next=$nextVideoId count=$retryCount")
        retryJob?.cancel()
        retryJob =
            scope.launch {
                delay(RETRY_DELAY_MS)
                if (currentVideoId() == anchorVideoId &&
                    preloaded == null &&
                    job?.isActive != true &&
                    nextTarget()?.video?.id == nextVideoId
                ) {
                    log("schedulePreloadRetry firing next=$nextVideoId count=$retryCount")
                    schedule()
                } else {
                    log("schedulePreloadRetry stale next=$nextVideoId")
                }
            }
    }

    /** Drops the preload, including the window already appended to the player. */
    fun clear() {
        log("clearPreload")
        cancelJobs()
        attemptVideoId = null
        attemptNextVideoId = null
        retryCount = 0
        if (preloaded != null) {
            preloaded = null
            val p = player()
            if (p != null && p.mediaItemCount > 1) {
                val current = p.currentMediaItemIndex
                for (i in p.mediaItemCount - 1 downTo current + 1) {
                    runCatching { p.removeMediaItem(i) }
                }
            }
        }
    }

    /**
     * Hands over the preloaded video because the player has advanced into it. The appended window
     * is left alone: it is what is playing now.
     */
    fun consume(): PreloadedNext? {
        val pre = preloaded ?: return null
        preloaded = null
        cancelJobs()
        return pre
    }

    /** Starts looking for the next preload once a promoted video is fully in place. */
    fun restartAfterAdvance() {
        attemptVideoId = null
        attemptNextVideoId = null
        retryCount = 0
        schedule()
    }

    private fun cancelJobs() {
        job?.cancel()
        job = null
        retryJob?.cancel()
        retryJob = null
    }
}
