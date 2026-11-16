package io.github.aedev.flow.ui.screens.music

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.music.artwork.AnimatedArtworkRepository
import io.github.aedev.flow.data.music.artwork.LoadedLoop
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.di.MusicCache
import io.github.aedev.flow.network.ProxyAwareClient
import io.github.aedev.flow.utils.NetworkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The music player's animated artwork: the loop of the playing song's album, looked up only when
 * the open player asks for it and only once per album. The loop's segments go through the music
 * cache, so replaying a loop does not download it again. A lookup that fails is tried again twice
 * while the song stays on screen, since the service often answers 503 well under its rate limit.
 */
@HiltViewModel
@androidx.annotation.OptIn(UnstableApi::class)
class AnimatedArtworkViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: AnimatedArtworkRepository,
        private val preferences: PlayerPreferences,
        @MusicCache musicCache: SimpleCache,
    ) : ViewModel() {
        val isEnabled: StateFlow<Boolean> =
            preferences.animatedArtwork.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        private val loaded = MutableStateFlow<LoadedLoop?>(null)

        /** The HLS loops of the song last [load]ed, or null while there are none to show. */
        internal val loop: StateFlow<LoadedLoop?> = loaded.asStateFlow()

        val dataSourceFactory: DataSource.Factory =
            CacheDataSource
                .Factory()
                .setCache(musicCache)
                .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(ProxyAwareClient().get()))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        private var loadedTrack: MusicTrack? = null
        private var lookup: Job? = null

        fun load(track: MusicTrack) {
            if (track == loadedTrack) return
            loadedTrack = track
            lookup?.cancel()
            loaded.value = null
            lookup =
                viewModelScope.launch {
                    for (waitMs in ATTEMPT_WAITS_MS) {
                        delay(waitMs)
                        if (!mayLookUp()) break
                        try {
                            loaded.value = repository.loopFor(track)?.let { LoadedLoop(track.videoId, it) }
                            return@launch
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // The next attempt, if any, asks again.
                        }
                    }
                    forgetLoaded()
                }
        }

        /** The song left the screen: an unfinished lookup stops, and starts over when it shows again. */
        fun stopLookup() {
            if (lookup?.isActive != true) return
            lookup?.cancel()
            forgetLoaded()
        }

        private suspend fun mayLookUp(): Boolean =
            preferences.animatedArtwork.first() &&
                (!preferences.animatedArtworkWifiOnly.first() || NetworkState.isOnWifi(context))

        /** A loop that would not play is dropped, so it is asked about again next time. */
        fun onLoopFailed() {
            val track = loadedTrack ?: return
            loaded.value = null
            viewModelScope.launch { repository.forget(track) }
        }

        // Nothing was decided for this song, so asking again later is allowed.
        private fun forgetLoaded() {
            loadedTrack = null
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L

            /** Tried at once, then 20 s and 60 s after the first failure. */
            val ATTEMPT_WAITS_MS = listOf(0L, 20_000L, 40_000L)
        }
    }
