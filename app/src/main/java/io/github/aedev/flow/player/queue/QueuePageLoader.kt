package io.github.aedev.flow.player.queue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Load the next batch once this many videos or fewer are left after the current one. */
internal const val QUEUE_PAGE_LOOKAHEAD = 10

/**
 * Loads a queue's later pages one at a time, a little before playback reaches the end of what has
 * loaded. [deliver] runs on [scope]'s dispatcher with the generation the request was made for, and a
 * null page when the read failed; the source is kept, so the next position change tries again.
 */
internal class QueuePageLoader(
    private val scope: CoroutineScope,
    private val deliver: (generation: Int, page: QueuePage?) -> Unit,
) {
    private var source: QueuePageSource? = null
    private var generation = -1
    private var job: Job? = null

    val hasMore: Boolean get() = source != null

    val spec: QueueSourceSpec? get() = source?.spec

    fun attach(
        source: QueuePageSource?,
        generation: Int,
    ) {
        job?.cancel()
        job = null
        this.source = source
        this.generation = generation
    }

    /** Asks for the next page when [remaining] videos or fewer follow the current one. */
    fun onPosition(remaining: Int) {
        if (remaining <= QUEUE_PAGE_LOOKAHEAD) load()
    }

    /** Starts loading the next page unless one is already on its way; false when there is none to load. */
    fun load(): Boolean {
        val current = source ?: return false
        if (job?.isActive == true) return true
        val forGeneration = generation
        job =
            scope.launch {
                val page =
                    try {
                        current.next()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                if (source === current) {
                    if (page != null && !page.hasMore) source = null
                    deliver(forGeneration, page)
                }
            }
        return true
    }

    fun clear() = attach(null, -1)
}
