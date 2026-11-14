package io.github.aedev.flow.service

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.RestoredMusicQueue
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test

/** A plain JVM test: Robolectric's natives do not load on the CI image, so [Uri] is mocked. */
class MusicPlaybackResumptionTest {
    private fun track(id: String) = MusicTrack(videoId = id, title = "Title $id", artist = "Artist", thumbnailUrl = "", duration = 200)

    private val queue = listOf(track("aaaaaaaaaaa"), track("bbbbbbbbbbb"), track("ccccccccccc"))

    private fun restored(
        track: MusicTrack? = queue[1],
        index: Int = 0,
        positionMs: Long = 42_000L,
    ) = RestoredMusicQueue(
        queue = queue,
        index = index,
        track = track,
        shuffleEnabled = false,
        automix = emptyList(),
        resumePositionMs = positionMs,
    )

    private val parsed = mutableMapOf<String, Uri>()

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { parsed.getOrPut(firstArg()) { mockk(relaxed = true) } }
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `resumption starts the saved track at its position`() {
        val items = musicResumptionItems(restored())

        assertThat(items.mediaItems.map { it.mediaId }).containsExactly("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc").inOrder()
        assertThat(items.startIndex).isEqualTo(1)
        assertThat(items.startPositionMs).isEqualTo(42_000L)
    }

    @Test
    fun `items stream through the music scheme under their cache key with their titles`() {
        val item = musicResumptionItems(restored()).mediaItems[1]

        assertThat(item.localConfiguration?.uri).isSameInstanceAs(parsed["music://bbbbbbbbbbb"])
        assertThat(item.localConfiguration?.customCacheKey).isEqualTo("bbbbbbbbbbb")
        assertThat(item.mediaMetadata.title.toString()).isEqualTo("Title bbbbbbbbbbb")
    }

    @Test
    fun `items are playable leaves so the system media controls accept them`() {
        musicResumptionItems(restored()).mediaItems.forEach { item ->
            assertThat(item.mediaMetadata.isBrowsable).isFalse()
            assertThat(item.mediaMetadata.isPlayable).isTrue()
        }
    }

    @Test
    fun `without a saved track the saved index is used`() {
        assertThat(musicResumptionItems(restored(track = null, index = 2)).startIndex).isEqualTo(2)
    }
}
