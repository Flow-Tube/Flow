package io.github.aedev.flow.sync.apply

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.AppDatabase
import io.github.aedev.flow.data.local.dao.SyncLogDao
import io.github.aedev.flow.data.local.dao.SyncPeerDao
import io.github.aedev.flow.data.local.entity.SyncLogEntity
import io.github.aedev.flow.data.local.entity.SyncPeerEntity
import io.github.aedev.flow.data.profile.ProfileSlices
import io.github.aedev.flow.di.ProfileDb
import io.github.aedev.flow.sync.identity.DeviceIdentity
import io.github.aedev.flow.sync.merge.LikesMerger
import io.github.aedev.flow.sync.merge.NotesMerger
import io.github.aedev.flow.sync.merge.PlaylistMerger
import io.github.aedev.flow.sync.merge.SettingsMerger
import io.github.aedev.flow.sync.merge.SubscribedChannelsMerger
import io.github.aedev.flow.sync.merge.SubscriptionsMerger
import io.github.aedev.flow.sync.merge.WatchHistoryMerger
import io.github.aedev.flow.sync.protocol.ApplyStats
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync.protocol.SyncSerialization
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ReceivedCollection(
    val lines: List<String>,
    val hash: String,
)

/** Identifies the peer for the ledger/known-devices list. */
data class PeerInfo(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
)

/**
 *  Sender side: [exportPayload]. Receiver side:
 * [applyPayload] — merges each collection with its local state (CRDT), applies Room collections in
 * a single `withTransaction`, applies DataStore/brain collections (individually atomic), and records
 * the `sync_log` idempotency ledger + known peer. A payload reaching beyond the Room transaction
 * takes a pre-merge backup first and restores it on any failure.
 */
