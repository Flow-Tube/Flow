package io.github.aedev.flow.ui.screens.player

import io.github.aedev.flow.data.model.LiveChatMessage
import io.github.aedev.flow.data.repository.LiveChatRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

/** What the replay needs from the player: whether this video is playing, and where it is. */
internal interface ChatReplayPlayback {
    fun isPlaying(videoId: String): Flow<Boolean>

    suspend fun positionMs(): Long
}

/**
 * Reveals a finished stream's chat as the video reaches it.
 *
 * Runs only inside [LiveChatController]'s panel-visible job, and suspends while the video is not
 * playing, so a paused video or a closed chat costs no request and no wake-up. Position is read
 * once a second, the player's own position cadence.
 */
internal class ChatReplayController(
    private val repository: LiveChatRepository,
    private val playback: ChatReplayPlayback,
    private val seed: suspend (videoId: String) -> String?,
) {
    suspend fun seedFor(videoId: String): String? = seed(videoId)

    suspend fun run(
        videoId: String,
        timeline: ChatReplayTimeline,
        onMessages: (List<LiveChatMessage>) -> Unit,
    ) {
        var shown: List<LiveChatMessage>? = null
        var failures = 0
        while (currentCoroutineContext().isActive) {
            playback.isPlaying(videoId).first { it }
            val positionMs = playback.positionMs()
            val fetch = timeline.fetchAt(positionMs)
            val token =
                when (fetch) {
                    is ChatReplayTimeline.Fetch.Next -> fetch.token
                    is ChatReplayTimeline.Fetch.Seek -> fetch.token
                    ChatReplayTimeline.Fetch.None -> null
                }
            if (token != null) {
                val page = repository.replayPage(token, positionMs)
                if (page == null) {
                    if (++failures >= MAX_FAILURES) break
                    delay(RETRY_MS)
                    continue
                }
                failures = 0
                timeline.onPage(fetch, page, positionMs)
            }
            val now = timeline.shownAt(positionMs)
            if (now != shown) {
                shown = now
                onMessages(now)
            }
            delay(TICK_MS)
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val RETRY_MS = 3_000L
        const val MAX_FAILURES = 6
    }
}
