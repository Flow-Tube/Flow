package io.github.aedev.flow.player.media

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import io.github.aedev.flow.player.error.StreamDenialClassifier
import java.io.IOException

/** Kept as MediaLoader's tag: a user's diagnostics dump is searched for it. */
private const val TAG = "MediaLoader"

private val FMT_VALUE = Regex("[^A-Za-z0-9]")

/**
 * The facts that tell a failed caption fetch's causes apart (which client minted it, which format
 * was asked for, whether it was a translation, whether it had expired), and nothing that could replay
 * the request: no path, signature, key, session or video id.
 */
internal object SubtitleLoadDiagnostics {
    fun describe(
        url: String?,
        error: Throwable,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): String {
        val remaining = StreamDenialClassifier.expiresInSeconds(url, nowSeconds)
        return listOfNotNull(
            "host=${hostOf(url)}",
            "c=${StreamDenialClassifier.clientOf(url) ?: "none"}",
            "fmt=${fmtValues(url).joinToString(",").ifEmpty { "none" }}",
            "tlang=${presence(url, "tlang")}",
            "source=${sourceOf(url)}",
            "pot=${presence(url, "pot")}",
            if (remaining == null) "expire=absent" else "expiresIn=${remaining}s",
            "status=${statusOf(error) ?: "none"}",
            "error=${error.javaClass.simpleName}",
            error.cause?.let { "cause=${it.javaClass.simpleName}" },
        ).joinToString(" ")
    }

    fun statusOf(error: Throwable): Int? = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode

    /** A translation's request shape, so a refusal can be told from a success when comparing builds (#1369). */
    fun describeTranslatedLoad(
        url: String?,
        bytes: Long,
    ): String = "fmt=${fmtValues(url).joinToString(",").ifEmpty { "none" }} source=${sourceOf(url)} bytes=$bytes"

    fun isTranslated(url: String?): Boolean = presence(url, "tlang") == "yes"

    private fun sourceOf(url: String?): String = if (StreamDenialClassifier.queryParam(url, "kind") == "asr") "asr" else "authored"

    private fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return "none"
        val scheme = url.substringBefore("://", missingDelimiterValue = "")
        if (!scheme.equals("http", true) && !scheme.equals("https", true)) return "local"
        return url
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringAfter('@')
            .ifEmpty { "none" }
    }

    private fun presence(
        url: String?,
        name: String,
    ): String = if (StreamDenialClassifier.queryParam(url, name).isNullOrEmpty()) "no" else "yes"

    /** Every `fmt`, in order: the endpoint honours the first, so a duplicate is itself the finding. */
    private fun fmtValues(url: String?): List<String> {
        val query = url?.substringAfter('?', missingDelimiterValue = "")?.substringBefore('#').orEmpty()
        return query
            .split('&')
            .filter { it.startsWith("fmt=") }
            .map { it.removePrefix("fmt=").replace(FMT_VALUE, "").take(8) }
    }
}

/**
 * Reports a subtitle fetch that has run out of retries.
 *
 * `treatLoadErrorsAsEndOfStream` turns that failure into an empty track, so without this the
 * user picks a language and simply gets nothing, with no clue that anything went wrong.
 * `wasCanceled` is Media3's signal that the loader chose not to retry, i.e. this is final.
 */
@UnstableApi
internal fun subtitleLoadFailureReporter(
    index: Int,
    label: String,
    url: String,
    onGaveUp: (index: Int, label: String) -> Unit,
): MediaSourceEventListener =
    object : MediaSourceEventListener {
        override fun onLoadCompleted(
            windowIndex: Int,
            mediaPeriodId: MediaSource.MediaPeriodId?,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            if (!SubtitleLoadDiagnostics.isTranslated(url)) return
            val shape = SubtitleLoadDiagnostics.describeTranslatedLoad(url, loadEventInfo.bytesLoaded)
            Log.i(TAG, "Translated subtitle '$label' loaded: $shape")
        }

        override fun onLoadError(
            windowIndex: Int,
            mediaPeriodId: MediaSource.MediaPeriodId?,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            if (!wasCanceled) {
                if (SubtitleLoadDiagnostics.isTranslated(url)) {
                    Log.w(TAG, "Translated subtitle '$label' refused, retrying: ${SubtitleLoadDiagnostics.describe(url, error)}")
                } else {
                    Log.d(TAG, "Subtitle '$label' load failed (status=${SubtitleLoadDiagnostics.statusOf(error)}), retrying")
                }
                return
            }
            Log.w(TAG, "Subtitle '$label' gave up after retries: ${SubtitleLoadDiagnostics.describe(url, error)}")
            onGaveUp(index, label)
        }
    }
