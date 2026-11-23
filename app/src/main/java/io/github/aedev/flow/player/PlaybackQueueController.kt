package io.github.aedev.flow.player

import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * What adding a single video did, so the caller can run the follow-up it owns.
 */
internal enum class QueueAddOutcome {
    /** Added to the existing queue. */
    Inserted,

    /** Nothing was queued, so the currently playing video and the new one became the queue. */
    QueueCreated,

    /** Nothing was queued and nothing is playing, so the caller has to start a queue itself. */
    NoActiveQueue,
}

/**
 * A video taken out of the queue, with what it takes to put it back: its place in the queue as
 * shown and in the pre-shuffle order, and which queue it came from.
 */
data class RemovedQueueEntry(
    val video: Video,
    val index: Int,
    val originalIndex: Int,
    val generation: Int,
)

/**
 * Owns the video playback queue: its order, the current position, and the flows the UI observes.
 *
 * Ordering rules live in [PlaylistQueueOrder] and playback side effects (starting a video,
 * preloading the next one, clearing a preloaded window) stay with the caller, so this class never
 * touches a player. Mutations report back what changed and leave the caller to react.
 *
 * Main-thread confined: [EnhancedPlayerManager] posts every mutation to the main looper before it
 * reaches here, so the fields are deliberately unsynchronised, as the state they replaced was.
 */
internal class PlaybackQueueController {
    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndexState: StateFlow<Int> = _currentIndex.asStateFlow()

    /** Pre-shuffle order, kept so shuffle can be turned back off. */
    private var originalItems: List<Video> = emptyList()

    var title: String? = null
        private set

    var loopEnabled: Boolean = false
        private set

    var shuffleEnabled: Boolean = false
        private set

    /** The video picked when the queue was set; cleared once playback moves to another item. */
    private var pickedVideoId: String? = null

    /** Bumped whenever the queue is replaced, so an undo or a page from an older queue does nothing. */
    var generation = 0
        private set

    /** Later videos of this queue's playlist are still to be loaded. */
    var morePending: Boolean = false

    private val items: List<Video>
        get() = _videos.value

    val size: Int
        get() = items.size

    val isEmpty: Boolean
        get() = items.isEmpty()

    val currentIndex: Int
        get() = _currentIndex.value

    val currentVideo: Video?
        get() = items.getOrNull(currentIndex)

    val hasPrevious: Boolean
        get() = currentIndex > 0

    val hasNext: Boolean
        get() = nextIndex() != null || morePending

    /** How many videos follow the current one in what has loaded. */
    val remainingAfterCurrent: Int
        get() = (items.size - 1 - currentIndex).coerceAtLeast(0)

    fun videoAt(index: Int): Video? = items.getOrNull(index)

    fun isCurrent(videoId: String): Boolean = currentVideo?.id == videoId

    /** Whether [videoId] is current because the queue moved to it, rather than because it was picked. */
    fun isReachedByAdvance(videoId: String): Boolean = isCurrent(videoId) && videoId != pickedVideoId

    fun nextIndex(): Int? =
        PlaylistQueueOrder.nextIndex(
            itemCount = items.size,
            currentIndex = currentIndex,
            loopEnabled = loopEnabled,
            morePending = morePending,
        )

    fun nextVideo(): Video? = nextIndex()?.let(items::getOrNull)

    /**
     * Replaces the queue, honouring the current shuffle setting unless [shuffle] sets it.
     *
     * @return the video playback should start from, or null when [videos] is empty.
     */
    fun setQueue(
        videos: List<Video>,
        startIndex: Int,
        title: String?,
        shuffle: Boolean? = null,
    ): Video? {
        shuffle?.let { shuffleEnabled = it }
        morePending = false
        originalItems = videos
        val normalizedStartIndex = startIndex.coerceIn(0, videos.lastIndex.coerceAtLeast(0))
        val ordered =
            if (shuffleEnabled && videos.size > 1) {
                PlaylistQueueOrder.shuffleFromCurrent(videos, normalizedStartIndex)
            } else {
                ReorderedQueue(videos, normalizedStartIndex)
            }
        this.title = title
        generation++
        publish(ordered.items, if (videos.isEmpty()) -1 else ordered.currentIndex)
        pickedVideoId = currentVideo?.id
        return currentVideo
    }

    /**
     * Moves the current position to [index].
     *
     * @return the video now current, or null when [index] is out of range.
     */
    fun moveTo(index: Int): Video? {
        val video = items.getOrNull(index) ?: return null
        if (index != currentIndex) pickedVideoId = null
        _currentIndex.value = index
        return video
    }

    fun movePrevious(): Video? = if (hasPrevious) moveTo(currentIndex - 1) else null

