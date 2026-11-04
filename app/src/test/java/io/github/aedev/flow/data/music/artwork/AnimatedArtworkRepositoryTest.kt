package io.github.aedev.flow.data.music.artwork

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class AnimatedArtworkRepositoryTest {
    private class MemoryCache : AnimatedArtworkCache {
        val entries = mutableMapOf<String, CachedLoop>()

        override suspend fun get(key: String) = entries[key]

        override suspend fun put(
            key: String,
            loop: CachedLoop,
        ) {
            entries[key] = loop
        }

        override suspend fun remove(key: String) {
            entries.remove(key)
        }
    }

    private val antiHero =
        MusicTrack(
            videoId = "a",
            title = "Anti-Hero",
            artist = "Taylor Swift",
            thumbnailUrl = "",
            duration = 201,
            album = "Midnights",
            artists = listOf(MusicArtist("Taylor Swift", "ts")),
        )
    private val loop = "https://mvod.itunes.apple.com/itunes-assets/loop.m3u8"
    private val tallLoop = "https://mvod.itunes.apple.com/itunes-assets/tall.m3u8"
    private val requests = mutableListOf<Map<String, String>>()
    private var answer: (Map<String, String>) -> ArtworkAnswer = { ArtworkAnswer("Anti-Hero", "Taylor Swift", loop) }
    private var clock = 0L
    private val cache = MemoryCache()
    private val repository =
        AnimatedArtworkRepository(
            fetch = { params ->
                requests += params
                answer(params)
            },
            cache = cache,
            storefront = { "US" },
            now = { clock },
        )

    @Test
    fun `a song is asked for by title, artist, album and length in the viewer's storefront`() =
        runTest {
            assertThat(repository.loopFor(antiHero)?.square).isEqualTo(loop)

            assertThat(requests.single())
                .containsExactly("s", "Anti-Hero", "a", "Taylor Swift", "al", "Midnights", "d", "201", "storefront", "us")
        }

    @Test
    fun `songs of one album share a single lookup until it is stale`() =
        runTest {
            repository.loopFor(antiHero)
            repository.loopFor(antiHero.copy(videoId = "b", title = "Lavender Haze"))
            assertThat(requests).hasSize(1)

            clock += 15L * 24 * 60 * 60 * 1000
            repository.loopFor(antiHero)
            assertThat(requests).hasSize(2)
        }

    @Test
    fun `an album without a loop is remembered too, for less time`() =
        runTest {
            answer = { ArtworkAnswer("Anti-Hero", "Taylor Swift", null) }
            assertThat(repository.loopFor(antiHero)).isNull()
            repository.loopFor(antiHero)
            assertThat(requests).hasSize(1)

            clock += 4L * 24 * 60 * 60 * 1000
            repository.loopFor(antiHero)
            assertThat(requests).hasSize(2)
        }

    @Test
    fun `an answer naming another song is not trusted, and the plain title is tried next`() =
        runTest {
            val featured = antiHero.copy(title = "Anti-Hero (feat. Bleachers)")
            answer = { params ->
                if (params["s"] ==
                    "Anti-Hero"
                ) {
                    ArtworkAnswer("Anti-Hero", "Taylor Swift", loop)
                } else {
                    ArtworkAnswer("Karma", "Taylor Swift", "other")
                }
            }

            assertThat(repository.loopFor(featured)?.square).isEqualTo(loop)
            assertThat(requests.map { it["s"] }).containsExactly("Anti-Hero (feat. Bleachers)", "Anti-Hero").inOrder()
        }

    @Test
    fun `an unknown song is a miss, a failed request is not`() =
        runTest {
            answer = { ArtworkAnswer() }
            assertThat(repository.loopFor(antiHero)).isNull()
            assertThat(cache.entries).isNotEmpty()

            cache.entries.clear()
            answer = { throw IOException("429") }
            runCatching { repository.loopFor(antiHero) }
            assertThat(cache.entries).isEmpty()
        }

    @Test
    fun `a loop that stopped playing is forgotten and asked for again`() =
        runTest {
            repository.loopFor(antiHero)
            repository.forget(antiHero)
            repository.loopFor(antiHero)

            assertThat(requests).hasSize(2)
        }

    @Test
    fun `an album's tall loop is kept beside its square one`() =
        runTest {
            answer = { ArtworkAnswer("Anti-Hero", "Taylor Swift", loop, tallLoop) }

            assertThat(repository.loopFor(antiHero)).isEqualTo(AnimatedLoop(loop, tallLoop))
            assertThat(repository.loopFor(antiHero)).isEqualTo(AnimatedLoop(loop, tallLoop))
            assertThat(requests).hasSize(1)
        }

    @Test
    fun `an album with only a tall loop still counts as having one`() =
        runTest {
            answer = { ArtworkAnswer("Anti-Hero", "Taylor Swift", null, tallLoop) }

            assertThat(repository.loopFor(antiHero)).isEqualTo(AnimatedLoop(null, tallLoop))
        }

    @Test
    fun `a tall space takes the tall loop, then the square one`() {
        assertThat(AnimatedLoop(loop, tallLoop).urlFor(tallSpace = true)).isEqualTo(tallLoop)
        assertThat(AnimatedLoop(loop, null).urlFor(tallSpace = true)).isEqualTo(loop)
        assertThat(AnimatedLoop(loop, tallLoop).urlFor(tallSpace = false)).isEqualTo(loop)
        assertThat(AnimatedLoop(null, tallLoop).urlFor(tallSpace = false)).isNull()
    }

    @Test
    fun `a stored answer reads back as it was written`() {
        val found = CachedLoop(AnimatedLoop(loop, tallLoop), 42L)
        val missing = CachedLoop(null, 42L)

        assertThat(decodeCachedLoop(found.encoded())).isEqualTo(found)
        assertThat(decodeCachedLoop(missing.encoded())).isEqualTo(missing)
    }

    @Test
    fun `an entry stored before tall loops is a miss, so the album is asked again`() {
        assertThat(decodeCachedLoop("42|$loop")).isNull()
        assertThat(decodeCachedLoop("42|")).isNull()
    }

    @Test
    fun `the artist channel's Topic suffix is not sent`() {
        val queries = AnimatedArtworkLookup.queries(antiHero.copy(artists = emptyList(), artist = "Taylor Swift - Topic"))

        assertThat(queries.map { it.artist }.distinct()).containsExactly("Taylor Swift")
    }
}
