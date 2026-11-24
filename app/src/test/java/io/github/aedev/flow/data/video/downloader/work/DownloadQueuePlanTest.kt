package io.github.aedev.flow.data.video.downloader.work

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.dao.QueuedDownload
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import org.junit.Test

class DownloadQueuePlanTest {
    private fun row(
        id: String,
        status: DownloadItemStatus = DownloadItemStatus.PENDING,
        createdAt: Long = 0,
    ) = QueuedDownload(id, createdAt, status)

    @Test
    fun `waiting downloads start oldest first up to the limit`() {
        val plan = DownloadQueuePlan.of(listOf(row("a"), row("b"), row("c")), running = emptySet(), limit = 2)

        assertThat(plan.toStart).containsExactly("a", "b").inOrder()
        assertThat(plan.toStop).isEmpty()
    }

    @Test
    fun `a download waiting out a failure is skipped and its slot goes to the next one`() {
        val plan = DownloadQueuePlan.of(listOf(row("a"), row("b")), running = emptySet(), limit = 1, held = setOf("a"))

        assertThat(plan.toStart).containsExactly("b")
    }

    @Test
    fun `running downloads count against the limit`() {
        val plan =
            DownloadQueuePlan.of(
                listOf(row("a", DownloadItemStatus.DOWNLOADING), row("b"), row("c")),
                running = setOf("a"),
                limit = 2,
            )

        assertThat(plan.toStart).containsExactly("b")
    }

    @Test
    fun `a paused, cancelled or removed download is stopped and frees its slot`() {
        val plan =
            DownloadQueuePlan.of(
                listOf(row("a", DownloadItemStatus.PAUSED), row("d", DownloadItemStatus.CANCELLED), row("c")),
                running = setOf("a", "b", "d"),
                limit = 1,
            )

        assertThat(plan.toStop).containsExactly("a", "b", "d")
        assertThat(plan.toStart).containsExactly("c")
    }

    @Test
    fun `a lowered limit only holds new downloads back`() {
        val plan =
            DownloadQueuePlan.of(
                listOf(row("a", DownloadItemStatus.DOWNLOADING), row("b", DownloadItemStatus.DOWNLOADING), row("c")),
                running = setOf("a", "b"),
                limit = 1,
            )

        assertThat(plan.toStop).isEmpty()
        assertThat(plan.toStart).isEmpty()
    }

    @Test
    fun `a download that just finished or failed is left to complete its own work`() {
        val plan =
            DownloadQueuePlan.of(
                listOf(row("done", DownloadItemStatus.COMPLETED), row("bad", DownloadItemStatus.FAILED)),
                running = setOf("done", "bad"),
                limit = 3,
            )

        assertThat(plan.toStop).isEmpty()
        assertThat(plan.toStart).isEmpty()
    }

    @Test
    fun `a stop never sends a finished download back to the queue`() {
        assertThat(stopActionFor(DownloadItemStatus.COMPLETED)).isEqualTo(StopAction.NOTHING)
        assertThat(stopActionFor(DownloadItemStatus.FAILED)).isEqualTo(StopAction.NOTHING)
        assertThat(stopActionFor(DownloadItemStatus.PAUSED)).isEqualTo(StopAction.KEEP_PAUSED)
        assertThat(stopActionFor(DownloadItemStatus.CANCELLED)).isEqualTo(StopAction.DISCARD)
        assertThat(stopActionFor(null)).isEqualTo(StopAction.DISCARD)
        assertThat(stopActionFor(DownloadItemStatus.DOWNLOADING)).isEqualTo(StopAction.WAIT)
    }
}
