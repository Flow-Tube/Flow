package io.github.aedev.flow.platform.memory

import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import dagger.Lazy
import io.github.aedev.flow.data.music.MusicCache
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.di.ApplicationScope
import io.github.aedev.flow.player.factory.PlayerBuffers
import io.github.aedev.flow.utils.FlowCrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * While Flow is on screen, checks the Java heap every 30 s and, when it is over 60 % full, adds a
 * crash breadcrumb naming what holds memory. An OutOfMemoryError report then shows what grew (#2204).
 */
@Singleton
class HeapSampler
    @Inject
    constructor(
        private val gauges: MemoryGauges,
        private val youTubeRepository: Lazy<YouTubeRepository>,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        fun install() {
            scope.launch(Dispatchers.Main.immediate) {
                ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        delay(HeapPressure.SAMPLE_INTERVAL_MS)
                        sample()
                    }
                }
            }
        }

        private fun sample() {
            val runtime = Runtime.getRuntime()
            val used = runtime.totalMemory() - runtime.freeMemory()
            val max = runtime.maxMemory()
            if (!HeapPressure.isHigh(used, max)) return
            FlowCrashHandler.recordPhase("heap", HeapPressure.describe(used, max, holdings()))
        }

        @OptIn(UnstableApi::class)
        private fun holdings(): Map<String, Int> {
            val music = MusicCache.getCacheStats()
            return gauges.snapshot() +
                mapOf(
                    "watch" to youTubeRepository.get().watchCacheEntries(),
                    "bufferMb" to (PlayerBuffers.heldBytes() / BYTES_PER_MB).toInt(),
                    "musicLists" to music.trendingCount + music.genreCount + music.searchCount + music.relatedCount,
                )
        }

        private companion object {
            const val BYTES_PER_MB = 1024 * 1024
        }
    }
