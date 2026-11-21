package io.github.aedev.flow.data.images

import androidx.collection.LruCache
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Scale
import coil3.size.pxOrElse

/**
 * Loads a [DrawnSizeImage] at the URL its drawn size needs. A URL that answers 404 (a YouTube tier
 * the video lacks) hands over to the next one inside the same load, and the miss is remembered for
 * the rest of the process so the image never asks for it again. Each attempt is an ordinary URL
 * load, so the memory and disk caches key on that URL.
 */
class DrawnSizeImageInterceptor(
    private val misses: LruCache<String, Boolean> = LruCache(MAX_REMEMBERED_MISSES),
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val image = chain.request.data as? DrawnSizeImage ?: return chain.proceed()
        val size = chain.size
        val urls =
            untried(
                image.urls(
                    widthPx = size.width.pxOrElse { 0 },
                    heightPx = size.height.pxOrElse { 0 },
                    crop = chain.request.scale == Scale.FILL,
                ),
            )
        var failure: ImageResult? = null
        for (url in urls) {
            val attempt = chain.request.newBuilder().data(url)
            val result = chain.withRequest(attempt.build()).proceed()
            if (result is SuccessResult) return result
            if (result.isNotFound()) misses.put(url, true)
            failure = result
        }
        return failure ?: ErrorResult(null, chain.request, IllegalArgumentException("No URL for $image"))
    }

    // The last URL is the one that always exists (hqdefault for YouTube), so it is kept even after a
    // miss: that miss was a moment's outage.
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
