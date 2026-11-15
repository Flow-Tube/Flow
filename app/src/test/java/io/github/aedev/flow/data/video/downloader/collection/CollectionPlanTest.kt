package io.github.aedev.flow.data.video.downloader.collection

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.entity.CollectionItemState
import io.github.aedev.flow.data.local.entity.DownloadCollectionItemEntity
import org.junit.Test

class CollectionPlanTest {
    private fun member(
        videoId: String,
        position: Int,
        state: CollectionItemState = CollectionItemState.WANTED,
        ownsDownload: Boolean = true,
    ) = DownloadCollectionItemEntity("PL", videoId, position, addedAt = 1L, state = state, ownsDownload = ownsDownload)

    private fun plan(
        ids: List<String>,
        existing: List<DownloadCollectionItemEntity> = emptyList(),
        downloaded: Set<String> = emptySet(),
        complete: Boolean = true,
        picked: Set<String>? = null,
    ) = CollectionPlan.of("PL", ids, existing, { it in downloaded }, complete, now = 9L, picked = picked)

    @Test
    fun `a first download queues every video in list order`() {
        val result = plan(listOf("a", "b", "c"))

        assertThat(result.toQueue).containsExactly("a", "b", "c").inOrder()
        assertThat(result.members.map { it.position }).containsExactly(0, 1, 2).inOrder()
        assertThat(result.members.all { it.ownsDownload && it.addedAt == 9L }).isTrue()
    }

    @Test
    fun `a video already downloaded elsewhere is referenced, not queued or owned`() {
        val result = plan(listOf("a", "b"), downloaded = setOf("a"))

        assertThat(result.toQueue).containsExactly("b")
        assertThat(result.members.first { it.videoId == "a" }.ownsDownload).isFalse()
    }

    @Test
    fun `a sync queues only what is new or still missing`() {
        val existing = listOf(member("a", 0), member("b", 1))

        val result = plan(listOf("a", "b", "c"), existing, downloaded = setOf("a"))

        assertThat(result.toQueue).containsExactly("b", "c").inOrder()
    }

    @Test
    fun `a video the user excluded is never queued again`() {
        val existing = listOf(member("a", 0, CollectionItemState.EXCLUDED))

        val result = plan(listOf("a", "b"), existing)

        assertThat(result.toQueue).containsExactly("b")
        assertThat(result.members.first { it.videoId == "a" }.state).isEqualTo(CollectionItemState.EXCLUDED)
    }

    @Test
    fun `a video gone from a complete list is marked removed upstream and kept`() {
        val existing = listOf(member("a", 0), member("b", 1))

        val result = plan(listOf("b"), existing, downloaded = setOf("a", "b"))

        val gone = result.members.single { it.videoId == "a" }
        assertThat(gone.state).isEqualTo(CollectionItemState.REMOVED_UPSTREAM)
        assertThat(gone.ownsDownload).isTrue()
        assertThat(result.members.single { it.videoId == "b" }.position).isEqualTo(0)
    }

    @Test
    fun `a truncated load marks nothing as removed`() {
        val existing = listOf(member("a", 0), member("b", 1))

        val result = plan(listOf("b"), existing, downloaded = setOf("a", "b"), complete = false)

        assertThat(result.members.map { it.videoId }).containsExactly("b")
    }

    @Test
    fun `a video back in the list is wanted again`() {
        val existing = listOf(member("a", 3, CollectionItemState.REMOVED_UPSTREAM))

        val result = plan(listOf("a"), existing)

        assertThat(result.members.single().state).isEqualTo(CollectionItemState.WANTED)
        assertThat(result.members.single().position).isEqualTo(0)
        assertThat(result.toQueue).containsExactly("a")
    }

    @Test
    fun `duplicate ids in a list become one member at the first position`() {
        val result = plan(listOf("a", "b", "a"))

        assertThat(result.members.map { it.videoId }).containsExactly("a", "b").inOrder()
        assertThat(result.toQueue).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `a picked download queues only the picked videos and excludes the rest`() {
        val result = plan(listOf("a", "b", "c"), picked = setOf("a", "c"))

        assertThat(result.toQueue).containsExactly("a", "c").inOrder()
        val b = result.members.first { it.videoId == "b" }
        assertThat(b.state).isEqualTo(CollectionItemState.EXCLUDED)
        assertThat(b.ownsDownload).isFalse()
    }

    @Test
    fun `picking an excluded video again makes it wanted and queues it`() {
        val existing = listOf(member("a", 0), member("b", 1, CollectionItemState.EXCLUDED, ownsDownload = false))

        val result = plan(listOf("a", "b"), existing, downloaded = setOf("a"), picked = setOf("a", "b"))

        assertThat(result.toQueue).containsExactly("b")
        val b = result.members.first { it.videoId == "b" }
        assertThat(b.state).isEqualTo(CollectionItemState.WANTED)
        assertThat(b.ownsDownload).isTrue()
    }

    @Test
    fun `leaving out a downloaded video excludes it without touching its file`() {
        val existing = listOf(member("a", 0), member("b", 1))

        val result = plan(listOf("a", "b"), existing, downloaded = setOf("a", "b"), picked = setOf("a"))

        assertThat(result.toQueue).isEmpty()
        val b = result.members.first { it.videoId == "b" }
        assertThat(b.state).isEqualTo(CollectionItemState.EXCLUDED)
        assertThat(b.ownsDownload).isTrue()
    }

    @Test
    fun `a video removed upstream and listed again follows the pick`() {
        val existing = listOf(member("a", 0, CollectionItemState.REMOVED_UPSTREAM))

        val result = plan(listOf("a"), existing, picked = emptySet())

        assertThat(result.members.single().state).isEqualTo(CollectionItemState.EXCLUDED)
        assertThat(result.toQueue).isEmpty()
    }
}
