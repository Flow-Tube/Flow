package io.github.aedev.flow.player.shorts

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import io.github.aedev.flow.player.cache.PlayerCacheManager
import io.github.aedev.flow.player.cache.SharedPlayerCacheProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The pool's reads through the shared media cache once it exists.
 *
 * Without it every loop of a REPEAT_MODE_ONE short and every swipe back re-downloaded the whole
 * file; the main video player has read through [SharedPlayerCacheProvider] all along, and the pool
 * was the one playback path that did not.
 */
@OptIn(UnstableApi::class)
internal class ShortsSharedCache {
    /**
     * Null until the cache has been opened, which cannot happen on the caller's thread: the pool
     * initializes on the main thread and opening the cache touches SQLite and the filesystem.
     */
    @Volatile
    var dataSourceFactory: DataSource.Factory? = null
        private set

    private var scope: CoroutineScope? = null

    /**
     * Points playback at the shared media cache, opening it off the main thread if some other
     * player has not already. Until that completes the pool keeps streaming uncached, so a cold
     * cache delays the benefit rather than the first frame.
     */
    fun attach(appContext: Context) {
        SharedPlayerCacheProvider.existing()?.let {
            dataSourceFactory = ShortsPlayerFactory.cachedDataSourceFactory(appContext, it)
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        scope.launch {
            runCatching {
                PlayerCacheManager.preload(appContext)
                SharedPlayerCacheProvider.existing()
            }.onSuccess { cache ->
                if (cache != null) {
                    dataSourceFactory = ShortsPlayerFactory.cachedDataSourceFactory(appContext, cache)
                    Log.d(TAG, "Shorts playback now reads through the shared media cache")
                }
            }.onFailure { Log.w(TAG, "Shared cache unavailable; Shorts will stream uncached", it) }
        }
    }

    fun release() {
        scope?.cancel()
        scope = null
        dataSourceFactory = null
    }

    private companion object {
        const val TAG = "ShortsPlayerPool"
    }
}
