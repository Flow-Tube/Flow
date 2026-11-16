package io.github.aedev.flow.data.newmusic

import io.github.aedev.flow.data.music.model.MusicArtist
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicArtistLookupTest {
    private val fonsi = MusicArtist("Luis Fonsi", "UCk91oFc2hY2CdHirU93baLg")
    private val yankee = MusicArtist("Daddy Yankee", "UCXRloA7YFgCfd6sU2_Y12cA")

    @Test
    fun `artists without an id are left out`() =
        runTest {
            val lookup = MusicArtistLookup { listOf(fonsi, MusicArtist("Nobody"), yankee) }

            assertEquals(listOf(fonsi, yankee), lookup.artistsOf("kJQP7kiw5Fk"))
        }

    @Test
    fun `a song is looked up once however often it is asked for`() =
        runTest {
            var calls = 0
            val lookup =
                MusicArtistLookup {
                    calls++
                    delay(100)
                    listOf(fonsi, yankee)
                }

            listOf(async { lookup.artistsOf("a") }, async { lookup.artistsOf("a") }).awaitAll()
            lookup.artistsOf("a")

            assertEquals(1, calls)
        }

    @Test
    fun `an empty answer is asked again next time`() =
        runTest {
            var calls = 0
            val lookup =
                MusicArtistLookup {
                    calls++
                    if (calls == 1) emptyList() else listOf(fonsi, yankee)
                }

            assertEquals(emptyList<MusicArtist>(), lookup.artistsOf("a"))
            assertEquals(listOf(fonsi, yankee), lookup.artistsOf("a"))
        }

    @Test
    fun `a lookup that takes too long gives nothing`() =
        runTest {
            val lookup =
                MusicArtistLookup {
                    delay(60_000)
                    listOf(fonsi)
                }

            assertEquals(emptyList<MusicArtist>(), lookup.artistsOf("a"))
        }
}
