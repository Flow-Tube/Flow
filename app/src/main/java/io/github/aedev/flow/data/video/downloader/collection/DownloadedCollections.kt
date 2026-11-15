package io.github.aedev.flow.data.video.downloader.collection

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.dao.DownloadCollectionDao
import io.github.aedev.flow.data.local.dao.DownloadCollectionSummary
import io.github.aedev.flow.data.local.dao.DownloadDao
import io.github.aedev.flow.data.local.entity.CollectionItemState
import io.github.aedev.flow.data.local.entity.DownloadCollectionEntity
import io.github.aedev.flow.data.local.entity.DownloadCollectionKind
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.DownloadManager
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.profile.ActiveProfile
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.downloader.collection.CollectionOwners.asPage
import io.github.aedev.flow.data.video.storage.DownloadFiles
import io.github.aedev.flow.data.video.storage.DownloadNaming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playlists and albums downloaded as one item: their membership, the folder they are saved in,
 * and what of them is on the device, for the Downloads shelf and for a page opened offline.
 */
@Singleton
class DownloadedCollections
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val collectionDao: DownloadCollectionDao,
        private val downloadDao: DownloadDao,
        private val downloads: VideoDownloadManager,
        private val musicDownloads: DownloadManager,
    ) {
        private val profile get() = ActiveProfile.get(context)

        /** The running profile's collections and the shared ones, each under the playlist id its page opens with. */
        val summaries: Flow<List<DownloadCollectionSummary>> =
            collectionDao.observeSummaries().map { list ->
                val visible = list.filter { CollectionOwners.isVisible(it.collection, profile) }
                // A playlist of this profile's own hides a shared collection that happens to have its id.
                val own =
                    visible
                        .map { it.collection.id }
                        .filter { it != CollectionOwners.playlistId(it) }
                        .mapTo(HashSet(), CollectionOwners::playlistId)
                visible.filterNot { it.collection.id in own }.map { it.copy(collection = it.collection.asPage()) }
            }

        fun observe(id: String): Flow<DownloadCollectionSummary?> = summaries.map { list -> list.firstOrNull { it.collection.id == id } }

        /** The id [spec]'s collection is stored under, which its download requests carry. */
        fun storedId(spec: CollectionSpec): String = CollectionOwners.storedId(spec.id, spec.ownPlaylist, profile)

        /**
         * Records [spec] with [ids] as its current list and returns the videos to queue. The folder
         * name is fixed the first time, so a renamed playlist keeps writing into the same folder.
         * [picked] narrows the download to those videos; see [CollectionPlan.of].
         */
        suspend fun prepare(
            spec: CollectionSpec,
            ids: List<String>,
            complete: Boolean,
            picked: Set<String>? = null,
        ): List<String> {
            val storedId = storedId(spec)
            val existing = collectionDao.getCollection(storedId)
            val folderName =
                existing?.folderName ?: run {
                    val taken = collectionDao.folderNames().toSet()
                    val name = DownloadNaming.folderName(spec.title, spec.author, spec.kind == DownloadCollectionKind.MUSIC_ALBUM, spec.id)
                    DownloadNaming.unique(name, hasExtension = false) { it in taken }
                }
            val collection =
                (existing ?: DownloadCollectionEntity(id = storedId, kind = spec.kind, title = spec.title, folderName = folderName)).copy(
                    title = spec.title.ifBlank { existing?.title.orEmpty() },
                    author = spec.author ?: existing?.author.orEmpty(),
                    authorId = spec.authorId ?: existing?.authorId,
                    thumbnailUrl = spec.thumbnailUrl ?: existing?.thumbnailUrl.orEmpty(),
                    remoteItemCount = ids.size,
                    lastSyncedAt = System.currentTimeMillis(),
                )
            val downloaded = ids.filterTo(HashSet()) { isDownloadedOrQueued(it) }
            val plan =
                CollectionPlan.of(
                    collectionId = storedId,
                    ids = ids,
                    existing = collectionDao.itemsOf(storedId),
                    isDownloaded = { it in downloaded },
                    complete = complete,
                    now = System.currentTimeMillis(),
                    picked = picked,
                )
            collectionDao.upsertWithItems(collection, plan.members)
            return plan.toQueue
        }

        /**
         * What of the collection is on the device, in list order, for a page opened with no
         * network; null when [id] was never downloaded as a collection.
         */
        suspend fun offline(id: String): OfflineCollection? {
            val stored = find(id) ?: return null
            val wanted = wantedIds(stored.id)
            val collection = stored.asPage()
            return if (collection.kind.isMusic) {
                val byId = musicDownloads.downloadedTracks.first().associateBy { it.track.videoId }
                OfflineCollection(collection, tracks = wanted.mapNotNull { byId[it]?.track }, videos = emptyList())
            } else {
                OfflineCollection(collection, tracks = emptyList(), videos = wanted.mapNotNull { downloads.findLocalCopy(it)?.video })
            }
        }

        suspend fun collection(id: String): DownloadCollectionEntity? = find(id)?.asPage()

        /** The videos left out of the collection of playlist [id] the last time it was downloaded. */
        suspend fun excludedIds(id: String): Set<String> {
            val stored = find(id) ?: return emptySet()
            return collectionDao.itemsOf(stored.id).filter { it.state == CollectionItemState.EXCLUDED }.mapTo(HashSet()) { it.videoId }
        }

        /**
         * Removes the collection. With [deleteFiles], a download goes only when this collection
         * created it and no other collection still holds it; the folder goes once it is empty.
         */
        suspend fun remove(
            id: String,
            deleteFiles: Boolean,
        ) {
            val collection = find(id) ?: return
            val members = collectionDao.itemsOf(collection.id)
            collectionDao.deleteCollection(collection.id)
            if (!deleteFiles) return
            members
                .filter { it.ownsDownload && collectionDao.membershipsOf(it.videoId).isEmpty() }
                .forEach { downloads.deleteDownload(it.videoId) }
            collection.folderLocation?.let { DownloadFiles.deleteIfEmpty(context, it) }
        }

        /** Forgets a deleted profile's own playlists. Their downloads stay, like every other download. */
        suspend fun forgetProfile(profileId: String) = collectionDao.deleteCollectionsStartingWith(CollectionOwners.prefixOf(profileId))

        /** The stored collection the page of playlist [id] shows here. */
        private suspend fun find(id: String): DownloadCollectionEntity? {
            val profile = profile
            return CollectionOwners
                .candidates(id, profile)
                .firstNotNullOfOrNull { collectionDao.getCollection(it)?.takeIf { found -> CollectionOwners.isVisible(found, profile) } }
        }

        private suspend fun wantedIds(id: String): List<String> =
            collectionDao.itemsOf(id).filter { it.state != CollectionItemState.EXCLUDED }.map { it.videoId }

        private suspend fun isDownloadedOrQueued(videoId: String): Boolean =
            when (downloadDao.getDownloadWithItems(videoId)?.overallStatus) {
                DownloadItemStatus.COMPLETED, DownloadItemStatus.PENDING, DownloadItemStatus.DOWNLOADING, DownloadItemStatus.PAUSED -> true
                else -> false
            }
    }

/** A downloaded collection as it can be shown offline: songs for music, videos otherwise. */
data class OfflineCollection(
    val collection: DownloadCollectionEntity,
    val tracks: List<MusicTrack>,
    val videos: List<Video>,
)
