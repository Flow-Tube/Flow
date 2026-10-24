package io.github.aedev.flow.data.download

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Reads a streamed song as one stream although [DownloadUtil] fetches it in bounded chunks. An
 * open-ended read used to stop where its chunk stopped, so the extractor, cut off mid-block, threw
 * an EOFException and the loader reopened the song after a load error every 512 KB. When such a
 * read runs out, this opens the same chain again at the next byte, which is the reopen the loader's
 * retry made, and keeps reading. A song on the device is read as it is. Media3 has no data source
 * that carries a read on across ranged requests.
 */
@OptIn(UnstableApi::class)
internal class ChunkStitchingDataSource(
    private val upstream: DataSource,
) : DataSource {
    private var stitched: DataSpec? = null
    private var bytesRead = 0L
    private var bytesSinceOpen = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        val streamed =
            dataSpec.length == C.LENGTH_UNSET.toLong() &&
                dataSpec.uri.scheme == LocalCopyDataSource.MUSIC_SCHEME &&
                upstream.uri?.scheme !in LocalCopyDataSource.DEVICE_SCHEMES
        stitched = dataSpec.takeIf { streamed }
        bytesRead = 0
        bytesSinceOpen = 0
        return if (streamed) C.LENGTH_UNSET.toLong() else length
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        val read = upstream.read(buffer, offset, length)
        val dataSpec = stitched ?: return read
        if (read != C.RESULT_END_OF_INPUT) {
            bytesRead += read
            bytesSinceOpen += read
            return read
        }
        if (bytesSinceOpen == 0L || !openNextChunk(dataSpec)) return C.RESULT_END_OF_INPUT
        return read(buffer, offset, length)
    }

    /**
     * False ends the read where it stands. Past the song's last byte the next open fails (416, or
     * no network for a song the cache holds whole), and the extractor has already read to its end.
     * Mid-song, a failure leaves the extractor short, which raises the same load error a chunk end
     * raised before, so the loader's retry runs as it always did.
     */
    private fun openNextChunk(dataSpec: DataSpec): Boolean {
        upstream.close()
        bytesSinceOpen = 0
        return try {
            upstream.open(dataSpec.subrange(bytesRead)) != 0L
        } catch (e: IOException) {
            false
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        stitched = null
        upstream.close()
    }

    class Factory(
        private val upstream: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkStitchingDataSource(upstream.createDataSource())
    }
}
