package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LyricsProviderRunTest {
    private val word = LyricsReply.Found(LyricsKind.WORD, listOf(LyricsEntry(1L, "word")))
    private val line = LyricsReply.Found(LyricsKind.LINE, listOf(LyricsEntry(1L, "line")))
    private val plain = LyricsReply.Found(LyricsKind.PLAIN, listOf(LyricsEntry(0L, "plain")))

    private class Reply(
        val afterMs: Long,
        val reply: LyricsReply,
    )

    private fun after(
        ms: Long,
        reply: LyricsReply,
    ) = Reply(ms, reply)

    private inner class Harness(
        private val scope: TestScope,
        sources: List<String>,
        private val replies: Map<String, Reply>,
        alreadyShown: LyricsKind? = null,
        private val refused: Set<String> = emptySet(),
    ) {
        val started = mutableListOf<Pair<String, Long>>()
        val shown = mutableListOf<Pair<String, Long>>()
        val cancelled = mutableListOf<String>()
        var mostRunning = 0
        private var running = 0

        val run =
            LyricsProviderRun(
                sources = sources,
                preferred = "YouTube",
                alreadyShown = alreadyShown,
                ask = ::ask,
                show = { answer ->
                    val accepted = answer.source !in refused
                    if (accepted) shown += answer.source to scope.currentTime
                    accepted
                },
            )

        private suspend fun ask(source: String): LyricsReply {
            started += source to scope.currentTime
            running++
            mostRunning = maxOf(mostRunning, running)
            try {
                val reply = replies[source] ?: Reply(LONG_MS, LyricsReply.Failed)
                delay(reply.afterMs)
                return reply.reply
            } catch (e: CancellationException) {
                cancelled += source
                throw e
            } finally {
                running--
            }
        }
    }

    private val all = listOf("BetterLyrics", "LrcRed", "LyricsPlus", "KuGou", "LrcLib", "YouTube")

    @Test
    fun `youtube music and the first other source start together and one more at two seconds`() =
        runTest {
            val harness = Harness(this, all, emptyMap())

            harness.run.run()

            assertThat(harness.started.take(3))
                .containsExactly("YouTube" to 0L, "BetterLyrics" to 0L, "LrcRed" to 2_000L)
                .inOrder()
            assertThat(harness.started).hasSize(3)
        }

    @Test
    fun `without youtube music the first two enabled sources start`() =
        runTest {
            val harness = Harness(this, all - "YouTube", emptyMap())

            harness.run.run()

            assertThat(harness.started.take(2)).containsExactly("BetterLyrics" to 0L, "LrcRed" to 0L).inOrder()
        }

    @Test
    fun `a miss starts the next source at once`() =
        runTest {
            val harness = Harness(this, all, mapOf("BetterLyrics" to after(300, LyricsReply.Miss)))

            harness.run.run()

            assertThat(harness.started).contains("LrcRed" to 300L)
        }

    @Test
    fun `a failure and plain text also start the next source`() =
        runTest {
            val harness =
                Harness(
                    this,
                    all,
                    mapOf("YouTube" to after(100, plain), "BetterLyrics" to after(200, LyricsReply.Failed)),
                )

            harness.run.run()

            assertThat(harness.started).containsAtLeast("LrcRed" to 100L, "LyricsPlus" to 200L)
        }

    @Test
    fun `never more than three run at once`() =
        runTest {
            val replies = (all - "LrcLib").associateWith { after(1_500, LyricsReply.Miss) }
            val harness = Harness(this, all, replies)

            harness.run.run()

            assertThat(harness.mostRunning).isEqualTo(3)
            assertThat(harness.started.map { it.first }).containsExactlyElementsIn(all)
        }

    @Test
    fun `a word synced answer shows at once and ends the run`() =
        runTest {
            val harness = Harness(this, all, mapOf("BetterLyrics" to after(700, word)))

            val result = harness.run.run()

            assertThat(harness.shown).containsExactly("BetterLyrics" to 700L)
            assertThat(result.best?.source).isEqualTo("BetterLyrics")
            assertThat(currentTime).isEqualTo(700L)
            assertThat(harness.cancelled).containsExactly("YouTube")
            assertThat(harness.started).hasSize(2)
        }

    @Test
    fun `a line synced answer is held and then shown`() =
        runTest {
            val harness = Harness(this, all, mapOf("YouTube" to after(1_000, line)))

            harness.run.run()

            assertThat(harness.shown).containsExactly("YouTube" to 1_800L)
        }

    @Test
    fun `word sync inside the hold means the line synced answer is never drawn`() =
        runTest {
            val harness = Harness(this, all, mapOf("YouTube" to after(1_000, line), "BetterLyrics" to after(1_500, word)))

            val result = harness.run.run()

            assertThat(harness.shown).containsExactly("BetterLyrics" to 1_500L)
            assertThat(result.best?.kind).isEqualTo(LyricsKind.WORD)
        }

    @Test
    fun `word sync after the hold swaps and ends the run`() =
        runTest {
            val harness =
                Harness(
                    this,
                    all,
                    mapOf(
                        "YouTube" to after(500, line),
                        "BetterLyrics" to after(LONG_MS, LyricsReply.Failed),
                        "LrcRed" to after(3_000, word),
                    ),
                )

            val result = harness.run.run()

            assertThat(harness.shown).containsExactly("YouTube" to 1_300L, "LrcRed" to 5_000L).inOrder()
            assertThat(result.best?.source).isEqualTo("LrcRed")
        }

    @Test
    fun `youtube music beats a line synced answer from earlier in the order`() =
        runTest {
            val harness = Harness(this, all, mapOf("BetterLyrics" to after(500, line), "YouTube" to after(1_000, line)))

            harness.run.run()

            assertThat(harness.shown).containsExactly("YouTube" to 1_300L)
        }

    @Test
    fun `a line synced answer waits for youtube music so the song is not drawn twice`() =
        runTest {
            val harness = Harness(this, all, mapOf("BetterLyrics" to after(400, line), "YouTube" to after(1_600, line)))

            harness.run.run()

            assertThat(harness.shown).containsExactly("YouTube" to 1_600L)
        }

    @Test
    fun `a miss from youtube music ends that wait`() =
        runTest {
            val harness = Harness(this, all, mapOf("BetterLyrics" to after(400, line), "YouTube" to after(600, LyricsReply.Miss)))

            harness.run.run()

            assertThat(harness.shown).containsExactly("BetterLyrics" to 1_200L)
        }

    @Test
    fun `youtube music arriving after the hold still replaces another line synced answer`() =
        runTest {
            val harness = Harness(this, all, mapOf("BetterLyrics" to after(500, line), "YouTube" to after(4_000, line)))

            val result = harness.run.run()

            assertThat(harness.shown).containsExactly("BetterLyrics" to 3_000L, "YouTube" to 4_000L).inOrder()
            assertThat(result.best?.source).isEqualTo("YouTube")
        }

    @Test
    fun `otherwise the users order decides among line synced answers`() =
        runTest {
            val order = listOf("LrcLib", "KuGou", "LrcRed")
            val harness = Harness(this, order, mapOf("KuGou" to after(200, line), "LrcLib" to after(600, line)))

            harness.run.run()

            assertThat(harness.shown).containsExactly("LrcLib" to 1_000L)
        }

    @Test
    fun `a later line synced answer does not redraw the song`() =
        runTest {
            val replies =
                mapOf(
                    "YouTube" to after(100, LyricsReply.Miss),
                    "LrcRed" to after(1_000, line),
                    "BetterLyrics" to after(500, line),
                )
            val harness = Harness(this, all, replies)

            harness.run.run()

            assertThat(harness.shown).containsExactly("BetterLyrics" to 1_300L)
        }

    @Test
    fun `plain text only shows when the run ends with nothing else`() =
        runTest {
            val harness =
                Harness(
                    this,
                    listOf("YouTube", "LrcLib", "KuGou"),
                    mapOf(
                        "YouTube" to after(100, plain),
                        "LrcLib" to after(200, LyricsReply.Miss),
                        "KuGou" to after(200, LyricsReply.Miss),
                    ),
                )

            val result = harness.run.run()

            assertThat(harness.shown).containsExactly("YouTube" to 300L)
            assertThat(result.best?.kind).isEqualTo(LyricsKind.PLAIN)
        }

    @Test
    fun `plain text loses to a line synced answer`() =
        runTest {
            val harness = Harness(this, all, mapOf("YouTube" to after(100, plain), "BetterLyrics" to after(500, line)))

            val result = harness.run.run()

            assertThat(result.best?.source).isEqualTo("BetterLyrics")
            assertThat(harness.shown.map { it.first }).containsExactly("BetterLyrics")
        }

    @Test
    fun `everything stops at nine seconds`() =
        runTest {
            val harness = Harness(this, all, emptyMap())

            val result = harness.run.run()

            assertThat(currentTime).isEqualTo(9_000L)
            assertThat(harness.cancelled).hasSize(3)
            assertThat(result.best).isNull()
            assertThat(result.everySourceMissed).isFalse()
        }

    @Test
    fun `an answer still on hold at the stop is shown`() =
        runTest {
            val harness =
                Harness(
                    this,
                    listOf("YouTube", "LrcLib"),
                    mapOf("YouTube" to after(8_500, line), "LrcLib" to after(LONG_MS, LyricsReply.Failed)),
                )

            harness.run.run()

            assertThat(harness.shown).containsExactly("YouTube" to 9_000L)
        }

    @Test
    fun `cancelling the run ends every source and shows nothing`() =
        runTest {
            val harness = Harness(this, all, mapOf("YouTube" to after(1_000, line)))

            val job = async { harness.run.run() }
            advanceTimeBy(1_500)
            runCurrent()
            job.cancelAndJoin()
            advanceTimeBy(30_000)
            runCurrent()

            assertThat(harness.cancelled).containsExactly("BetterLyrics")
            assertThat(harness.shown).isEmpty()
            assertThat(harness.started).hasSize(2)
        }

    @Test
    fun `every source saying no is a clean miss`() =
        runTest {
            val replies = listOf("YouTube", "LrcLib", "KuGou").associateWith { after(100, LyricsReply.Miss) }
            val harness = Harness(this, replies.keys.toList(), replies)

            val result = harness.run.run()

            assertThat(result.everySourceMissed).isTrue()
            assertThat(currentTime).isEqualTo(200L)
        }

    @Test
    fun `one failed source is not a clean miss`() =
        runTest {
            val replies = mapOf("YouTube" to after(100, LyricsReply.Miss), "LrcLib" to after(100, LyricsReply.Failed))
            val harness = Harness(this, replies.keys.toList(), replies)

            assertThat(harness.run.run().everySourceMissed).isFalse()
        }

    @Test
    fun `no sources at all ends at once without a miss`() =
        runTest {
            val result = Harness(this, emptyList(), emptyMap()).run.run()

            assertThat(result.best).isNull()
            assertThat(result.everySourceMissed).isFalse()
            assertThat(currentTime).isEqualTo(0L)
        }

    @Test
    fun `with a saved line synced copy only word sync or youtube music is shown`() =
        runTest {
            val harness =
                Harness(
                    this,
                    listOf("BetterLyrics", "LrcRed", "KuGou"),
                    mapOf("BetterLyrics" to after(100, line), "LrcRed" to after(200, LyricsReply.Miss), "KuGou" to after(300, word)),
                    alreadyShown = LyricsKind.LINE,
                )

            harness.run.run()

            assertThat(harness.shown).containsExactly("KuGou" to 500L)
        }

    @Test
    fun `a saved line synced copy with no upgrade reports nothing new`() =
        runTest {
            val harness = Harness(this, listOf("LrcLib"), mapOf("LrcLib" to after(100, line)), alreadyShown = LyricsKind.LINE)

            val result = harness.run.run()

            assertThat(result.best).isNull()
            assertThat(harness.shown).isEmpty()
        }

    @Test
    fun `a saved plain copy is replaced by a line synced answer after the hold`() =
        runTest {
            val harness = Harness(this, listOf("LrcLib"), mapOf("LrcLib" to after(100, line)), alreadyShown = LyricsKind.PLAIN)

            harness.run.run()

            assertThat(harness.shown).containsExactly("LrcLib" to 900L)
        }

    @Test
    fun `a refused answer is not returned as the best one and is not a clean miss`() =
        runTest {
            val harness =
                Harness(
                    this,
                    listOf("YouTube", "LrcLib"),
                    mapOf("YouTube" to after(100, word), "LrcLib" to after(100, LyricsReply.Miss)),
                    refused = setOf("YouTube"),
                )

            val result = harness.run.run()

            assertThat(result.best).isNull()
        }

    @Test
    fun `a refused line synced answer does not read as no lyrics`() =
        runTest {
            val harness =
                Harness(
                    this,
                    listOf("YouTube", "LrcLib"),
                    mapOf("YouTube" to after(100, line), "LrcLib" to after(100, LyricsReply.Miss)),
                    refused = setOf("YouTube"),
                )

            val result = harness.run.run()

            assertThat(result.best).isNull()
            assertThat(result.everySourceMissed).isFalse()
        }

    @Test
    fun `a run counts as having looked once any source really replied`() =
        runTest {
            val oneReplied = mapOf("YouTube" to after(100, LyricsReply.Miss), "LrcLib" to after(100, LyricsReply.Failed))
            val allFailed = listOf("YouTube", "LrcLib").associateWith { after(100, LyricsReply.Failed) }
            val stalled = mapOf("YouTube" to after(100, LyricsReply.Miss))

            assertThat(Harness(this, oneReplied.keys.toList(), oneReplied).run.run().someSourceReplied).isTrue()
            assertThat(Harness(this, allFailed.keys.toList(), allFailed).run.run().someSourceReplied).isFalse()
            assertThat(Harness(this, listOf("YouTube", "LrcLib"), stalled).run.run().someSourceReplied).isTrue()
        }

    private companion object {
        const val LONG_MS = 60_000L
    }
}
