package io.github.aedev.flow.ui.screens.channel

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.engagement.HiddenContent
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.channel.ChannelTabContent
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class ChannelTabControllerTest {
    private val owner = FeedItemOwner(id = "UC1")

    @Before
    fun setUp() {
        mockkObject(YouTube)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `the landing response fills the Home tab without a request of its own`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = ChannelTabController(backgroundScope, MutableStateFlow(HiddenContent()))
            controller.reset("UC1", owner)

            controller.seedSections(ChannelTabKind.Home, ChannelTabContent(kind = ChannelTabKind.Home, owner = owner))
            controller.ensureLoaded(ChannelTabKind.Home, "EghmZWF0dXJlZPIG")

            assertThat(controller.states.value[ChannelTabKind.Home]?.loaded).isTrue()
            coVerify(exactly = 0) { YouTube.channelTab(any(), any(), any(), any()) }
        }

    @Test
    fun `asking for the Home tab again while it loads sends one request`() =
        runTest(UnconfinedTestDispatcher()) {
            val answer = CompletableDeferred<Result<ChannelTabContent>>()
            coEvery { YouTube.channelTab(any(), any(), any(), any()) } coAnswers { answer.await() }
            val controller = ChannelTabController(backgroundScope, MutableStateFlow(HiddenContent()))
            controller.reset("UC1", owner)

            controller.ensureLoaded(ChannelTabKind.Home, "EghmZWF0dXJlZPIG")
            controller.ensureLoaded(ChannelTabKind.Home, "EghmZWF0dXJlZPIG")

            coVerify(exactly = 1) { YouTube.channelTab(any(), any(), any(), any()) }
            answer.complete(Result.success(ChannelTabContent(kind = ChannelTabKind.Home, owner = owner)))
        }
}
