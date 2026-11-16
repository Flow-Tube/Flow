package io.github.aedev.flow.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.aedev.flow.data.local.BufferProfile
import io.github.aedev.flow.utils.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Loads the start of the next song while the current one plays, so a skip has audio to start from
 * (#2076). Media3 does it only while the current song isn't loading, through the same sources and
 * cache. Off while Android's Data Saver or the Data saver buffer profile is on, checked on each new song;
 * [onSavingData] hears the same answer, for the load control's own window.
 */
@OptIn(UnstableApi::class)
internal class MusicPreload(
    private val context: Context,
    private val player: ExoPlayer,
    private val onSavingData: (Boolean) -> Unit = {},
) {
    private var profileSavesData = false

    private val listener =
        object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) = refresh()
        }

    fun attach(
        scope: CoroutineScope,
        bufferProfile: Flow<BufferProfile>,
    ) {
        player.addListener(listener)
        scope.launch {
            bufferProfile.collect {
                profileSavesData = it == BufferProfile.DATASAVER
                refresh()
            }
        }
    }

    private fun refresh() {
        val savesData = profileSavesData || NetworkState.isDataSaverOn(context)
        onSavingData(savesData)
        val wanted =
            if (savesData) {
                ExoPlayer.PreloadConfiguration.DEFAULT
            } else {
                ExoPlayer.PreloadConfiguration(NEXT_SONG_US)
            }
        if (player.preloadConfiguration.targetPreloadDurationUs != wanted.targetPreloadDurationUs) {
            player.preloadConfiguration = wanted
        }
    }

    private companion object {
        const val NEXT_SONG_US = 10_000_000L
    }
}
