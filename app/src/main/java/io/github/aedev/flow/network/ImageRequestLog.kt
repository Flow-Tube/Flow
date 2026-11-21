package io.github.aedev.flow.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only image request log under the `FlowRequests` tag: one line per image fetch with its
 * status, bytes and time, followed by the running totals for its host since the process started.
 * Only debug builds add it to the image client.
 */
object ImageRequestLog : Interceptor {
    private const val TAG = "FlowRequests"
    private const val HTTP_NOT_FOUND = 404
    private const val BYTES_PER_KB = 1024L

    private class HostTotals {
        val requests = AtomicLong()
        val notFound = AtomicLong()
        val bytes = AtomicLong()
    }

    private val totals = ConcurrentHashMap<String, HostTotals>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val path = request.url.encodedPath
        val startNs = System.nanoTime()
        val response =
            try {
                chain.proceed(request)
            } catch (e: IOException) {
                Log.d(TAG, "IMG $host $path failed ${elapsedMs(startNs)}ms ${e.javaClass.simpleName}")
                throw e
            }
        val cached = response.networkResponse == null && response.cacheResponse != null
        val counted =
            FlowRequestLog.CountingBody(response.body) { bytes ->
                val sum = record(host, response.code, if (cached) 0L else bytes)
                val source = if (cached) " http-cache" else ""
                Log.d(
                    TAG,
                    "IMG $host $path ${response.code} ${bytes}B ${elapsedMs(startNs)}ms$source | " +
                        "$host ${sum.requests} req ${sum.notFound}x404 ${sum.bytes / BYTES_PER_KB}KB",
                )
            }
        return response.newBuilder().body(counted).build()
    }

    private fun record(
        host: String,
        code: Int,
        bytes: Long,
    ): HostTotalsSnapshot {
        val entry = totals.getOrPut(host) { HostTotals() }
        return HostTotalsSnapshot(
            requests = entry.requests.incrementAndGet(),
            notFound = if (code == HTTP_NOT_FOUND) entry.notFound.incrementAndGet() else entry.notFound.get(),
            bytes = entry.bytes.addAndGet(bytes),
        )
    }

    private data class HostTotalsSnapshot(
        val requests: Long,
        val notFound: Long,
        val bytes: Long,
    )

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L
}
