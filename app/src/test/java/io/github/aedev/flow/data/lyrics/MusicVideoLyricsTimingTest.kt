package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.repository.TimedSegment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MusicVideoLyricsTimingTest {
    private val setting = MutableStateFlow(true)
    private val preferences = mockk<PlayerPreferences> { every { lyricsSponsorBlockTiming } returns setting }
    private val repository = mockk<SponsorBlockRepository>()
    private val timing = MusicVideoLyricsTiming(preferences, repository)
    private val entries = listOf(LyricsEntry(5_000L, "One"), LyricsEntry(9_000L, "Two"))
    private val intro = TimedSegment(0.0, 20.0, videoDurationSeconds = 240.0)

    @Test
    fun `with the setting off nothing is sent`() =
        runTest {
            setting.value = false

            assertThat(timing.fit("v", entries, 240)).isSameInstanceAs(entries)
            coVerify(exactly = 0) { repository.getNonMusicSegments(any()) }
        }

    @Test
    fun `a video is looked up once however many times its lyrics are fitted`() =
        runTest {
            coEvery { repository.getNonMusicSegments("v") } returns listOf(intro)

            val first = timing.fit("v", entries, 240)
            val second = timing.lookUpAndFit("v", entries, 240)

            assertThat(first.map { it.time }).containsExactly(25_000L, 29_000L).inOrder()
            assertThat(second).isEqualTo(first)
            coVerify(exactly = 1) { repository.getNonMusicSegments("v") }
        }

    @Test
    fun `a video with no segments isn't looked up again`() =
        runTest {
            coEvery { repository.getNonMusicSegments("v") } returns emptyList()

            timing.fit("v", entries, 240)
            timing.fit("v", entries, 240)

            coVerify(exactly = 1) { repository.getNonMusicSegments("v") }
        }

    @Test
    fun `a failed lookup leaves the lyrics alone and is tried again`() =
        runTest {
            coEvery { repository.getNonMusicSegments("v") } returns null

            assertThat(timing.fit("v", entries, 240)).isSameInstanceAs(entries)
            timing.fit("v", entries, 240)

            coVerify(exactly = 2) { repository.getNonMusicSegments("v") }
        }

    @Test
    fun `segments voted on for another cut of the video are ignored`() =
        runTest {
            coEvery { repository.getNonMusicSegments("v") } returns listOf(intro)

            assertThat(timing.fit("v", entries, 300)).isEqualTo(entries)
            assertThat(timing.fit("v", entries, 244).first().time).isEqualTo(25_000L)
        }

    @Test
    fun `either length is enough to know where the video ends`() =
        runTest {
            coEvery { repository.getNonMusicSegments("v") } returns listOf(intro)
            coEvery { repository.getNonMusicSegments("w") } returns listOf(intro.copy(videoDurationSeconds = 0.0))

            assertThat(timing.fit("v", entries, 0).first().time).isEqualTo(25_000L)
            assertThat(timing.fit("w", entries, 240).first().time).isEqualTo(25_000L)
        }

    @Test
    fun `lyrics stay when neither length is known`() =
        runTest {
            coEvery { repository.getNonMusicSegments("v") } returns listOf(intro.copy(videoDurationSeconds = 0.0))

            assertThat(timing.fit("v", entries, 0)).isSameInstanceAs(entries)
        }
}
