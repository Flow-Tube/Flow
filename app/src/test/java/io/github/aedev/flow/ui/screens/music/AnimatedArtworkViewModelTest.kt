package io.github.aedev.flow.ui.screens.music

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.music.artwork.AnimatedArtworkRepository
import io.github.aedev.flow.data.music.artwork.AnimatedLoop
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.utils.NetworkState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AnimatedArtworkViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val repository: AnimatedArtworkRepository = mockk(relaxed = true)
    private val wifiOnly = MutableStateFlow(false)
    private val preferences: PlayerPreferences =
        mockk(relaxed = true) {
            every { animatedArtwork } returns flowOf(true)
            every { animatedArtworkWifiOnly } returns wifiOnly
        }
    private val song = MusicTrack(videoId = "song", title = "Salt Line", artist = "Harbour Lights", thumbnailUrl = "", duration = 200)
    private val other = song.copy(videoId = "other", title = "Tide Table")
    private val found = AnimatedLoop("https://example.org/loop.m3u8", null)

    private fun viewModel() = AnimatedArtworkViewModel(mockk<Context>(relaxed = true), repository, preferences, mockk(relaxed = true))

    private fun advanceTo(ms: Long) {
        testDispatcher.scheduler.advanceTimeBy(ms - testDispatcher.scheduler.currentTime)
        testDispatcher.scheduler.runCurrent()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(NetworkState)
        every { NetworkState.isOnWifi(any()) } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `a failed lookup is tried again at 20 s and shows the loop it finds`() {
        coEvery { repository.loopFor(song) } throws IOException("503") andThen found
        val viewModel = viewModel()

        viewModel.load(song)
        advanceTo(19_999)
        coVerify(exactly = 1) { repository.loopFor(song) }
        assertThat(viewModel.loop.value).isNull()

        advanceTo(20_000)
        coVerify(exactly = 2) { repository.loopFor(song) }
        assertThat(viewModel.loop.value?.urlFor("song", tallSpace = false)).isEqualTo(found.square)
    }

    @Test
    fun `a song that keeps failing is tried at 20 s and 60 s, then left alone`() {
        coEvery { repository.loopFor(song) } throws IOException("503")
        val viewModel = viewModel()

        viewModel.load(song)
        advanceTo(59_999)
        coVerify(exactly = 2) { repository.loopFor(song) }

        advanceTo(60_000)
        coVerify(exactly = 3) { repository.loopFor(song) }

        advanceTo(3_600_000)
        coVerify(exactly = 3) { repository.loopFor(song) }
    }

    @Test
    fun `after the last try, showing the song again asks once more`() {
        coEvery { repository.loopFor(song) } throws IOException("503")
        val viewModel = viewModel()

        viewModel.load(song)
        advanceTo(60_000)
        viewModel.load(song)
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 4) { repository.loopFor(song) }
    }

    @Test
    fun `an answer, loop or none, is not tried again`() {
        coEvery { repository.loopFor(song) } returns null
        coEvery { repository.loopFor(other) } returns found
        val viewModel = viewModel()

        viewModel.load(song)
        advanceTo(100_000)
        viewModel.load(other)
        advanceTo(200_000)

        coVerify(exactly = 1) { repository.loopFor(song) }
        coVerify(exactly = 1) { repository.loopFor(other) }
    }

    @Test
    fun `a new song cancels the old song's retry`() {
        coEvery { repository.loopFor(song) } throws IOException("503")
        coEvery { repository.loopFor(other) } returns found
        val viewModel = viewModel()

        viewModel.load(song)
        advanceTo(10_000)
        viewModel.load(other)
        advanceTo(100_000)

        coVerify(exactly = 1) { repository.loopFor(song) }
        assertThat(viewModel.loop.value?.urlFor("other", tallSpace = false)).isEqualTo(found.square)
    }

    @Test
    fun `hiding the song cancels its retry, and showing it again starts over`() {
        coEvery { repository.loopFor(song) } throws IOException("503") andThen found
        val viewModel = viewModel()

        viewModel.load(song)
        advanceTo(10_000)
        viewModel.stopLookup()
        advanceTo(100_000)
        coVerify(exactly = 1) { repository.loopFor(song) }

        viewModel.load(song)
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 2) { repository.loopFor(song) }
        assertThat(viewModel.loop.value?.urlFor("song", tallSpace = false)).isEqualTo(found.square)
    }

    @Test
    fun `hiding the song keeps a loop it already found`() {
        coEvery { repository.loopFor(song) } returns found
        val viewModel = viewModel()

        viewModel.load(song)
        testDispatcher.scheduler.runCurrent()
        viewModel.stopLookup()
        viewModel.load(song)
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { repository.loopFor(song) }
        assertThat(viewModel.loop.value?.urlFor("song", tallSpace = false)).isEqualTo(found.square)
    }

    @Test
    fun `Wi-Fi only stops the first lookup and any retry`() {
        wifiOnly.value = true
        every { NetworkState.isOnWifi(any()) } returns false
        val viewModel = viewModel()
        viewModel.load(song)
        advanceTo(100_000)
        coVerify(exactly = 0) { repository.loopFor(any()) }

        coEvery { repository.loopFor(song) } throws IOException("503")
        every { NetworkState.isOnWifi(any()) } returns true
        viewModel.load(song)
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 1) { repository.loopFor(song) }

        every { NetworkState.isOnWifi(any()) } returns false
        testDispatcher.scheduler.advanceTimeBy(100_000)
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 1) { repository.loopFor(song) }
    }
}
