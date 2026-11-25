package io.github.aedev.flow.innertube

import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.random.Random

/**
 * When a failed InnerTube request is asked again. Only transport failures are retried: an HTTP
 * error status, 429 included, is an answer, and Ktor throws it as a ResponseException rather
 * than an IOException. A request that already ran into its call timeout is not repeated, nor is a
 * name lookup while no network is up, and nothing runs past [budgetMs] from the first attempt.
 * Each wait is the doubling backoff times a random factor in [1, 2), so requests that failed
 * together do not all come back at the same moment.
 */
internal class InnerTubeRetry(
    private val maxAttempts: Int = 3,
    private val initialDelayMs: Long = 500L,
    private val budgetMs: Long = 60_000L,
    private val hasActiveNetwork: () -> Boolean = { true },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val jitter: () -> Double = { Random.nextDouble(1.0, 2.0) },
) {
    suspend fun <T : Any> run(block: suspend () -> T): T {
        val startedAt = nowMs()
        var backoffMs = initialDelayMs
        var attempt = 1
        var previous: IOException? = null
        while (true) {
            val failure =
                try {
                    val earlier = previous ?: return block()
                    return withTimeoutOrNull(budgetMs - (nowMs() - startedAt)) { block() } ?: throw earlier
                } catch (e: IOException) {
                    e
                }
            val waitMs = (backoffMs * jitter()).toLong()
            if (attempt >= maxAttempts || !isRetryable(failure) || nowMs() - startedAt + waitMs >= budgetMs) throw failure
            delay(waitMs)
            attempt++
            backoffMs *= 2
            previous = failure
        }
    }

    private fun isRetryable(failure: IOException): Boolean =
        when (failure) {
            is HttpRequestTimeoutException -> false
            is SocketTimeoutException -> true
            is InterruptedIOException -> false
            is UnknownHostException -> hasActiveNetwork()
            else -> true
        }
}
