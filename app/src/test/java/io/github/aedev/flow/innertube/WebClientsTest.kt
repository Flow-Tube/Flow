package io.github.aedev.flow.innertube

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.YouTubeClient
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Each web client is read from its own site's worker; every other client goes out as pinned. */
class WebClientsTest {
    private val asked = mutableListOf<Pair<String, String>>()

    private val workers =
        mapOf(
            "https://www.youtube.com" to worker("WEB", "2.20261201.00.00"),
            "https://m.youtube.com" to worker("MWEB", "2.20261201.01.00"),
            "https://music.youtube.com" to worker("WEB_REMIX", "1.20261201.02.00"),
        )

    private fun worker(
        name: String,
        version: String,
    ) = """self.ytcfg={"INNERTUBE_CLIENT_NAME":"$name","INNERTUBE_CONTEXT_CLIENT_VERSION":"$version"}"""

    private val clients =
        WebClients { origin, userAgent ->
            asked += origin to userAgent
            workers.getValue(origin)
        }

    @Test
    fun `each web client carries the version its own site serves`() =
        runTest {
            assertThat(clients.live(YouTubeClient.WEB)).isEqualTo(YouTubeClient.WEB.copy(clientVersion = "2.20261201.00.00"))
            assertThat(clients.live(YouTubeClient.MWEB)).isEqualTo(YouTubeClient.MWEB.copy(clientVersion = "2.20261201.01.00"))
            assertThat(clients.live(YouTubeClient.WEB_REMIX))
                .isEqualTo(YouTubeClient.WEB_REMIX.copy(clientVersion = "1.20261201.02.00"))
        }

    @Test
    fun `each site is asked once, as the browser its client claims to be`() =
        runTest {
            repeat(3) {
                clients.live(YouTubeClient.WEB)
                clients.live(YouTubeClient.MWEB)
                clients.live(YouTubeClient.WEB_REMIX)
            }

            assertThat(asked)
                .containsExactly(
                    "https://www.youtube.com" to YouTubeClient.WEB.userAgent,
                    "https://m.youtube.com" to YouTubeClient.MWEB.userAgent,
                    "https://music.youtube.com" to YouTubeClient.WEB_REMIX.userAgent,
                ).inOrder()
        }

    @Test
    fun `a client changed for one request keeps its other fields`() =
        runTest {
            val shifted = YouTubeClient.WEB.copy(originalUrl = "https://www.youtube.com/watch")

            assertThat(clients.live(shifted)).isEqualTo(shifted.copy(clientVersion = "2.20261201.00.00"))
        }

    @Test
    fun `app and TV clients are never rewritten`() =
        runTest {
            listOf(YouTubeClient.ANDROID, YouTubeClient.IOS, YouTubeClient.TVHTML5, YouTubeClient.WEB_CREATOR, YouTubeClient.VISIONOS)
                .forEach { assertThat(clients.live(it)).isSameInstanceAs(it) }
            assertThat(asked).isEmpty()
        }

    @Test
    fun `a site that cannot be read leaves only its own client pinned`() =
        runTest {
            val partial =
                WebClients { origin, _ ->
                    if (origin == "https://m.youtube.com") throw java.io.IOException("offline")
                    workers.getValue(origin)
                }

            assertThat(partial.live(YouTubeClient.MWEB)).isEqualTo(YouTubeClient.MWEB)
            assertThat(partial.live(YouTubeClient.WEB).clientVersion).isEqualTo("2.20261201.00.00")
        }

    @Test
    fun `a site answering for another client is not trusted`() =
        runTest {
            val redirected = WebClients { _, _ -> workers.getValue("https://www.youtube.com") }

            assertThat(redirected.live(YouTubeClient.MWEB)).isEqualTo(YouTubeClient.MWEB)
        }

    @Test
    fun `the version in use is known without a fetch`() =
        runTest {
            assertThat(clients.known(YouTubeClient.WEB)).isEqualTo(YouTubeClient.WEB.clientVersion)
            clients.live(YouTubeClient.WEB)
            assertThat(clients.known(YouTubeClient.WEB)).isEqualTo("2.20261201.00.00")
            assertThat(asked).hasSize(1)
        }

    @Test
    fun `a first music request goes out pinned while the version is read behind it`() =
        runTest {
            val lazy =
                WebClients(
                    fetchServiceWorker = { origin, userAgent ->
                        asked += origin to userAgent
                        workers.getValue(origin)
                    },
                    background = this,
                )

            assertThat(lazy.current(YouTubeClient.WEB_REMIX)).isEqualTo(YouTubeClient.WEB_REMIX)
            assertThat(lazy.current(YouTubeClient.WEB_REMIX)).isEqualTo(YouTubeClient.WEB_REMIX)
            advanceUntilIdle()

            assertThat(lazy.current(YouTubeClient.WEB_REMIX).clientVersion).isEqualTo("1.20261201.02.00")
            assertThat(asked).containsExactly("https://music.youtube.com" to YouTubeClient.WEB_REMIX.userAgent)
        }
}
