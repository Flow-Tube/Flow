package io.github.aedev.flow.data.music.artwork

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.innertube.models.Album
import io.github.aedev.flow.innertube.models.Artist
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
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
    private val searches = mutableListOf<String>()
    private var searchAnswer: (String) -> List<SongItem> = { emptyList() }
    private val catalogSearches = mutableListOf<Pair<String, String>>()
    private var catalogAnswer: (String) -> List<CatalogSong> = { emptyList() }
    private var clock = 0L
    private val cache = MemoryCache()
    private val repository =
        AnimatedArtworkRepository(
            fetch = { params ->
                requests += params
                answer(params)
            },
            searchSongs = { query ->
                searches += query
                searchAnswer(query)
            },
            searchCatalog = { term, country ->
                catalogSearches += term to country
                catalogAnswer(term)
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
    fun `a miss from before releases were searched is asked again, but a saved loop is kept`() {
        assertThat(decodeCachedLoop("42||")).isNull()
        assertThat(decodeCachedLoop("42|$loop|")).isEqualTo(CachedLoop(AnimatedLoop(loop, null), 42L))
    }

    private val huaMain =
        MusicTrack(
            videoId = "h",
            title = "Hua Main",
            artist = "Raghav Chaitanya",
            thumbnailUrl = "",
            duration = 278,
            album = "Animal- Hindi",
            artists = listOf(MusicArtist("Raghav Chaitanya", "rc")),
        )

    private fun release(
        id: Long,
        album: String,
        tracks: Int,
    ) = CatalogSong("Hua Main", "Raghav Chaitanya & Pritam", id, album, "Pritam", tracks)

    @Test
    fun `a song whose album has no loop finds the loop on its other release`() =
        runTest {
            catalogAnswer = {
                listOf(
                    release(1727144163, "ANIMAL (Deluxe Edition Album)", 18),
                    release(1718278225, "ANIMAL (Original Motion Picture Soundtrack)", 9),
                )
            }
            answer = { params ->
                when (params["id"]) {
                    null -> ArtworkAnswer("Hua Main", "Raghav Chaitanya & Pritam", albumId = "1727144163")
                    "1718278225" -> ArtworkAnswer("ANIMAL", "Various", loop, tallLoop, "1718278225")
                    else -> ArtworkAnswer("ANIMAL (Deluxe Edition Album)", "Various", albumId = params["id"])
                }
            }

            assertThat(repository.loopFor(huaMain)).isEqualTo(AnimatedLoop(loop, tallLoop))

            assertThat(catalogSearches.single()).isEqualTo("Hua Main Raghav Chaitanya" to "us")
            assertThat(requests.mapNotNull { it["id"] }).containsExactly("1718278225")
            assertThat(requests.last()["storefront"]).isEqualTo("us")
        }

    @Test
    fun `a recommended row with one artist string and no album still finds the loop`() =
        runTest {
            val recommended = huaMain.copy(artist = "Raghav Chaitanya, Pritam, Manoj Muntashir", album = "", artists = emptyList())
            searchAnswer = {
                listOf(
                    SongItem(
                        id = "65-VQkyE72I",
                        title = "Hua Main",
                        artists = listOf(Artist("Raghav Chaitanya", "rc")),
                        album = Album("Animal- Hindi", "a"),
                        duration = 278,
                        thumbnail = "",
                        musicVideoType = MUSIC_VIDEO_TYPE_ATV,
                    ),
                )
            }
            catalogAnswer = { listOf(release(1718278225, "ANIMAL (Original Motion Picture Soundtrack)", 9)) }
            answer = { params ->
                if (params["id"] == "1718278225") {
                    ArtworkAnswer("ANIMAL", "Various", loop, tallLoop, "1718278225")
                } else {
                    ArtworkAnswer("Hua Main", "Pritam, Raghav Chaitanya & Manoj Muntashir", albumId = "1727144163")
                }
            }

            assertThat(repository.loopFor(recommended)).isEqualTo(AnimatedLoop(loop, tallLoop))

            assertThat(requests.first()["a"]).isEqualTo("Raghav Chaitanya")
            assertThat(catalogSearches.single().first).isEqualTo("Hua Main Raghav Chaitanya")
        }

    @Test
    fun `a search row's Unknown Album is not asked about as an album`() =
        runTest {
            answer = { ArtworkAnswer("Anti-Hero", "Taylor Swift", loop) }

            repository.loopFor(antiHero.copy(album = "Unknown Album"))

            assertThat(requests.single()).doesNotContainKey("al")
            assertThat(cache.entries.keys.single()).endsWith("|anti hero")
        }

    @Test
    fun `a song with a loop never searches for other releases`() =
        runTest {
            repository.loopFor(antiHero)

            assertThat(catalogSearches).isEmpty()
        }

    @Test
    fun `a failed release search is not saved as a miss`() =
        runTest {
            answer = { ArtworkAnswer("Anti-Hero", "Taylor Swift") }
            catalogAnswer = { throw IOException("refused") }

            runCatching { repository.loopFor(antiHero) }

            assertThat(cache.entries).isEmpty()
        }

    @Test
    fun `no release with a loop is saved as a miss and not asked about again`() =
        runTest {
            answer = { ArtworkAnswer("Anti-Hero", "Taylor Swift") }

            assertThat(repository.loopFor(antiHero)).isNull()
            repository.loopFor(antiHero)

            assertThat(catalogSearches).hasSize(1)
        }

    private val besharamRangVideo =
        MusicTrack(
            videoId = "huxhqphtDrM",
            title = "Besharam Rang",
            artist = "Shilpa Rao",
            thumbnailUrl = "",
            duration = 194,
            isVideoSong = true,
            artists = listOf(MusicArtist("Shilpa Rao"), MusicArtist("Caralisa Monteiro"), MusicArtist("Vishal Dadlani")),
        )
    private val besharamRangSong =
        SongItem(
            id = "Uo_OSlQZlgY",
            title = "Besharam Rang (From \"Pathaan\")",
            artists = listOf(Artist("Shilpa Rao", null), Artist("Vishal-Shekhar", null), Artist("Caralisa Monteiro", null)),
            album = Album("Pathaan", "pathaan"),
            duration = 259,
            musicVideoType = MUSIC_VIDEO_TYPE_ATV,
            thumbnail = "",
        )

    /** The service as it answered live: no album gets a compilation without a loop, the film's album has both loops. */
    private fun besharamRangService(params: Map<String, String>): ArtworkAnswer =
        if (params["al"] == "Pathaan") {
            ArtworkAnswer(params["s"], "Vishal & Shekhar, Shilpa Rao, Caralisa Monteiro", loop, tallLoop)
        } else {
            ArtworkAnswer("Besharam Rang", "Vishal & Shekhar, Shilpa Rao, Caralisa Monteiro")
        }

    @Test
    fun `a video row with no album takes the loop of its song version's album`() =
        runTest {
            answer = ::besharamRangService
            searchAnswer = { listOf(besharamRangSong) }

            assertThat(repository.loopFor(besharamRangVideo)).isEqualTo(AnimatedLoop(loop, tallLoop))

            assertThat(searches).containsExactly("Besharam Rang Shilpa Rao")
            assertThat(requests.last())
                .containsExactly(
                    "s",
                    "Besharam Rang (From \"Pathaan\")",
                    "a",
                    "Shilpa Rao",
                    "al",
                    "Pathaan",
                    "d",
                    "259",
                    "storefront",
                    "us",
                )
        }

    @Test
    fun `the song version's answer is kept, so one row searches once`() =
        runTest {
            answer = ::besharamRangService
            searchAnswer = { listOf(besharamRangSong) }

            repository.loopFor(besharamRangVideo)
            val requestsAfterFirst = requests.size
            assertThat(repository.loopFor(besharamRangVideo)).isEqualTo(AnimatedLoop(loop, tallLoop))

            assertThat(searches).hasSize(1)
            assertThat(requests).hasSize(requestsAfterFirst)
        }

    @Test
    fun `a video row with no song version is a miss, kept for days`() =
        runTest {
            answer = ::besharamRangService

            assertThat(repository.loopFor(besharamRangVideo)).isNull()
            repository.loopFor(besharamRangVideo)
            assertThat(searches).hasSize(1)

            clock += 4L * 24 * 60 * 60 * 1000
            repository.loopFor(besharamRangVideo)
            assertThat(searches).hasSize(2)
        }

    @Test
    fun `a failed search is not kept as a miss`() =
        runTest {
            answer = ::besharamRangService
            searchAnswer = { throw IOException("no network") }
            runCatching { repository.loopFor(besharamRangVideo) }
            assertThat(cache.entries).isEmpty()

            searchAnswer = { listOf(besharamRangSong) }
            assertThat(repository.loopFor(besharamRangVideo)).isEqualTo(AnimatedLoop(loop, tallLoop))
        }

    @Test
    fun `a service failure on the song version is not kept as a miss`() =
        runTest {
            searchAnswer = { listOf(besharamRangSong) }
            answer = { params -> if (params["al"] == "Pathaan") throw IOException("503") else besharamRangService(params) }

            assertThat(runCatching { repository.loopFor(besharamRangVideo) }.isFailure).isTrue()
            assertThat(cache.entries).isEmpty()
        }

    @Test
    fun `a song row with an album never searches, loop or none`() =
        runTest {
            val albumVersion =
                besharamRangVideo.copy(videoId = "Uo_OSlQZlgY", title = besharamRangSong.title, album = "Pathaan", duration = 259)
            val gehraHua =
                MusicTrack(
                    videoId = "i1o1p_DD6TU",
                    title = "Gehra Hua (From \"Dhurandhar\")",
                    artist = "Shashwat Sachdev",
                    thumbnailUrl = "",
                    duration = 363,
                    album = "Gehra Hua (From \"Dhurandhar\")",
                    artists = listOf(MusicArtist("Shashwat Sachdev"), MusicArtist("Arijit Singh")),
                )

            answer = ::besharamRangService
            assertThat(repository.loopFor(albumVersion)).isEqualTo(AnimatedLoop(loop, tallLoop))
            answer = { ArtworkAnswer("Gehra Hua (From \"Dhurandhar\")", "Shashwat Sachdev, Arijit Singh, Irshad Kamil", loop, tallLoop) }
            assertThat(repository.loopFor(gehraHua)).isEqualTo(AnimatedLoop(loop, tallLoop))
            answer = { ArtworkAnswer("Anti-Hero", "Taylor Swift") }
            assertThat(repository.loopFor(antiHero)).isNull()

            assertThat(searches).isEmpty()
        }

    @Test
    fun `a row with no album whose answer already has a loop does not search`() =
        runTest {
            answer = { ArtworkAnswer("Besharam Rang", "Shilpa Rao", loop) }

            assertThat(repository.loopFor(besharamRangVideo)?.square).isEqualTo(loop)
            assertThat(searches).isEmpty()
        }

    @Test
    fun `the artist channel's Topic suffix is not sent`() {
        val queries = AnimatedArtworkLookup.queries(antiHero.copy(artists = emptyList(), artist = "Taylor Swift - Topic"))

        assertThat(queries.map { it.artist }.distinct()).containsExactly("Taylor Swift")
    }
}
