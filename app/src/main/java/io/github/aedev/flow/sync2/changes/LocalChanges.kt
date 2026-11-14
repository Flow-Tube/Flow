package io.github.aedev.flow.sync2.changes

import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.sync.mapping.LikesMapper
import io.github.aedev.flow.sync.mapping.SubscribedChannelsMapper
import io.github.aedev.flow.sync.protocol.SyncCollection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Changes to the collections a push sends, as they are stored: the Room collections from [TableChanges],
 * likes and followed channels from their stores, compared as a push exports them so that details a
 * push leaves out, such as a channel's latest video, don't count. Watch history and settings are left out.
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
        val stored: Flow<String> =
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

        private fun <T> Flow<T>.changes(collection: String): Flow<String> = distinctUntilChanged().drop(1).map { collection }
    }
