package io.github.aedev.flow.player.shorts

import android.util.Log
import io.github.aedev.flow.player.error.PlayerDiagnostics
import java.util.concurrent.ConcurrentHashMap

/**
 * Splits "why is this Short slow to start" into the two legs that can own the delay: resolving the
 * stream URLs, and then getting a first frame out of them.
 *
 * Without this the two are indistinguishable from the outside — the page shows the same still
 * thumbnail either way — and the fix for one is nothing like the fix for the other. Entries land in
 * [PlayerDiagnostics], so a report can be read off the device instead of through adb.
 */
object ShortsStartupTrace {
    private const val TAG = "ShortsStartup"

    private val openedAt = ConcurrentHashMap<String, Long>()
    private val preparedAt = ConcurrentHashMap<String, Long>()
    private val reported = ConcurrentHashMap.newKeySet<String>()
    private val activatedAt = ConcurrentHashMap<String, Long>()

    /** The moment the screen decided it needs this short playable. */
    fun onRequested(videoId: String) {
        openedAt.putIfAbsent(videoId, now())
    }

    fun onStreamsResolved(
        videoId: String,
        cached: Boolean,
        strategy: String,
    ) {
        val requestedAt = openedAt[videoId] ?: return
        report("$videoId streams in ${now() - requestedAt}ms (${if (cached) "cache" else strategy})")
    }

    /** [ShortsPlayerPool.prepare] handed the URLs to an ExoPlayer. */
    fun onPrepared(videoId: String) {
        preparedAt[videoId] = now()
        reported.remove(videoId)
    }

    /** First frame on screen — the number the user actually experiences. */
    fun onFirstFrame(videoId: String?) {
        val id = videoId ?: return
        if (!reported.add(id)) return
        val prepared = preparedAt[id]
        val requested = openedAt[id]
        val buffer = prepared?.let { now() - it }
        val total = requested?.let { now() - it }
        report("$id FIRST FRAME: buffer=${buffer ?: -1}ms total=${total ?: -1}ms")
    }

    /** The pager made this short the active page; the clock for [onPlaying]. */
    fun onActivated(videoId: String) {
        activatedAt.putIfAbsent(videoId, now())
    }

    /** The active short started playing: what a swipe costs, whether or not its frame was already drawn. */
    fun onPlaying(videoId: String?) {
        val id = videoId ?: return
        val activated = activatedAt.remove(id) ?: return
        report("$id SWIPE TO PLAY: ${now() - activated}ms")
    }

    /** Playback state transitions on the active short, so a stall is visible as a gap. */
    fun onState(
        videoId: String?,
        state: String,
    ) {
        report("${videoId ?: "?"} state=$state")
    }

    /** PlayerDiagnostics is an in-app buffer only, so mirror to logcat for live tracing. */
    private fun report(message: String) {
        Log.w(TAG, message)
        PlayerDiagnostics.logWarning(TAG, message)
    }

    fun forget(videoId: String) {
        openedAt.remove(videoId)
        preparedAt.remove(videoId)
        reported.remove(videoId)
        activatedAt.remove(videoId)
    }

    private fun now(): Long = System.currentTimeMillis()
}
