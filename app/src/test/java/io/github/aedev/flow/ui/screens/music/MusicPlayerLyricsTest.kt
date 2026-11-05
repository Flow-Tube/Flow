package io.github.aedev.flow.ui.screens.music

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.localmedia.LocalLyrics
import io.github.aedev.flow.data.localmedia.LocalLyricsReader
import io.github.aedev.flow.data.localmedia.LocalLyricsSource
import io.github.aedev.flow.data.lyrics.LyricsHelper
import io.github.aedev.flow.utils.NetworkState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
        }
    private val preferences =
        mockk<PlayerPreferences> {
            every { lyricsShowTranslation } returns flowOf(false)
            every { lyricsShowRomanization } returns flowOf(false)
            every { lyricsAutoRomanize } returns flowOf(false)
            every { lyricsKeepScreenOn } returns flowOf(false)
            every { lyricsBlurOtherLines } returns flowOf(true)
        }
    private val reader =
        mockk<LocalLyricsReader> {
            coEvery { read("local_7") } returns
                LocalLyrics("[offset:+250]\n[00:01.00]First\n[00:09.00]Second", LocalLyricsSource.FILE)
            coEvery { readDownload("/music/Song.m4a") } returns
                LocalLyrics("[00:02.00]Saved\n[00:12.00]Offline", LocalLyricsSource.EMBEDDED)
        }
    private val downloads = mapOf("dQw4w9WgXcQ" to "/music/Song.m4a")

    @After
    fun tearDown() = unmockkObject(NetworkState)

    @Test
    fun `a device song reads its lrc and never asks an online provider`() =
        runTest {
            val state = MutableStateFlow(MusicPlayerUiState())
            val lyrics = MusicPlayerLyrics(context, this, state, helper, preferences, reader, downloads::get)

            lyrics.fetch(videoId = "local_7", artist = "Artist", title = "Song")
            state.first { it.lyricsProviderName.isNotEmpty() }

            coVerify(exactly = 0) { helper.getLyrics(any(), any(), any(), any(), any(), any()) }
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
            val lyrics = MusicPlayerLyrics(context, this, state, helper, preferences, reader, downloads::get)

            lyrics.fetch(videoId = "dQw4w9WgXcQ", artist = "Artist", title = "Song")
            state.first { it.lyricsProviderName.isNotEmpty() }

            coVerify(exactly = 0) { helper.getLyrics(any(), any(), any(), any(), any(), any()) }
            assertThat(state.value.syncedLyrics.map { it.text }).containsExactly("Saved", "Offline").inOrder()
            assertThat(state.value.lyricsProviderName).isEqualTo("Embedded lyrics")
        }

    @Test
    fun `a downloaded song falls back to its saved lyrics when no provider has any`() =
        runTest {
            mockkObject(NetworkState)
            every { NetworkState.isOnline(any()) } returns true
            coEvery { helper.getLyrics(any(), any(), any(), any(), any(), any()) } returns null
            val state = MutableStateFlow(MusicPlayerUiState())
            val lyrics = MusicPlayerLyrics(context, this, state, helper, preferences, reader, downloads::get)

            lyrics.fetch(videoId = "dQw4w9WgXcQ", artist = "Artist", title = "Song")
            state.first { it.lyricsProviderName.isNotEmpty() }

            assertThat(state.value.lyricsProviderName).isEqualTo("Embedded lyrics")
        }
}
