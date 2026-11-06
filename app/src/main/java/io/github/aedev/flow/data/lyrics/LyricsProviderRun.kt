package io.github.aedev.flow.data.lyrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class LyricsKind { WORD, LINE, PLAIN }

internal data class LyricsAnswer(
    val kind: LyricsKind,
    val entries: List<LyricsEntry>,
    val source: String,
)

internal sealed interface LyricsReply {
    data class Found(
        val kind: LyricsKind,
        val entries: List<LyricsEntry>,
    ) : LyricsReply

    /** The source answered and has nothing for this song. */
    data object Miss : LyricsReply

    /** The source could not answer: a network failure, a timeout or a refused request. */
    data object Failed : LyricsReply
}

internal data class LyricsRunResult(
    val best: LyricsAnswer?,
    val everySourceMissed: Boolean,
    /** At least one source really replied, so an empty result isn't just being offline. */
    val someSourceReplied: Boolean,
)

/**
 * One song's search across its lyrics sources. [preferred] and the next source start together, one
 * more joins at 2 s or as soon as a running source comes back without synced lyrics, and at most
 * three run at once. Everything stops at 9 s.
 *
 * A word-synced answer is shown at once and ends the run. A line-synced one is held for 800 ms, and
 * up to 2.5 s while [preferred] hasn't replied, so a song isn't drawn twice when a better answer
 * lands right after. The run then keeps listening for word sync. Among line-synced answers
 * [preferred] beats the rest, and otherwise the order of [sources] decides. Once one is on screen
 * only [preferred] or word sync replaces it. Plain text shows only when the run ends with nothing
 * else.
 *
 * [alreadyShown] is what the screen holds from the saved copy when this run looks for an upgrade.
 * [show] returns false when the answer was refused, and then it does not count as shown.
 */
internal class LyricsProviderRun(
    private val sources: List<String>,
    private val preferred: String,
    private val alreadyShown: LyricsKind? = null,
    private val ask: suspend (String) -> LyricsReply,
    private val show: suspend (LyricsAnswer) -> Boolean,
) {
    private sealed interface Event {
        data class Replied(
            val source: String,
            val reply: LyricsReply,
        ) : Event

        data object OneMore : Event

        data object HoldOver : Event

        data object PreferredWaitOver : Event

        data object Stop : Event
    }

    private val waiting = ArrayDeque(sources.sortedBy { it != preferred })
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val lines = mutableListOf<LyricsAnswer>()
    private var plain: LyricsAnswer? = null
    private var shown: LyricsAnswer? = null
    private var shownKind = alreadyShown
    private var running = 0
    private var oneMoreDue = true
    private var holding = false
    private var holdDone = false
    private var preferredSettled = preferred !in sources
    private var stopped = false
    private var anyFailed = false
    private var someSourceReplied = false

    suspend fun run(): LyricsRunResult =
        coroutineScope {
            repeat(FIRST_WAVE) { startNext() }
            after(ONE_MORE_AT_MS, Event.OneMore)
            after(STOP_AT_MS, Event.Stop)
            while (!isOver()) handle(events.receive())
            showWhatIsLeft()
            coroutineContext.cancelChildren()
            LyricsRunResult(
                best = shown,
                everySourceMissed = sources.isNotEmpty() && waiting.isEmpty() && !stopped && !anyFailed && nothingFound(),
                someSourceReplied = someSourceReplied,
            )
        }

    private fun nothingFound(): Boolean = lines.isEmpty() && plain == null && shown == null

    private fun CoroutineScope.startNext() {
        if (running >= MAX_RUNNING) return
        val source = waiting.removeFirstOrNull() ?: return
        running++
        launch { events.send(Event.Replied(source, ask(source))) }
    }

    private fun CoroutineScope.after(
        delayMs: Long,
        event: Event,
    ) {
        launch {
            delay(delayMs)
            events.send(event)
        }
    }

    private fun isOver(): Boolean =
        stopped ||
            shownKind == LyricsKind.WORD ||
            (running == 0 && !holding && (waiting.isEmpty() || !oneMoreDue))

    private suspend fun CoroutineScope.handle(event: Event) {
        when (event) {
            is Event.Replied -> {
                running--
                if (event.source == preferred) preferredSettled = true
                handleReply(event.source, event.reply)
                endHoldIfDue()
            }

            Event.OneMore -> {
                oneMoreDue = false
                startNext()
            }

            Event.HoldOver -> {
                holdDone = true
                endHoldIfDue()
            }

            Event.PreferredWaitOver -> {
                preferredSettled = true
                endHoldIfDue()
            }

            Event.Stop -> {
                stopped = true
            }
        }
    }

    private suspend fun CoroutineScope.handleReply(
        source: String,
        reply: LyricsReply,
    ) {
        if (reply !is LyricsReply.Failed) someSourceReplied = true
        when (reply) {
            is LyricsReply.Found -> {
                val answer = LyricsAnswer(reply.kind, reply.entries, source)
                when (reply.kind) {
                    LyricsKind.WORD -> display(answer)
                    LyricsKind.LINE -> handleLine(answer)
                    LyricsKind.PLAIN -> keepPlain(answer)
                }
            }

            LyricsReply.Miss -> {
                startNext()
            }

            LyricsReply.Failed -> {
                anyFailed = true
                startNext()
            }
        }
    }

    private suspend fun CoroutineScope.handleLine(answer: LyricsAnswer) {
        lines += answer
        when {
            shownKind == LyricsKind.LINE -> {
                if (answer.source == preferred && shown?.source != preferred) display(answer)
            }

            !holding -> {
                holding = true
                after(HOLD_MS, Event.HoldOver)
                after(PREFERRED_WAIT_MS, Event.PreferredWaitOver)
            }
        }
    }

    private suspend fun endHoldIfDue() {
        if (!holding || !holdDone || !preferredSettled || shownKind == LyricsKind.WORD) return
        holding = false
        display(lines.minBy(::lineRank))
    }

    private fun CoroutineScope.keepPlain(answer: LyricsAnswer) {
        val kept = plain
        if (kept == null || sources.indexOf(answer.source) < sources.indexOf(kept.source)) plain = answer
        startNext()
    }

    private suspend fun showWhatIsLeft() {
        val left = plain
        when {
            shownKind == LyricsKind.WORD -> Unit
            holding -> display(lines.minBy(::lineRank))
            shownKind == null && left != null -> display(left)
        }
    }

    private suspend fun display(answer: LyricsAnswer) {
        if (!show(answer)) return
        shown = answer
        shownKind = answer.kind
    }

    private fun lineRank(answer: LyricsAnswer): Int = if (answer.source == preferred) -1 else sources.indexOf(answer.source)

    internal companion object {
        const val FIRST_WAVE = 2
        const val MAX_RUNNING = 3
        const val ONE_MORE_AT_MS = 2_000L
        const val HOLD_MS = 800L
        const val PREFERRED_WAIT_MS = 2_500L
        const val STOP_AT_MS = 9_000L
    }
}
