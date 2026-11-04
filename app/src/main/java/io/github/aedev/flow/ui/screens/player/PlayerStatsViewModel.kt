package io.github.aedev.flow.ui.screens.player

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.stats.PlayerStatsReader
import io.github.aedev.flow.player.stats.PlayerStatsSnapshot
import javax.inject.Inject

/** Reads the player for the stats panel, which only exists while the panel is open. */
@OptIn(UnstableApi::class)
@HiltViewModel
class PlayerStatsViewModel
    @Inject
    constructor(
        private val playerManager: EnhancedPlayerManager,
    ) : ViewModel() {
        internal fun read(
            videoId: String,
            client: String?,
        ): PlayerStatsSnapshot? {
            val player = playerManager.getPlayer() ?: return null
            val kind =
                when {
                    LocalMediaIds.isLocal(videoId) -> "local"
                    player.isCurrentMediaItemLive -> "live"
                    else -> "vod"
                }
            return PlayerStatsReader.read(player, playerManager.statsReadings(), videoId, kind, client)
        }
    }
