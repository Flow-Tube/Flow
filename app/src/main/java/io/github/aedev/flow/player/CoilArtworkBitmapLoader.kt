package io.github.aedev.flow.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import io.github.aedev.flow.service.auto.AutoArtworkProvider
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/** Waits between the attempts at one song's artwork: a network that dropped at a track change is usually back within these. */
internal val SESSION_ARTWORK_RETRY_DELAYS_MS = listOf(2_000L, 10_000L, 30_000L)

private val YTIMG_SIZED_NAME = Regex("/(hq720|maxresdefault|sddefault)(\\.jpg|\\.webp)")

/**
 * Loads session artwork through the app's Coil loader, so it goes over the app's OkHttp client,
 * proxy and disk cache, and retries a failed load. Media3 loads each song's artwork once and never
 * again, so one failed fetch at a track change left the notification without art for the whole
 * song (#1637). Media3 ships no Coil loader, hence this adapter; decoding embedded artwork stays
 * with [decoder].
 */
@OptIn(UnstableApi::class)
internal class CoilArtworkBitmapLoader(
    private val context: Context,
    private val maxDimensionPx: Int,
    private val decoder: BitmapLoader,
) : BitmapLoader by decoder {
    private val scope = CoroutineScope(SupervisorJob() + PerformanceDispatcher.networkIO)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        // Session items carry web artwork in the car's content:// form; Coil loads the web address directly.
        val url = AutoArtworkProvider.webUrlOf(uri.toString())
        val web = url.startsWith("http://") || url.startsWith("https://")
        val delays = if (web) SESSION_ARTWORK_RETRY_DELAYS_MS else emptyList()
        val job =
            scope.launch {
                try {
                    future.set(fetchWithRetries(sessionArtworkCandidates(url), delays, ::fetch))
                } catch (e: CancellationException) {
                    future.cancel(false)
                    throw e
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
        future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return future
    }

    private suspend fun fetch(url: String): Bitmap {
        val request =
            ImageRequest
                .Builder(context)
                .data(url)
                .size(maxDimensionPx)
                .precision(Precision.INEXACT)
                .allowHardware(false)
                // Some ROMs recycle the bitmap a session is given (#1017), so never share one with the UI's cache.
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
        return when (val result = SingletonImageLoader.get(context).execute(request)) {
            is SuccessResult -> result.image.toBitmap()
            is ErrorResult -> throw IOException("Artwork failed: $url", result.throwable)
        }
    }
}

/** The artwork URL, then the size YouTube serves for every video when the larger one is missing. */
internal fun sessionArtworkCandidates(url: String): List<String> {
    if (!url.contains("ytimg.com/")) return listOf(url)
    val fallback = YTIMG_SIZED_NAME.replace(url) { "/hqdefault${it.groupValues[2]}" }
    return if (fallback == url) listOf(url) else listOf(url, fallback)
}

/** Tries every candidate in turn, and the whole list again after each delay, until one loads. */
internal suspend fun <T : Any> fetchWithRetries(
    candidates: List<String>,
    delaysMs: List<Long>,
    fetch: suspend (String) -> T,
): T {
    var lastError: Exception = IOException("No artwork candidates")
    for (attempt in 0..delaysMs.size) {
        if (attempt > 0) delay(delaysMs[attempt - 1])
        for (candidate in candidates) {
            try {
                return fetch(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
    }
    throw lastError
}
