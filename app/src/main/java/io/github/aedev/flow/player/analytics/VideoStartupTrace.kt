package io.github.aedev.flow.player.analytics

import android.os.SystemClock
import android.util.Log
import io.github.aedev.flow.player.error.PlayerDiagnostics
import java.util.concurrent.ConcurrentHashMap

/**
 * Splits a regular video's tap-to-first-frame into its legs: resolving streams, then prepare to the
 * first frame. The Shorts pool has its own [io.github.aedev.flow.player.shorts.ShortsStartupTrace].
 */
object VideoStartupTrace {
    private const val TAG = "VideoStartup"

    private val requestedAt = ConcurrentHashMap<String, Long>()
    private val resolvedAt = ConcurrentHashMap<String, Long>()
    private val preparedAt = ConcurrentHashMap<String, Long>()

    fun onRequested(videoId: String) {
        requestedAt[videoId] = now()
        resolvedAt.remove(videoId)
        preparedAt.remove(videoId)
    }

    fun onResolved(videoId: String) {
        resolvedAt.putIfAbsent(videoId, now())
    }

    fun onPrepared(videoId: String?) {
        val id = videoId ?: return
        if (requestedAt.containsKey(id)) preparedAt.putIfAbsent(id, now())
    }

    fun onFirstFrame(videoId: String?) {
        val id = videoId ?: return
        val requested = requestedAt.remove(id) ?: return
        val resolved = resolvedAt.remove(id)
        val prepared = preparedAt.remove(id)
        val at = now()
        report(
            "$id FIRST FRAME total=${at - requested}ms resolve=${resolved?.let { it - requested } ?: -1}ms " +
                "prepareToFrame=${prepared?.let { at - it } ?: -1}ms",
        )
    }

    private fun report(message: String) {
        Log.w(TAG, message)
        PlayerDiagnostics.logWarning(TAG, message)
    }

    private fun now(): Long = SystemClock.elapsedRealtime()
}
