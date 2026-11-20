package io.github.aedev.flow.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WatchResponseCacheTest {
    private val fetched = mutableListOf<String>()

    private fun TestScope.cache(gate: CompletableDeferred<Unit>? = null) =
        WatchResponseCache<String>(capacity = 3, scope = backgroundScope) { videoId ->
            gate?.await()
            fetched += videoId
            "response:$videoId"
        }

    @Test
    fun `feed lookups never evict what the player holds`() =
        runTest {
            val cache = cache()
            listOf("a", "b", "c").forEach { cache.forPlayer(it) }

            listOf("d", "e", "f", "g").forEach { cache.forFeed(it) }

            assertThat(cache.cached("a")).isEqualTo("response:a")
            assertThat(cache.cached("b")).isEqualTo("response:b")
            assertThat(cache.cached("c")).isEqualTo("response:c")
            assertThat(cache.cached("d")).isNull()
        }

    @Test
    fun `a feed lookup reuses the player's entry without fetching`() =
        runTest {
            val cache = cache()
            cache.forPlayer("a")

            assertThat(cache.forFeed("a")).isEqualTo("response:a")
            assertThat(fetched).containsExactly("a")
        }

    @Test
    fun `a player read joining a feed fetch stores the shared answer`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val cache = cache(gate)
            val feed = async { cache.forFeed("a") }
            val player = async { cache.forPlayer("a") }
            runCurrent()
            gate.complete(Unit)

            assertThat(feed.await()).isEqualTo("response:a")
            assertThat(player.await()).isEqualTo("response:a")
            assertThat(fetched).containsExactly("a")
            assertThat(cache.cached("a")).isEqualTo("response:a")
        }
}
