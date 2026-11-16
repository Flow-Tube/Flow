package io.github.aedev.flow.ui.screens.player

import io.github.aedev.flow.data.model.LiveChatMessage
import io.github.aedev.flow.data.repository.LiveChatRepository.ReplayMessage
import io.github.aedev.flow.data.repository.LiveChatRepository.ReplayPage

/**
 * The fetched part of a finished stream's chat and which of it the video has reached.
 *
 * One request returns the messages of the next 20 to 45 seconds of video, so the window is
 * extended a little before playback catches up with it. A jump back before the window, or forward
 * past it, asks again at the new position with the seek token and starts the window over.
 */
internal class ChatReplayTimeline(
    private val seed: String,
    private val maxShown: Int = MAX_SHOWN,
) {
    sealed interface Fetch {
        data object None : Fetch

        data class Next(
            val token: String,
        ) : Fetch

        data class Seek(
            val token: String,
        ) : Fetch
    }

    private val entries = ArrayList<ReplayMessage>()
    private val ids = HashSet<String>()
    private var windowStartMs = 0L
    private var coveredUntilMs = NOTHING_FETCHED
    private var nextToken: String? = null
    private var seekToken: String? = null

    fun fetchAt(positionMs: Long): Fetch {
        val seekWith = seekToken ?: seed
        val next = nextToken
        return when {
            coveredUntilMs == NOTHING_FETCHED -> Fetch.Seek(seed)
            positionMs < windowStartMs -> Fetch.Seek(seekWith)
            positionMs > coveredUntilMs + JUMP_AHEAD_MS -> Fetch.Seek(seekWith)
            next != null && positionMs >= coveredUntilMs - FETCH_AHEAD_MS -> Fetch.Next(next)
            else -> Fetch.None
        }
    }

    fun onPage(
        fetch: Fetch,
        page: ReplayPage,
        positionMs: Long,
    ) {
        if (fetch is Fetch.Seek) {
            entries.clear()
            ids.clear()
            windowStartMs = positionMs
            coveredUntilMs = NOTHING_FETCHED
        }
        page.messages.forEach { if (ids.add(it.message.id)) entries += it }
        entries.sortBy { it.offsetMs }
        val lastOffset = page.messages.maxOfOrNull { it.offsetMs }
        // A page with nothing in it is a quiet stretch; asking again every second would not end it.
        val reached = lastOffset ?: (positionMs + QUIET_MS)
        coveredUntilMs = maxOf(coveredUntilMs, reached, positionMs)
        nextToken = page.nextContinuation
        page.seekContinuation?.let { seekToken = it }
        trim(positionMs)
    }

    fun shownAt(positionMs: Long): List<LiveChatMessage> {
        val reached = entries.indexOfLast { it.offsetMs <= positionMs } + 1
        return entries.subList(maxOf(0, reached - maxShown), reached).map { it.message }
    }

    private fun trim(positionMs: Long) {
        val reached = entries.indexOfLast { it.offsetMs <= positionMs } + 1
        val drop = reached - maxShown
        if (drop <= 0) return
        repeat(drop) { ids.remove(entries[it].message.id) }
        entries.subList(0, drop).clear()
        windowStartMs = entries.first().offsetMs
    }

    private companion object {
        const val NOTHING_FETCHED = -1L
        const val MAX_SHOWN = 200
        const val FETCH_AHEAD_MS = 5_000L
        const val JUMP_AHEAD_MS = 30_000L
        const val QUIET_MS = 10_000L
    }
}
