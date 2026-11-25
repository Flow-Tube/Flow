package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.shorts.reelAudioFormat
import io.github.aedev.flow.data.shorts.reelExtraction
import io.github.aedev.flow.data.shorts.reelVideoFormat
import io.mockk.mockk
import org.junit.Test

class ReusableExtractionsTest {
    private var now = 1_000L
    private var generation = 0
    private val gated = mutableSetOf<String>()
    private val kept =
        ReusableExtractions(
            identity = { generation },
            isGated = { it in gated },
            nowMs = { now },
        )

    private fun playable(expiresInSeconds: Int = 21_540) =
        reelExtraction(
            videoFormats = listOf(reelVideoFormat(itag = 248, mimeType = "video/webm; codecs=\"vp9\"", width = 1920, height = 1080)),
            audioFormats = listOf(reelAudioFormat(itag = 251, mimeType = "audio/webm; codecs=\"opus\"")),
            expiresInSeconds = expiresInSeconds,
        )

    @Test
    fun `a re-open within the lifetime gets the kept extraction`() {
        val result = playable()
        kept.store("video", result)

        assertThat(kept.get("video")).isSameInstanceAs(result)
        assertThat(kept.get("video")).isSameInstanceAs(result)
    }

    @Test
    fun `an entry lasts 80 percent of the URL lifetime, at most 10 minutes`() {
        kept.store("short-lived", playable(expiresInSeconds = 100))
        kept.store("long-lived", playable())

        now += 80_000L
        assertThat(kept.get("short-lived")).isNull()
        assertThat(kept.get("long-lived")).isNotNull()

        now += ReusableExtractions.MAX_TTL_MS
        assertThat(kept.get("long-lived")).isNull()
    }

    @Test
    fun `a refused video's entry is dropped`() {
        kept.store("video", playable())

        kept.forget("video")

        assertThat(kept.get("video")).isNull()
    }

    @Test
    fun `an entry from a client GVS has since gated is dropped`() {
        val result = playable()
        kept.store("video", result)

        gated += result.usedClient.clientName
        assertThat(kept.get("video")).isNull()

        gated.clear()
        assertThat(kept.get("video")).isNull()
    }

    @Test
    fun `a visitor identity change drops every entry made under the old one`() {
        kept.store("video", playable())

        generation++

        assertThat(kept.get("video")).isNull()
    }

    @Test
    fun `live, SABR, unplayable and undated results are never kept`() {
        kept.store("live", playable().copy(isLive = true))
        kept.store("sabr", playable().copy(sabrInfo = mockk()))
        kept.store("silent", playable().copy(audioFormats = emptyList()))
        kept.store("undated", playable(expiresInSeconds = 0))

        assertThat(listOf("live", "sabr", "silent", "undated").mapNotNull(kept::get)).isEmpty()
    }

    @Test
    fun `only the most recently used few are kept`() {
        val first = playable()
        kept.store("first", first)
        repeat(ReusableExtractions.CAPACITY - 1) { kept.store("other$it", playable()) }
        kept.get("first")
        kept.store("newest", playable())

        assertThat(kept.get("first")).isSameInstanceAs(first)
        assertThat(kept.get("other0")).isNull()
    }
}