    /** Inserts [video] right after the current position (Play Next). */
    fun addNext(
        video: Video,
        currentlyPlaying: Video?,
    ): QueueAddOutcome {
        if (isEmpty) return startQueueFrom(currentlyPlaying, video)

        val current = currentVideo
        val originalInsertAt =
            originalItems
                .indexOfFirst { it.id == current?.id }
                .takeIf { it >= 0 }
                ?.plus(1)
                ?: originalItems.size
        originalItems =
            originalItems.toMutableList().apply {
                add(originalInsertAt.coerceIn(0, size), video)
            }
        _videos.value = items.toMutableList().apply { add(currentIndex + 1, video) }
        return QueueAddOutcome.Inserted
    }

    /**
     * Appends the next page of the queue's playlist, leaving out videos already in it. While
     * shuffled the page is mixed into what is still to play.
     *
     * @return how many videos joined, 0 when [pageGeneration] belongs to a queue since replaced.
     */
    fun appendPage(
        pageGeneration: Int,
        videos: List<Video>,
        random: Random = Random.Default,
    ): Int {
        if (pageGeneration != generation || isEmpty) return 0
        val known = originalItems.mapTo(HashSet()) { it.id }
        items.mapTo(known) { it.id }
        val fresh = videos.filter { known.add(it.id) }
        if (fresh.isEmpty()) return 0
        originalItems = originalItems + fresh
        _videos.value = if (shuffleEnabled) PlaylistQueueOrder.mixIn(items, currentIndex, fresh, random) else items + fresh
        return fresh.size
    }

    /** Appends [video] to the end of the queue. */
    fun append(
        video: Video,
        currentlyPlaying: Video?,
    ): QueueAddOutcome {
        if (isEmpty) return startQueueFrom(currentlyPlaying, video)

        originalItems = originalItems + video
        _videos.value = items + video
        return QueueAddOutcome.Inserted
    }

    /** @return what was removed, or null when [index] is out of range or the current video. */
    fun removeAt(index: Int): RemovedQueueEntry? {
        val removal = PlaylistQueueOrder.removeAt(items, currentIndex, index) ?: return null
        val originalIndex = PlaylistQueueOrder.indexMatching(originalItems, removal.removedItem, Video::id)
        if (originalIndex >= 0) {
            originalItems = originalItems.toMutableList().apply { removeAt(originalIndex) }
        }
        publish(removal.queue.items, removal.queue.currentIndex)
        return RemovedQueueEntry(
            video = removal.removedItem,
            index = index,
            originalIndex = originalIndex.coerceAtLeast(0),
            generation = generation,
        )
    }

    /**
     * Puts a removed video back where it was, shifting the current position with it.
     *
     * @return false when the queue has been replaced since the removal.
     */
    fun restore(entry: RemovedQueueEntry): Boolean {
        if (entry.generation != generation) return false

        val at = entry.index.coerceIn(0, items.size)
        val restored = items.toMutableList().apply { add(at, entry.video) }
        val restoredCurrentIndex = if (currentIndex >= at) currentIndex + 1 else currentIndex
        originalItems =
            originalItems.toMutableList().apply {
                add(entry.originalIndex.coerceIn(0, size), entry.video)
            }
        publish(restored, restoredCurrentIndex)
        return true
    }

    /** @return whether the move was applied. */
    fun move(
        fromIndex: Int,
        toIndex: Int,
    ): Boolean {
        val reordered =
            PlaylistQueueOrder.move(
                items = items,
                currentIndex = currentIndex,
                fromIndex = fromIndex,
                toIndex = toIndex,
            ) ?: return false

        // While shuffled, the pre-shuffle order has to survive a manual reorder untouched.
        if (!shuffleEnabled) originalItems = reordered.items
        publish(reordered.items, reordered.currentIndex)
        return true
    }

    fun setLoopEnabled(enabled: Boolean) {
        loopEnabled = enabled
    }

    /** @return whether this changed anything, i.e. there was a non-empty queue to reorder. */
    fun setShuffleEnabled(enabled: Boolean): Boolean {
        if (shuffleEnabled == enabled || isEmpty) return false

        val reordered =
            if (enabled) {
                originalItems = items
                PlaylistQueueOrder.shuffleFromCurrent(items, currentIndex)
            } else {
                PlaylistQueueOrder.restoreOriginal(
                    original = originalItems,
                    currentItem = currentVideo,
                    keySelector = Video::id,
                )
            }
        shuffleEnabled = enabled
        publish(reordered.items, reordered.currentIndex)
        return true
    }

    fun clear() {
        generation++
        originalItems = emptyList()
        title = null
        loopEnabled = false
        shuffleEnabled = false
        morePending = false
        pickedVideoId = null
        publish(emptyList(), currentIndex = -1)
    }

    private fun startQueueFrom(
        currentlyPlaying: Video?,
        video: Video,
    ): QueueAddOutcome {
        if (currentlyPlaying == null) return QueueAddOutcome.NoActiveQueue
        generation++
        morePending = false
        originalItems = listOf(currentlyPlaying, video)
        publish(originalItems, currentIndex = 0)
        return QueueAddOutcome.QueueCreated
    }

    private fun publish(
        items: List<Video>,
        currentIndex: Int,
    ) {
        _videos.value = items
        _currentIndex.value = currentIndex
    }
}
