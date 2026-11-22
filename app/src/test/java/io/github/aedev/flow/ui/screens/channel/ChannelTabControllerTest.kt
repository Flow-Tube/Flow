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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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

    // The load runs on the real network pool, so the test waits in real time for the first request
    // to arrive and gives a duplicate the same window to show up before counting.
    @Test
    fun `asking for the Home tab again while it loads sends one request`() =
        runBlocking {
            val calls = AtomicInteger()
            val firstCall = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<Result<ChannelTabContent>>()
            coEvery { YouTube.channelTab(any(), any(), any(), any()) } coAnswers {
                calls.incrementAndGet()
                firstCall.complete(Unit)
                answer.await()
            }
            val scope = CoroutineScope(SupervisorJob())
            val controller = ChannelTabController(scope, MutableStateFlow(HiddenContent()))
            controller.reset("UC1", owner)

            controller.ensureLoaded(ChannelTabKind.Home, "EghmZWF0dXJlZPIG")
            controller.ensureLoaded(ChannelTabKind.Home, "EghmZWF0dXJlZPIG")

            withTimeout(5_000) { firstCall.await() }
            delay(300)
            assertThat(calls.get()).isEqualTo(1)
            answer.complete(Result.success(ChannelTabContent(kind = ChannelTabKind.Home, owner = owner)))
            scope.cancel()
        }
}
