package io.github.aedev.flow.innertube

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.YouTubeClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * The WEB client the search, Shorts and channel requests are sent as: YouTube's current WEB
 * version when it can be read, else the version the app ships with, and nothing else changed.
 */
class CurrentWebClientTest {
    @After
    fun tearDown() {
        WebClientVersionStub.reset()
    }

    private fun currentWebClient(): YouTubeClient =
        runBlocking {
            val method = YouTube::class.java.getDeclaredMethod("currentWebClient", Continuation::class.java).apply { isAccessible = true }
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation -> method.invoke(YouTube, continuation) } as YouTubeClient
        }

    @Test
    fun `the WEB client carries the version YouTube currently serves`() {
        WebClientVersionStub.answer("2.20261116.01.00")

        assertThat(currentWebClient()).isEqualTo(YouTubeClient.WEB.copy(clientVersion = "2.20261116.01.00"))
    }

    @Test
    fun `when the version cannot be read the shipped WEB version is used`() {
        WebClientVersionStub.fail()

        assertThat(currentWebClient()).isEqualTo(YouTubeClient.WEB)
        assertThat(currentWebClient().clientVersion).isEqualTo("2.20261123.08.00")
    }
}
