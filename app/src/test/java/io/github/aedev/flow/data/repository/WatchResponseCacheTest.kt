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
    private var nowMs = 0L
    private var locale = "en-US"

    private fun TestScope.cache(gate: CompletableDeferred<Unit>? = null) =
        WatchResponseCache<String>(
            capacity = 3,
            scope = backgroundScope,
            now = { nowMs },
            identity = { locale },
        ) { videoId ->
            gate?.await()
            fetched += videoId
            "response:$videoId@$nowMs"
        }

    @Test
    fun `feed lookups never evict what the player holds`() =
        runTest {
            val cache = cache()
            listOf("a", "b", "c").forEach { cache.forPlayer(it) }

            listOf("d", "e", "f", "g").forEach { cache.forFeed(it) }

            assertThat(cache.cached("a")).isEqualTo("response:a@0")
            assertThat(cache.cached("b")).isEqualTo("response:b@0")
            assertThat(cache.cached("c")).isEqualTo("response:c@0")
            assertThat(cache.cached("d")).isNull()
        }

    @Test
    fun `a feed lookup reuses the player's entry without fetching`() =
        runTest {
            val cache = cache()
            cache.forPlayer("a")

            assertThat(cache.forFeed("a")).isEqualTo("response:a@0")
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

            assertThat(feed.await()).isEqualTo("response:a@0")
            assertThat(player.await()).isEqualTo("response:a@0")
            assertThat(fetched).containsExactly("a")
            assertThat(cache.cached("a")).isEqualTo("response:a@0")
        }

    @Test
    fun `a response is reused within its age limit`() =
        runTest {
            val cache = cache()
            cache.forPlayer("a")
            nowMs = WatchResponseCache.MAX_AGE_MS - 1

            assertThat(cache.forPlayer("a")).isEqualTo("response:a@0")
            assertThat(fetched).containsExactly("a")
        }

    @Test
    fun `replaying a video after the age limit fetches it again`() =
        runTest {
            val cache = cache()
            cache.forPlayer("a")
            nowMs = WatchResponseCache.MAX_AGE_MS

            assertThat(cache.cached("a")).isNull()
            assertThat(cache.forPlayer("a")).isEqualTo("response:a@${WatchResponseCache.MAX_AGE_MS}")
            assertThat(fetched).containsExactly("a", "a").inOrder()
            assertThat(cache.size()).isEqualTo(1)
        }

    @Test
    fun `a language change misses the old response`() =
        runTest {
            val cache = cache()
            cache.forPlayer("a")
            locale = "de-DE"

            assertThat(cache.cached("a")).isNull()
            cache.forPlayer("a")
            assertThat(fetched).containsExactly("a", "a").inOrder()
            assertThat(cache.cached("a")).isEqualTo("response:a@0")
        }

    @Test
    fun `a small heap keeps only the latest video`() =
        runTest {
            val cache = cache()
            cache.resize(1)

            listOf("a", "b").forEach { cache.forPlayer(it) }

            assertThat(cache.size()).isEqualTo(1)
            assertThat(cache.cached("a")).isNull()
            assertThat(cache.cached("b")).isEqualTo("response:b@0")
        }

    @Test
    fun `trimming keeps the playing video's response and drops the rest`() =
        runTest {
            val cache = cache()
            listOf("a", "b", "c").forEach { cache.forPlayer(it) }

            cache.retainOnly("b")

            assertThat(cache.size()).isEqualTo(1)
            assertThat(cache.cached("b")).isEqualTo("response:b@0")
        }

    @Test
    fun `trimming with nothing playing empties the cache`() =
        runTest {
            val cache = cache()
            listOf("a", "b").forEach { cache.forPlayer(it) }

            cache.retainOnly(null)

            assertThat(cache.size()).isEqualTo(0)
        }
}
