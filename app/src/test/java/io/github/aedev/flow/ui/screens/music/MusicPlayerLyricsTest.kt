package io.github.aedev.flow.ui.screens.music

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LyricsBackgroundStyle
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.localmedia.LocalLyrics
import io.github.aedev.flow.data.localmedia.LocalLyricsReader
import io.github.aedev.flow.data.localmedia.LocalLyricsSource
import io.github.aedev.flow.data.lyrics.LyricsCandidate
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.data.lyrics.LyricsHelper
import io.github.aedev.flow.data.lyrics.MusicVideoLyricsTiming
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.utils.NetworkState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicPlayerLyricsTest {
    private val context =
        mockk<Context> {
            every { getString(R.string.lyrics_source_local_file) } returns "Lyrics file"
            every { getString(R.string.lyrics_source_embedded) } returns "Embedded lyrics"
        }
    private val helper =
        mockk<LyricsHelper>(relaxed = true) {
            every { entriesAreSynced(any()) } answers { firstArg<List<*>>().size >= 2 }
            coEvery { syncOffset(any()) } returns null
        }
    private val onDemand = MutableStateFlow(false)
    private val preferences =
        mockk<PlayerPreferences> {
            every { lyricsOnDemand } returns onDemand
            every { lyricsShowTranslation } returns flowOf(false)
            every { lyricsShowRomanization } returns flowOf(false)
            every { lyricsAutoRomanize } returns flowOf(false)
            every { lyricsRomanizationOnly } returns flowOf(false)
            every { lyricsKeepScreenOn } returns flowOf(false)
            every { lyricsBlurOtherLines } returns flowOf(true)
            every { lyricsBackground } returns flowOf(LyricsBackgroundStyle.SOLID)
        }
    private val reader =
        mockk<LocalLyricsReader> {
            coEvery { read("local_7") } returns
                LocalLyrics("[offset:+250]\n[00:01.00]First\n[00:09.00]Second", LocalLyricsSource.FILE)
            coEvery { readDownload("/music/Song.m4a") } returns
                LocalLyrics("[00:02.00]Saved\n[00:12.00]Offline", LocalLyricsSource.EMBEDDED)
        }
    private val downloads = mapOf("dQw4w9WgXcQ" to "/music/Song.m4a")
    private val timingSetting = MutableStateFlow(false)
    private val timing =
        mockk<MusicVideoLyricsTiming> {
            every { enabled } returns timingSetting
            coEvery { lookUpAndFit(any(), any(), any()) } answers {
                secondArg<List<LyricsEntry>>().map { it.copy(time = it.time + 10_000L) }
            }
        }

    @After
    fun tearDown() = unmockkObject(NetworkState)

    @Test
    fun `a device song reads its lrc and never asks an online provider`() =
        runTest {
            val state = MutableStateFlow(MusicPlayerUiState())
            val lyrics = MusicPlayerLyrics(context, backgroundScope, state, helper, preferences, reader, downloads::get, timing)

            lyrics.fetch(videoId = "local_7", artist = "Artist", title = "Song")
            state.first { it.lyricsProviderName.isNotEmpty() }

            coVerify(exactly = 0) { helper.getLyrics(any(), any(), any(), any(), any(), any(), any()) }
            assertThat(state.value.syncedLyrics.map { it.text }).containsExactly("First", "Second").inOrder()
            assertThat(state.value.lyricsProviderName).isEqualTo("Lyrics file")
            assertThat(state.value.lyricsSyncOffsetMs).isEqualTo(250L)
            assertThat(state.value.isLyricsLoading).isFalse()
        }

    @Test
    fun `a downloaded song plays its saved lyrics while offline without asking a provider`() =
        runTest {
            mockkObject(NetworkState)
            every { NetworkState.isOnline(any()) } returns false
            val state = MutableStateFlow(MusicPlayerUiState())
            val lyrics = MusicPlayerLyrics(context, backgroundScope, state, helper, preferences, reader, downloads::get, timing)

            lyrics.fetch(videoId = "dQw4w9WgXcQ", artist = "Artist", title = "Song")
            state.first { it.lyricsProviderName.isNotEmpty() }

            coVerify(exactly = 0) { helper.getLyrics(any(), any(), any(), any(), any(), any(), any()) }
            assertThat(state.value.syncedLyrics.map { it.text }).containsExactly("Saved", "Offline").inOrder()
            assertThat(state.value.lyricsProviderName).isEqualTo("Embedded lyrics")
        }

    private val providerLines = listOf(LyricsEntry(1_000L, "One"), LyricsEntry(9_000L, "Two"))

    private fun TestScope.lyricsFromProvider(state: MutableStateFlow<MusicPlayerUiState>): MusicPlayerLyrics {
        mockkObject(NetworkState)
        every { NetworkState.isOnline(any()) } returns true
        coEvery { helper.getLyrics(any(), any(), any(), any(), any(), any(), any()) } returns (providerLines to "LrcLib")
        return MusicPlayerLyrics(context, backgroundScope, state, helper, preferences, reader, downloads::get, timing)
    }

    private val song = MusicTrack(videoId = "v", title = "Song", artist = "Artist", thumbnailUrl = "", duration = 200)

    @Test
    fun `a song change looks lyrics up at once while they load on every song`() =
        runTest {
            val state = MutableStateFlow(MusicPlayerUiState(currentTrack = song))

            lyricsFromProvider(state).onSongChanged(song)

            state.first { it.syncedLyrics == providerLines }
            coVerify(exactly = 1) { helper.getLyrics("v", any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `on demand a song change waits for the player to open, then loads once`() =
        runTest {
            onDemand.value = true
            val state = MutableStateFlow(MusicPlayerUiState(currentTrack = song))
            val lyrics = lyricsFromProvider(state)

            lyrics.onSongChanged(song)
            lyrics.warm(song)
            runCurrent()
            coVerify(exactly = 0) { helper.getLyrics(any(), any(), any(), any(), any(), any(), any()) }

            lyrics.setPlayerOpen(true)
            state.first { it.syncedLyrics == providerLines }
            coVerify(exactly = 1) { helper.getLyrics("v", any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `on demand a song change while the player is open looks lyrics up at once`() =
        runTest {
            onDemand.value = true
            val state = MutableStateFlow(MusicPlayerUiState())
            val lyrics = lyricsFromProvider(state)
            lyrics.setPlayerOpen(true)

            state.value = state.value.copy(currentTrack = song)
            lyrics.onSongChanged(song)

            state.first { it.syncedLyrics == providerLines }
            coVerify(exactly = 1) { helper.getLyrics("v", any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `lyrics stay as they came and nothing is looked up with the setting off`() =
        runTest {
            val state = MutableStateFlow(MusicPlayerUiState())

            lyricsFromProvider(state).fetch(videoId = "v", artist = "Artist", title = "Song", duration = 200)
            state.first { it.lyricsProviderName.isNotEmpty() }
            advanceUntilIdle()

            assertThat(state.value.syncedLyrics).isEqualTo(providerLines)
            coVerify(exactly = 0) { timing.lookUpAndFit(any(), any(), any()) }
        }

    @Test
    fun `lyrics move with the setting on and go back when it is switched off`() =
        runTest {
            timingSetting.value = true
            val state = MutableStateFlow(MusicPlayerUiState())

            lyricsFromProvider(state).fetch(videoId = "v", artist = "Artist", title = "Song", duration = 200)
            state.first { it.syncedLyrics.firstOrNull()?.time == 11_000L }

            timingSetting.value = false
            state.first { it.syncedLyrics.firstOrNull()?.time == 1_000L }
            coVerify(exactly = 1) { timing.lookUpAndFit("v", providerLines, 200) }
        }

    @Test
    fun `a late answer doesn't touch the next song's lyrics`() =
        runTest {
            timingSetting.value = true
            val state = MutableStateFlow(MusicPlayerUiState(currentTrack = mockk { every { videoId } returns "next" }))

            lyricsFromProvider(state).fetch(videoId = "v", artist = "Artist", title = "Song", duration = 200)
            state.first { it.lyricsProviderName.isNotEmpty() }
            advanceUntilIdle()

            assertThat(state.value.syncedLyrics).isEqualTo(providerLines)
        }

    @Test
    fun `a downloaded song falls back to its saved lyrics when no provider has any`() =
        runTest {
            mockkObject(NetworkState)
            every { NetworkState.isOnline(any()) } returns true
            coEvery { helper.getLyrics(any(), any(), any(), any(), any(), any(), any()) } returns null
            val state = MutableStateFlow(MusicPlayerUiState())
            val lyrics = MusicPlayerLyrics(context, backgroundScope, state, helper, preferences, reader, downloads::get, timing)

            lyrics.fetch(videoId = "dQw4w9WgXcQ", artist = "Artist", title = "Song")
            state.first { it.lyricsProviderName.isNotEmpty() }

            assertThat(state.value.lyricsProviderName).isEqualTo("Embedded lyrics")
        }

    private val lineSynced = listOf(LyricsEntry(1_000L, "line one"), LyricsEntry(20_000L, "line two"))
    private val wordSynced = listOf(LyricsEntry(1_000L, "word one"), LyricsEntry(20_000L, "word two"))

    private fun answerEarlyThenSwap() {
        mockkObject(NetworkState)
        every { NetworkState.isOnline(any()) } returns true
        coEvery { helper.getLyrics(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val onAnswer = arg<suspend (Pair<List<LyricsEntry>, String>) -> Unit>(6)
            delay(800)
            onAnswer(lineSynced to "YouTube")
            delay(2_000)
            onAnswer(wordSynced to "BetterLyrics")
            wordSynced to "BetterLyrics"
        }
    }

    @Test
    fun `an early answer shows right away and a later one swaps it`() =
        runTest {
            answerEarlyThenSwap()
            val state = MutableStateFlow(MusicPlayerUiState())
            val lyrics = MusicPlayerLyrics(context, backgroundScope, state, helper, preferences, reader, downloads::get, timing)

            lyrics.fetch(videoId = "dQw4w9WgXcQ", artist = "Artist", title = "Song")
            advanceTimeBy(1_000)

            assertThat(state.value.isLyricsLoading).isFalse()
            assertThat(state.value.syncedLyrics).isEqualTo(lineSynced)
            assertThat(state.value.lyricsProviderName).isEqualTo("YouTube")

            advanceTimeBy(2_000)

            assertThat(state.value.syncedLyrics).isEqualTo(wordSynced)
            assertThat(state.value.lyricsProviderName).isEqualTo("BetterLyrics")
        }

    @Test
    fun `a source picked by hand is not swapped by a run still going`() =
        runTest {
            answerEarlyThenSwap()
            val track = mockk<MusicTrack>(relaxed = true)
            val picked = listOf(LyricsEntry(1_000L, "picked one"), LyricsEntry(20_000L, "picked two"))
            val state = MutableStateFlow(MusicPlayerUiState(currentTrack = track))
            val lyrics = MusicPlayerLyrics(context, backgroundScope, state, helper, preferences, reader, downloads::get, timing)

            lyrics.fetch(videoId = "dQw4w9WgXcQ", artist = "Artist", title = "Song")
            advanceTimeBy(1_000)
            lyrics.applyCandidate(LyricsCandidate("LrcLib", picked, synced = true))
            advanceTimeBy(5_000)

            assertThat(state.value.syncedLyrics).isEqualTo(picked)
            assertThat(state.value.lyricsProviderName).isEqualTo("LrcLib")
        }

    @Test
    fun `a final answer repeating the early one keeps the fitted timing`() =
        runTest {
            mockkObject(NetworkState)
            every { NetworkState.isOnline(any()) } returns true
            timingSetting.value = true
            coEvery { helper.getLyrics(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val onAnswer = arg<suspend (Pair<List<LyricsEntry>, String>) -> Unit>(6)
                onAnswer(lineSynced to "YouTube")
                delay(1_000)
                lineSynced to "YouTube"
            }
            val state = MutableStateFlow(MusicPlayerUiState())
            val lyrics = MusicPlayerLyrics(context, backgroundScope, state, helper, preferences, reader, downloads::get, timing)

            lyrics.fetch(videoId = "dQw4w9WgXcQ", artist = "Artist", title = "Song")
            advanceTimeBy(2_000)

            assertThat(state.value.syncedLyrics.map { it.time }).containsExactly(11_000L, 30_000L).inOrder()
            coVerify(exactly = 1) { timing.lookUpAndFit(any(), any(), any()) }
        }

    @Test
    fun `a song's saved offset comes back on load and a pick keeps it`() =
        runTest {
            answerEarlyThenSwap()
            coEvery { helper.syncOffset("dQw4w9WgXcQ") } returns -500L
            val track = mockk<MusicTrack>(relaxed = true)
            val state = MutableStateFlow(MusicPlayerUiState(currentTrack = track))
            val lyrics = MusicPlayerLyrics(context, backgroundScope, state, helper, preferences, reader, downloads::get, timing)

            lyrics.fetch(videoId = "dQw4w9WgXcQ", artist = "Artist", title = "Song")
            advanceTimeBy(1_000)

            assertThat(state.value.lyricsSyncOffsetMs).isEqualTo(-500L)

            lyrics.applyCandidate(LyricsCandidate("LrcLib", lineSynced, synced = true))
            advanceTimeBy(100)

            assertThat(state.value.lyricsSyncOffsetMs).isEqualTo(-500L)
        }

    @Test
    fun `quick nudges save once and reset clears the saved offset`() =
        runTest {
            val track = mockk<MusicTrack> { every { videoId } returns "v" }
            val state = MutableStateFlow(MusicPlayerUiState(currentTrack = track))
            val lyrics = MusicPlayerLyrics(context, backgroundScope, state, helper, preferences, reader, downloads::get, timing)

            lyrics.adjustSyncOffset(100L)
            lyrics.adjustSyncOffset(100L)
            advanceTimeBy(1_000)

            coVerify(exactly = 1) { helper.saveSyncOffset("v", any()) }
            coVerify { helper.saveSyncOffset("v", 200L) }

            lyrics.resetSyncOffset()
            advanceTimeBy(1_000)

            assertThat(state.value.lyricsSyncOffsetMs).isEqualTo(0L)
            coVerify { helper.clearSyncOffset("v") }
        }

    private fun track(id: String) = mockk<MusicTrack>(relaxed = true) { every { videoId } returns id }

    @Test
    fun `a new song ends the source browse and a late candidate is dropped`() =
        runTest {
            mockkObject(NetworkState)
            every { NetworkState.isOnline(any()) } returns true
            coEvery { helper.getLyrics(any(), any(), any(), any(), any(), any(), any()) } returns null
            val candidate = LyricsCandidate("LrcLib", lineSynced, synced = true)
            coEvery { helper.getAllLyrics(any(), any(), any(), any(), any(), any()) } coAnswers {
                delay(1_000)
                arg<suspend (LyricsCandidate) -> Unit>(5)(candidate)
            }
            val state = MutableStateFlow(MusicPlayerUiState(currentTrack = track("a")))
            val lyrics = MusicPlayerLyrics(context, backgroundScope, state, helper, preferences, reader, downloads::get, timing)

            lyrics.browseCandidates()
            advanceTimeBy(500)
            state.value = state.value.copy(currentTrack = track("b"))
            lyrics.fetch(videoId = "b", artist = "Artist", title = "Song")
            advanceTimeBy(2_000)

            assertThat(state.value.lyricsCandidates).isEmpty()
            assertThat(state.value.isBrowsingLyrics).isFalse()
        }

    @Test
    fun `a pick that lands after the song changed does not show on the new song`() =
        runTest {
            coEvery { helper.applyManualLyrics(any(), any(), any()) } coAnswers { delay(1_000) }
            val state = MutableStateFlow(MusicPlayerUiState(currentTrack = track("a")))
            val lyrics = MusicPlayerLyrics(context, backgroundScope, state, helper, preferences, reader, downloads::get, timing)

            lyrics.applyCandidate(LyricsCandidate("LrcLib", lineSynced, synced = true))
            advanceTimeBy(500)
            state.value = state.value.copy(currentTrack = track("b"))
            advanceTimeBy(1_000)

            coVerify { helper.applyManualLyrics("a", lineSynced, any()) }
            assertThat(state.value.syncedLyrics).isEmpty()
        }

    @Test
    fun `a refresh that ends after the song changed does not fetch the old song`() =
        runTest {
            coEvery { helper.forceRefresh(any(), any()) } coAnswers { delay(1_000) }
            val state = MutableStateFlow(MusicPlayerUiState(currentTrack = track("a")))
            val lyrics = MusicPlayerLyrics(context, backgroundScope, state, helper, preferences, reader, downloads::get, timing)

            lyrics.refresh()
            advanceTimeBy(500)
            state.value = state.value.copy(currentTrack = track("b"))
            advanceTimeBy(1_000)

            coVerify(exactly = 0) { helper.getLyrics(any(), any(), any(), any(), any(), any(), any()) }
        }
}
