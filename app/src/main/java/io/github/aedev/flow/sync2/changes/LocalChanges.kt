package io.github.aedev.flow.sync2.changes

import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.sync.mapping.LikesMapper
import io.github.aedev.flow.sync.mapping.SubscribedChannelsMapper
import io.github.aedev.flow.sync.protocol.SyncCollection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import javax.inject.Inject
import javax.inject.Singleton

/** What a player has loaded, and whether it is meant to play. */
internal data class PlayerItem(
    val item: String?,
    val playing: Boolean,
)

/** Whether going from [before] to [after] settles [before]'s history entry: playback paused or stopped, or moved on. */
internal fun historySettled(
    before: PlayerItem,
    after: PlayerItem,
): Boolean = (before.playing && !after.playing) || (before.item != null && before.item != after.item)

/**
 * Changes to the collections a push sends, as they are stored: the Room collections from [TableChanges],
 * likes and followed channels from their stores, compared as a push exports them so that details a
 * push leaves out, such as a channel's latest video, don't count. Settings are left out. Watch history
 * is saved every few seconds while something plays, so it counts only once playback settles.
 */
@Singleton
class LocalChanges
    @Inject
    constructor(
        private val tables: TableChanges,
        private val likes: LikedVideosRepository,
        private val subscriptions: SubscriptionRepository,
    ) {
        /** Each collection as it changes, until cancelled; this includes what a sync applies. */
        fun stored(): Flow<String> =
            merge(
                tables.changes.transform { collections -> (collections - SyncCollection.WATCH_HISTORY).forEach { emit(it) } },
                likes
                    .getAllLikedVideos()
                    .map { liked -> liked.filterNot { LocalMediaIds.isLocal(it.videoId) }.map { LikesMapper.likedToCanonical(it, "") } }
                    .changes(SyncCollection.LIKES),
                subscriptions
                    .getAllSubscriptions()
                    .map { channels -> channels.map { SubscribedChannelsMapper.toCanonical(it, "") } }
                    .changes(SyncCollection.SUBSCRIBED_CHANNELS),
            )

        /** Each time a player pauses, stops or moves on to another video or song, until cancelled. */
        fun historySettles(): Flow<Unit> =
            merge(
                combine(GlobalPlayerState.currentVideo, GlobalPlayerState.playerState) { video, state ->
                    PlayerItem(video?.id, state.playWhenReady && !state.hasEnded)
                }.settles(),
                // The music player's state carries no intent to play; buffering is still playing.
                combine(EnhancedMusicPlayerManager.currentTrack, EnhancedMusicPlayerManager.playerState) { track, state ->
                    PlayerItem(track?.videoId, state.isPlaying || state.isBuffering)
                }.settles(),
            )

        private fun Flow<PlayerItem>.settles(): Flow<Unit> =
            flow {
                var before: PlayerItem? = null
                distinctUntilChanged().collect { after ->
                    if (before?.let { historySettled(it, after) } == true) emit(Unit)
                    before = after
                }
            }

        private fun <T> Flow<T>.changes(collection: String): Flow<String> = distinctUntilChanged().drop(1).map { collection }
    }
