package io.github.aedev.flow.player.queue

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueuePageLoaderTest {
    private fun done(page: QueuePage?) = CompletableDeferred<QueuePage?>().apply { complete(page) }

    private fun video(id: String) = Video(id, id, "", "", "", 0, 0L, uploadDate = "")

    private class FakeSource(
        val pages: ArrayDeque<CompletableDeferred<QueuePage?>>,
    ) : QueuePageSource {
        var calls = 0
        override val spec: QueueSourceSpec = QueueSourceSpec.PlaylistPages("PL1", "t")

        override suspend fun next(): QueuePage? {
            calls++
            return pages.removeFirst().await()
        }
    }

    @Test
    fun `asks once when playback nears the end of what has loaded`() =
        runTest {
            val delivered = mutableListOf<Pair<Int, QueuePage?>>()
            val loader = QueuePageLoader(this) { generation, page -> delivered += generation to page }
            val first = CompletableDeferred<QueuePage?>()
            val source = FakeSource(ArrayDeque(listOf(first)))
            loader.attach(source, generation = 3)

            loader.onPosition(remaining = QUEUE_PAGE_LOOKAHEAD + 1)
            runCurrent()
            assertThat(source.calls).isEqualTo(0)

            loader.onPosition(remaining = QUEUE_PAGE_LOOKAHEAD)
            loader.onPosition(remaining = QUEUE_PAGE_LOOKAHEAD - 1)
            runCurrent()
            assertThat(source.calls).isEqualTo(1)

            first.complete(QueuePage(listOf(video("b")), hasMore = false))
            runCurrent()
            assertThat(delivered.single().first).isEqualTo(3)
            assertThat(loader.hasMore).isFalse()
        }

    @Test
    fun `a failed read keeps the source so the next position tries again`() =
        runTest {
            val delivered = mutableListOf<QueuePage?>()
            val loader = QueuePageLoader(this) { _, page -> delivered += page }
            val source = FakeSource(ArrayDeque(listOf(done(null), done(QueuePage(emptyList(), true)))))
            loader.attach(source, generation = 1)

            loader.load()
            runCurrent()
            assertThat(delivered).containsExactly(null)
            assertThat(loader.hasMore).isTrue()

            loader.load()
            runCurrent()
            assertThat(source.calls).isEqualTo(2)
        }

    @Test
    fun `a page for a source since replaced is not delivered`() =
        runTest {
            val delivered = mutableListOf<QueuePage?>()
            val loader = QueuePageLoader(this) { _, page -> delivered += page }
            val pending = CompletableDeferred<QueuePage?>()
            loader.attach(FakeSource(ArrayDeque(listOf(pending))), generation = 1)
            loader.load()
            runCurrent()

            loader.attach(null, generation = 2)
            pending.complete(QueuePage(listOf(video("x")), hasMore = true))
            runCurrent()

            assertThat(delivered).isEmpty()
            assertThat(loader.load()).isFalse()
        }
}
