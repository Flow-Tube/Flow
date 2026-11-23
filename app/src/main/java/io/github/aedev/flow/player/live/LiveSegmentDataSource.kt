package io.github.aedev.flow.player.live

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** The CDN refused a segment, most likely because its URL expired; Media3 retries once the URLs are fetched again. */
class LiveSegmentRefusedException(
    val sequence: Long,
    cause: IOException,
) : IOException("Live segment $sequence was refused", cause)

/** The CDN has no segment [sequence] yet; Media3 retries the load. */
class LiveSegmentNotReadyException(
    val sequence: Long,
) : IOException("Live segment $sequence is not available yet")

/**
 * Fetches a live session's segment URIs from googlevideo: `flowlive://segment/<video>/<itag>/<sq>`
 * becomes that itag's current URL with `&sq=<sq>`, and the head the response reports is handed back
 * to the session's clock.
 *
 * A segment past the head is held open for about one segment and then answered with 204 and no
 * body. Media3 would read that as an empty segment and move on, skipping it, so it is raised as
 * [LiveSegmentNotReadyException] instead.
 */
@UnstableApi
class LiveSegmentDataSource(
    private val upstream: HttpDataSource,
    private val parseUri: (String) -> Uri = Uri::parse,
) : DataSource {
    class Factory(
        private val upstream: HttpDataSource.Factory,
        private val transferListener: TransferListener? = null,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            LiveSegmentDataSource(upstream.createDataSource()).also { source -> transferListener?.let(source::addTransferListener) }
    }

    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val session = LiveSegmentSessions.forUri(dataSpec.uri) ?: throw IOException("No live session for ${dataSpec.uri}")
        val segments = dataSpec.uri.pathSegments
        val itag = segments.getOrNull(1)?.toIntOrNull()
        val sequence = segments.getOrNull(2)?.toLongOrNull()
        val base = itag?.let(session::urlFor)
        if (base == null || sequence == null) throw IOException("Unknown live segment ${dataSpec.uri}")
        val length =
            try {
                upstream.open(dataSpec.withUri(parseUri("$base&sq=$sequence")))
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                if (e.responseCode != HTTP_FORBIDDEN) throw e
                // Raised as its own error so Media3 retries the segment rather than excluding the
                // track, which it does for a plain 403.
                session.onRefused()
                throw LiveSegmentRefusedException(sequence, e)
            }
        session.observe(upstream.responseHeaders)
        if (upstream.responseCode == HTTP_NO_CONTENT || length == 0L) {
            upstream.close()
            throw LiveSegmentNotReadyException(sequence)
        }
        return length
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        uri = null
        upstream.close()
    }

    private companion object {
        const val HTTP_NO_CONTENT = 204
        const val HTTP_FORBIDDEN = 403
    }
}