@Singleton
class SyncApplier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ProfileDb private val db: AppDatabase,
        private val dataAccess: SyncDataAccess,
        private val tracker: SyncChangeTracker,
        private val brains: BrainSyncAccess,
        private val syncLogDao: SyncLogDao,
        private val syncPeerDao: SyncPeerDao,
        private val deviceIdentity: DeviceIdentity,
        private val slices: ProfileSlices,
    ) {
        // --- sender ---

        /** The language this device's topic keys are stemmed for, announced with the brain's capability. */
        suspend fun neuroKeyLanguage(): String = brains.keyLanguage()

        suspend fun exportPayload(
            collections: List<String>,
            node: String,
            hlc: String,
        ): Map<String, CollectionWire> {
            val out = LinkedHashMap<String, CollectionWire>()
            for (c in collections) {
                val wire =
                    when (c) {
                        SyncCollection.WATCH_HISTORY -> {
                            SyncSerialization.encodeWatchHistory(tracker.watchHistory(node))
                        }

                        SyncCollection.PLAYLISTS -> {
                            SyncSerialization.encodePlaylists(tracker.playlists(node))
                        }

                        SyncCollection.LIKES -> {
                            SyncSerialization.encodeLikes(tracker.likes(node))
                        }

                        SyncCollection.SETTINGS -> {
                            SyncSerialization.encodeSettings(tracker.settings(node))
                        }

                        SyncCollection.SUBSCRIBED_CHANNELS -> {
                            SyncSerialization.encodeSubscribedChannels(dataAccess.readSubscribedChannels(node))
                        }

                        SyncCollection.SUBSCRIPTIONS -> {
                            SyncSerialization.encodeSubscriptions(tracker.groups(node))
                        }

                        SyncCollection.NOTES -> {
                            SyncSerialization.encodeNotes(tracker.notes())
                        }

                        SyncCollection.FLOW_NEURO_BRAIN -> {
                            SyncSerialization.encodeBrain(brains.exportBrain(node, hlc))
                        }

                        SyncCollection.MUSIC_BRAIN -> {
                            SyncSerialization.encodeMusicBrain(brains.exportMusicBrain(node, hlc))
                        }

                        else -> {
                            null
                        }
                    }
                if (wire != null) out[c] = wire
            }
            return out
        }

        // --- receiver ---

        /**
         * [onceEach] keeps FLOW-SYNC/1's guard: a payload already applied from this peer is skipped and the
         * applied ones are recorded. FLOW-SYNC/2 turns it off; its stream positions already stop a re-apply,
         * and an identical payload in a new group must still apply.
         */
        suspend fun applyPayload(
            peer: PeerInfo,
            received: Map<String, ReceivedCollection>,
            hlc: String,
            onceEach: Boolean = true,
        ): Map<String, ApplyStats> {
            val node = deviceIdentity.hlcNode()
            val myDeviceId = deviceIdentity.deviceId()
            val stats = LinkedHashMap<String, ApplyStats>()

            // Idempotency: drop collections whose exact payload we've already applied from this peer.
            val fresh = HashMap<String, ReceivedCollection>()
            for ((collection, rc) in received) {
                if (onceEach && syncLogDao.isAlreadyApplied(peer.deviceId, collection, rc.hash)) {
                    stats[collection] = ApplyStats(skipped = rc.lines.size)
                } else {
                    fresh[collection] = rc
                }
            }
            if (fresh.isEmpty()) {
                recordPeer(peer)
                return stats
            }

            // The Room transaction alone undoes a failed merge of Room collections; a full-profile backup
            // there only cost seconds and tens of megabytes on every linked sync.
            val crossStore = fresh.keys.any { it !in ROOM_COLLECTIONS }
            if (crossStore) takeBackup()
            var failedCollection: String? = null
            // The ledgers learn what was written only once the whole merge has stuck.
            val settles = ArrayList<suspend () -> Unit>()
            try {
                // Room-backed collections: one transaction, all-or-nothing.
                db.withTransaction {
                    fresh[SyncCollection.WATCH_HISTORY]?.let { rc ->
                        failedCollection = SyncCollection.WATCH_HISTORY
                        val remote = SyncSerialization.decodeWatchHistory(rc.lines)
                        val local = tracker.watchHistory(node)
                        val merged = WatchHistoryMerger.merge(local, remote)
                        dataAccess.writeWatchHistory(merged)
                        settles += { tracker.settleWatchHistory(merged) }
                        stats[SyncCollection.WATCH_HISTORY] = statsFor(remote.map { it.videoId to it.deleted }, local.map { it.videoId })
                    }
                    fresh[SyncCollection.SUBSCRIPTIONS]?.let { rc ->
                        failedCollection = SyncCollection.SUBSCRIPTIONS
                        val remote = SyncSerialization.decodeSubscriptions(rc.lines)
                        val local = tracker.groups(node)
                        val merged = SubscriptionsMerger.merge(local, remote)
                        dataAccess.writeSubscriptions(merged)
                        settles += { tracker.settleGroups(merged) }
                        stats[SyncCollection.SUBSCRIPTIONS] = statsFor(remote.map { it.name to it.deleted }, local.map { it.name })
                    }
                    fresh[SyncCollection.NOTES]?.let { rc ->
                        failedCollection = SyncCollection.NOTES
                        val remote = SyncSerialization.decodeNotes(rc.lines)
                        val local = tracker.notes()
                        val merged = NotesMerger.merge(local, remote)
                        dataAccess.writeNotes(merged)
                        settles += { tracker.settleNotes(merged) }
                        stats[SyncCollection.NOTES] = statsFor(remote.map { it.id to it.deleted }, local.map { it.id })
                    }
                    fresh[SyncCollection.PLAYLISTS]?.let { rc ->
                        failedCollection = SyncCollection.PLAYLISTS
                        val remote = SyncSerialization.decodePlaylists(rc.lines)
                        val local = tracker.playlists(node)
                        val merged = PlaylistMerger.merge(local, remote)
                        dataAccess.writePlaylists(merged)
                        settles += { tracker.settlePlaylists(merged) }
                        stats[SyncCollection.PLAYLISTS] = statsFor(remote.map { it.syncId to it.deleted }, local.map { it.syncId })
                    }
                }

                // DataStore-backed collections: individually atomic.
                fresh[SyncCollection.LIKES]?.let { rc ->
                    failedCollection = SyncCollection.LIKES
                    val remote = SyncSerialization.decodeLikes(rc.lines)
                    val local = tracker.likes(node)
                    val merged = LikesMerger.merge(local, remote)
                    dataAccess.writeLikes(merged)
                    settles += { tracker.settleLikes(merged) }
                    stats[SyncCollection.LIKES] =
                        statsFor(remote.map { "${it.kind}|${it.id}" to (it.state == "none") }, local.map { "${it.kind}|${it.id}" })
                }
                // Subscribed channels live in DataStore, and unsubscribing also prunes that channel's
                // cached feed rows, so this cannot sit inside the Room transaction above.
                fresh[SyncCollection.SUBSCRIBED_CHANNELS]?.let { rc ->
                    failedCollection = SyncCollection.SUBSCRIBED_CHANNELS
                    val remote = SyncSerialization.decodeSubscribedChannels(rc.lines)
                    val local = dataAccess.readSubscribedChannels(node)
                    val merged = SubscribedChannelsMerger.merge(local, remote)
                    dataAccess.writeSubscribedChannels(merged)
                    stats[SyncCollection.SUBSCRIBED_CHANNELS] =
                        statsFor(
                            remote.map { it.channelId to it.deleted },
                            local.filter { !it.deleted }.map { it.channelId },
                        )
                }
                fresh[SyncCollection.SETTINGS]?.let { rc ->
                    failedCollection = SyncCollection.SETTINGS
                    val remote = SyncSerialization.decodeSettings(rc.lines)
                    val local = tracker.settings(node)
                    val merged = SettingsMerger.merge(local, remote)
                    dataAccess.writeSettings(merged)
                    settles += { tracker.settleSettings(merged) }
                    stats[SyncCollection.SETTINGS] = ApplyStats(updated = remote.size)
                }

                // Brain: stateful CRDT merge + engine reload.
                fresh[SyncCollection.FLOW_NEURO_BRAIN]?.let { rc ->
                    failedCollection = SyncCollection.FLOW_NEURO_BRAIN
                    val snapshots = SyncSerialization.decodeBrainSnapshots(rc.lines)
                    if (snapshots.isNotEmpty()) {
                        brains.mergeBrain(snapshots, peer.deviceId, node, myDeviceId, hlc)
                        stats[SyncCollection.FLOW_NEURO_BRAIN] = ApplyStats(updated = 1)
                    }
                }

                // Music brain: same stateful CRDT pattern, music twin.
                fresh[SyncCollection.MUSIC_BRAIN]?.let { rc ->
                    failedCollection = SyncCollection.MUSIC_BRAIN
                    SyncSerialization.decodeMusicBrain(rc.lines)?.let { remote ->
                        brains.mergeMusicBrain(remote, peer.deviceId, node, myDeviceId, hlc)
                        stats[SyncCollection.MUSIC_BRAIN] = ApplyStats(updated = 1)
                    }
                }
                failedCollection = null
                for (settle in settles) settle()

                // Ledger: record each applied payload + the peer.
                val now = System.currentTimeMillis()
                for ((collection, rc) in fresh.takeIf { onceEach }.orEmpty()) {
                    syncLogDao.insert(SyncLogEntity(peer.deviceId, collection, rc.hash, now, hlc))
                }
                recordPeer(peer)
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "merge failed at collection=$failedCollection; rolling back", e)
                if (crossStore) runCatching { restoreBackup() }
                val where = failedCollection?.let { " applying '$it'" } ?: ""
                val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                throw SyncApplyException("Merge failed$where and was rolled back. Cause: $reason", e)
            } finally {
                runCatching { snapshotFile().delete() }
            }
            return stats
        }

        private companion object {
            const val TAG = "SyncApplier"

            /** Collections [applyPayload] writes inside its one Room transaction. */
            val ROOM_COLLECTIONS =
                setOf(SyncCollection.WATCH_HISTORY, SyncCollection.SUBSCRIPTIONS, SyncCollection.NOTES, SyncCollection.PLAYLISTS)
        }

        private suspend fun recordPeer(peer: PeerInfo) {
            syncPeerDao.upsert(SyncPeerEntity(peer.deviceId, peer.deviceName, peer.platform, System.currentTimeMillis()))
        }

        /** added = new keys, updated = existing keys, tombstoned = deletes in the incoming set. */
        private fun statsFor(
            incoming: List<Pair<String, Boolean>>,
            localKeys: List<String?>,
        ): ApplyStats {
            val local = localKeys.filterNotNull().toHashSet()
            var added = 0
            var updated = 0
            var tombstoned = 0
            for ((key, deleted) in incoming) {
                when {
                    deleted -> tombstoned++
                    key in local -> updated++
                    else -> added++
                }
            }
            return ApplyStats(added = added, updated = updated, tombstoned = tombstoned)
        }

        // --- pre-merge backup (cross-store rollback net: a slice of Default, the only profile that syncs) ---

        private fun snapshotFile() = File(context.cacheDir, "sync_premerge_backup.zip")

        private suspend fun takeBackup() {
            val result = slices.export(snapshotFile())
            if (result.isFailure) {
                throw SyncApplyException("Could not take the pre-merge safety backup; aborting merge", result.exceptionOrNull())
            }
        }

        private suspend fun restoreBackup() {
            slices.import(Uri.fromFile(snapshotFile()))
        }
    }

class SyncApplyException(
    message: String,
    cause: Throwable?,
) : Exception(message, cause)
