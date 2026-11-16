package io.github.aedev.flow.data.video

import io.github.aedev.flow.data.local.entity.DownloadCollectionKind
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.toMusicTrack
import io.github.aedev.flow.data.video.downloader.collection.CollectionSpec
import io.github.aedev.flow.data.video.downloader.collection.DownloadedCollections
import io.github.aedev.flow.data.video.downloader.request.toDownloadRequest
import io.github.aedev.flow.data.video.downloader.work.DownloadController
import io.github.aedev.flow.data.video.downloader.work.EnqueueOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A "Download all" in progress: how many of [total] videos have been looked at so far. */
data class DownloadBatch(
    val collectionId: String,
    val total: Int,
    val processed: Int = 0,
    val queued: Int = 0,
    val skipped: Int = 0,
) {
    val isFinished: Boolean get() = processed >= total

    fun record(outcome: QueueOutcome): DownloadBatch =
        copy(
            processed = processed + 1,
            queued = queued + if (outcome == QueueOutcome.QUEUED) 1 else 0,
            skipped = skipped + if (outcome == QueueOutcome.ALREADY_PRESENT) 1 else 0,
        )
}

/** What happened to one video handed to [BackgroundDownloadQueuer.queue]. */
enum class QueueOutcome {
    QUEUED,
    ALREADY_PRESENT,
    UNAVAILABLE,
}

/**
 * Queues downloads with no dialog, at the default download quality and codec: "Download all" on a
 * playlist or album. Queueing only writes the request; streams are resolved when each download's
 * turn comes, so a long batch never runs on URLs that expired while it waited.
 */
@Singleton
class BackgroundDownloadQueuer
    @Inject
    constructor(
        private val controller: DownloadController,
        private val collections: DownloadedCollections,
    ) {
        // Outlives the screen that asked, so leaving a playlist doesn't drop the rest of its videos.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val _batches = MutableStateFlow<Map<String, DownloadBatch>>(emptyMap())
        val batches: StateFlow<Map<String, DownloadBatch>> = _batches.asStateFlow()

        /** Queues every video of a collection, skipping what is already downloaded or queued. */
        fun queueAll(
            collectionId: String,
            videos: List<Video>,
        ) = runBatch(collectionId, videos.distinctBy { it.id }) { queue(it) }

        /** [queueAll] for songs, which keep their album and artists in their tags. */
        fun queueSongs(
            collectionId: String,
            tracks: List<MusicTrack>,
        ) = runBatch(collectionId, tracks.distinctBy { it.videoId }) { queueSong(it) }

        /**
         * Downloads a playlist as one item saved in its own folder. [complete] says [videos] is the
         * whole list, so anything no longer in it can be marked as removed from the playlist. Only
         * the [picked] videos are downloaded; the rest stay out of the collection until picked.
         */
        fun queueCollection(
            spec: CollectionSpec,
            videos: List<Video>,
            complete: Boolean,
            picked: Set<String>,
        ) {
            val byId = videos.associateBy { it.id }
            val collectionId = collections.storedId(spec)
            runCollection(spec, videos.map { it.id }, complete, picked) { id, _ ->
                val video = byId.getValue(id)
                if (video.isMusic) {
                    controller.enqueue(video.toMusicTrack().toDownloadRequest(collectionId = collectionId)).toQueueOutcome()
                } else {
                    controller.enqueue(video.toDownloadRequest(collectionId = collectionId)).toQueueOutcome()
                }
            }
        }

        /** [queueCollection] for an album or music playlist; album tracks are numbered in their folder. */
        fun queueCollectionSongs(
            spec: CollectionSpec,
            tracks: List<MusicTrack>,
            complete: Boolean,
        ) {
            val byId = tracks.associateBy { it.videoId }
            val isAlbum = spec.kind == DownloadCollectionKind.MUSIC_ALBUM
            val collectionId = collections.storedId(spec)
            runCollection(spec, tracks.map { it.videoId }, complete) { id, position ->
                controller
                    .enqueue(
                        byId.getValue(id).toDownloadRequest(
                            collectionId = collectionId,
                            trackNumber = if (isAlbum) position + 1 else null,
                            trackTotal = if (isAlbum) byId.size else null,
                            albumArtist = spec.author.takeIf { isAlbum },
                        ),
                    ).toQueueOutcome()
            }
        }

        private fun runCollection(
            spec: CollectionSpec,
            ids: List<String>,
            complete: Boolean,
            picked: Set<String>? = null,
            work: suspend (id: String, position: Int) -> QueueOutcome,
        ) {
            if (_batches.value[spec.id]?.isFinished == false) return
            scope.launch {
                val positions = ids.distinct().withIndex().associate { (index, id) -> id to index }
                val toQueue = collections.prepare(spec, ids, complete, picked)
                runBatch(spec.id, toQueue, allowEmpty = true) { id -> work(id, positions.getValue(id)) }
            }
        }

        private fun <T> runBatch(
            collectionId: String,
            items: List<T>,
            allowEmpty: Boolean = false,
            work: suspend (T) -> QueueOutcome,
        ) {
            if (items.isEmpty() && !allowEmpty) return
            val started = DownloadBatch(collectionId, total = items.size)
            var accepted = false
            _batches.update { batches ->
                if (batches[collectionId]?.isFinished == false) {
                    batches
                } else {
                    accepted = true
                    batches + (collectionId to started)
                }
            }
            if (!accepted) return
            scope.launch {
                items.forEach { item ->
                    val outcome = runCatching { work(item) }.getOrDefault(QueueOutcome.UNAVAILABLE)
                    _batches.update { batches ->
                        val batch = batches[collectionId] ?: return@update batches
                        batches + (collectionId to batch.record(outcome))
                    }
                }
            }
        }

        /** Forgets a finished batch once its result has been shown. */
        fun clearBatch(collectionId: String) {
            _batches.update { batches -> if (batches[collectionId]?.isFinished == true) batches - collectionId else batches }
        }

        /** Queues [video]; a song goes in as music, so it lands in the music folder with its tags. */
        suspend fun queue(video: Video): QueueOutcome =
            if (video.isMusic) queueSong(video.toMusicTrack()) else controller.enqueue(video.toDownloadRequest()).toQueueOutcome()

        private suspend fun queueSong(track: MusicTrack): QueueOutcome = controller.enqueue(track.toDownloadRequest()).toQueueOutcome()

        private fun EnqueueOutcome.toQueueOutcome(): QueueOutcome =
            when (this) {
                EnqueueOutcome.QUEUED -> QueueOutcome.QUEUED
                EnqueueOutcome.ALREADY_DOWNLOADED, EnqueueOutcome.ALREADY_QUEUED -> QueueOutcome.ALREADY_PRESENT
            }
    }
