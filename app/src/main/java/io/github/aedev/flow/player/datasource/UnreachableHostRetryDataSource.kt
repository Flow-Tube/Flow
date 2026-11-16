package io.github.aedev.flow.player.datasource

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/**
 * Opens a stream again, once, when its server could not be reached at all. [onUnreachable] gets the
 * request and the url that never answered, arranges for the second open to go elsewhere, and says
 * whether that is worth trying. A refusal, a missing host name or a dropped read is left to the
 * layers that handle those.
 */
@UnstableApi
internal class UnreachableHostRetryDataSource(
    private val upstream: DataSource,
    private val onUnreachable: (dataSpec: DataSpec, url: String) -> Boolean,
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long =
        try {
            upstream.open(dataSpec)
        } catch (e: IOException) {
            val url = unreachableUrl(e) ?: throw e
            upstream.close()
            if (!onUnreachable(dataSpec, url)) throw e
            upstream.open(dataSpec)
        }

    class Factory(
        private val upstream: DataSource.Factory,
        private val onUnreachable: (dataSpec: DataSpec, url: String) -> Boolean,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = UnreachableHostRetryDataSource(upstream.createDataSource(), onUnreachable)
    }

    companion object {
        /** The url whose server could not be connected to, when that is what [error] is. */
        fun unreachableUrl(error: Throwable): String? {
            val causes = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
            val http = causes.filterIsInstance<HttpDataSource.HttpDataSourceException>().firstOrNull() ?: return null
            if (http is HttpDataSource.InvalidResponseCodeException) return null
            val unreachable = causes.any { it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException }
            return http.dataSpec.uri
                .toString()
                .takeIf { unreachable }
        }

        private const val MAX_CAUSE_DEPTH = 8
    }
}
