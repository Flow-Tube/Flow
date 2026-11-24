package io.github.aedev.flow.data.video.downloader.transfer

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import io.github.aedev.flow.player.cache.SharedPlayerCacheProvider
import io.github.aedev.flow.player.cache.googleVideoStreamKey
import java.io.IOException

/** A byte range of a stream that may already be on the device. */
internal fun interface CachedBlocks {
    /**
     * Hands [from]..[to] of [url]'s stream to [sink] in order when every byte of it is cached, and
     * returns whether it did; nothing is handed over unless the whole range is there.
     */
    fun copy(
        url: String,
        from: Long,
        to: Long,
        sink: (ByteArray, Int) -> Unit,
    ): Boolean
}

/**
 * Reads blocks from the player's video cache, keyed by the stream itself (itag, lmt, clen, xtags)
 * rather than the signed URL, so a video watched before is not fetched again (YouTube's offline
 * transfer reads its streaming cache first the same way). The cache is never created from here.
 */
@OptIn(UnstableApi::class)
internal class PlayerCacheBlocks(
    private val cache: () -> Cache? = SharedPlayerCacheProvider::existing,
) : CachedBlocks {
    override fun copy(
        url: String,
        from: Long,
        to: Long,
        sink: (ByteArray, Int) -> Unit,
    ): Boolean {
        val cache = cache() ?: return false
        val key = googleVideoStreamKey(url) ?: return false
        val length = to - from + 1
        if (!cache.isCached(key, from, length)) return false
        val source = CacheDataSource(cache, null)
        return try {
            source.open(
                DataSpec
                    .Builder()
                    .setUri(url)
                    .setKey(key)
                    .setPosition(from)
                    .setLength(length)
                    .build(),
            )
            val buffer = ByteArray(BUFFER_SIZE)
            var copied = 0L
            while (copied < length) {
                val read = source.read(buffer, 0, minOf(buffer.size.toLong(), length - copied).toInt())
                if (read < 0) break
                sink(buffer, read)
                copied += read
            }
            copied == length
        } catch (e: IOException) {
            Log.w(TAG, "Cached block unreadable, fetching it instead", e)
            false
        } finally {
            runCatching { source.close() }
        }
    }

    private companion object {
        const val TAG = "PlayerCacheBlocks"
        const val BUFFER_SIZE = 256 * 1024
    }
}
