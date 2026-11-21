package io.github.aedev.flow.data.images

import androidx.collection.LruCache
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.pxOrElse

/**
 * Loads a [YouTubeThumbnail] at the tier its drawn size needs. A tier the video lacks answers 404;
 * the next tier is tried inside the same load, and the miss is remembered for the rest of the
 * process so the card never asks for it again. Each attempt is an ordinary URL load, so the memory
 * and disk caches key on the tier's URL.
 */
class YouTubeThumbnailInterceptor(
    private val misses: LruCache<String, Boolean> = LruCache(MAX_REMEMBERED_MISSES),
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val thumbnail = chain.request.data as? YouTubeThumbnail ?: return chain.proceed()
        val size = chain.size
        val urls = untried(thumbnail.urls(size.width.pxOrElse { 0 }, size.height.pxOrElse { 0 }))
        var failure: ImageResult? = null
        for (url in urls) {
            val attempt = chain.request.newBuilder().data(url)
            val result = chain.withRequest(attempt.build()).proceed()
            if (result is SuccessResult) return result
            if (result.isNotFound()) misses.put(url, true)
            failure = result
        }
        return failure ?: ErrorResult(null, chain.request, IllegalArgumentException("No thumbnail for ${thumbnail.videoId}"))
    }

    // The last URL is the tier every video has, so it is kept even after a miss: that miss was a moment's outage.
    private fun untried(urls: List<String>): List<String> {
        if (urls.isEmpty()) return urls
        return urls.dropLast(1).filter { misses[it] == null } + urls.last()
    }

    private fun ImageResult.isNotFound(): Boolean {
        val error = (this as? ErrorResult)?.throwable as? HttpException ?: return false
        return error.response.code == HTTP_NOT_FOUND
    }

    private companion object {
        const val MAX_REMEMBERED_MISSES = 1024
        const val HTTP_NOT_FOUND = 404
    }
}
