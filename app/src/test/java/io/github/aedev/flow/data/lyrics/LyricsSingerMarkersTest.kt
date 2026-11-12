package io.github.aedev.flow.data.lyrics

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricsSingerMarkersTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val paxsenixSyllable =
        """
        [00:32.10]{agent:v1}Baby, I'm yours
        <Baby,:32.1:32.5|I'm:32.5:32.9|yours:32.9:33.6>
        [00:34.00]{bg}I'm yours
        <I'm:34.0:34.4|yours:34.4:35.0>
        [00:36.20]{agent:v2}You're so pretty it hurts
        <You're:36.2:36.6|so:36.6:36.9|pretty:36.9:37.3|it:37.3:37.5|hurts:37.5:38.2>
        """.trimIndent()

    @Test
    fun `singer markers never reach the line text`() {
        val entries = LyricsUtils.parseLyrics(paxsenixSyllable)

        assertThat(entries.map { it.text })
            .containsExactly("Baby, I'm yours", "I'm yours", "You're so pretty it hurts")
            .inOrder()
    }

    @Test
    fun `markers become the singer and the background flag`() {
        val entries = LyricsUtils.parseLyrics(paxsenixSyllable)

        assertThat(entries.map { it.agent to it.isBackground })
            .containsExactly("v1" to false, "v1" to true, "v2" to false)
            .inOrder()
    }

    @Test
    fun `word timings still come from the block under the line`() {
        val first = LyricsUtils.parseLyrics(paxsenixSyllable).first()

        assertThat(first.words?.map { it.text }).containsExactly("Baby,", "I'm", "yours").inOrder()
        assertThat(first.words?.first()?.startTime).isEqualTo(32_100L)
    }

    @Test
    fun `plain line synced lyrics are unchanged`() {
        val entries = LyricsUtils.parseLyrics("[00:01.00] One\n[00:05.00] Two")

        assertThat(entries).containsExactly(LyricsEntry(1_000L, "One"), LyricsEntry(5_000L, "Two")).inOrder()
    }

    @Test
    fun `lyrics cached with the markers in their text come back clean`() =
        runTest {
            val context = mockk<Context> { every { filesDir } returns folder.root }
            val stale =
                listOf(
                    LyricsEntry(1_000L, "{agent:v1}Baby, I'm yours"),
                    LyricsEntry(2_000L, "{bg}I'm yours"),
                    LyricsEntry(3_000L, "{agent:v2}You're so pretty"),
                )
            LyricsCacheManager.saveLyrics(context, "v", stale)

            val read = LyricsCacheManager.getLyrics(context, "v").orEmpty()

            assertThat(read.map { Triple(it.text, it.agent, it.isBackground) })
                .containsExactly(
                    Triple("Baby, I'm yours", "v1", false),
                    Triple("I'm yours", "v1", true),
                    Triple("You're so pretty", "v2", false),
                ).inOrder()
        }

    @Test
    fun `clean cached lyrics are returned as they are`() {
        val clean = listOf(LyricsEntry(1_000L, "One", agent = "v1"))

        assertThat(LyricsUtils.withoutLineMarkers(clean)).isSameInstanceAs(clean)
    }
}
