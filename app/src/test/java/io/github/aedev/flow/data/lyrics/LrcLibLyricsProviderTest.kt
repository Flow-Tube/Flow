package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class LrcLibLyricsProviderTest {
    private fun result(
        track: String,
        artist: String,
        seconds: Double?,
        synced: Boolean = true,
    ) = LrcLibLyricsProvider.SearchResult(
        trackName = track,
        artistName = artist,
        duration = seconds,
        syncedLyrics = "[00:01.00]line".takeIf { synced },
        plainLyrics = "line",
    )

    @Test
    fun `a tribute version much shorter than the song is refused`() {
        val request = LyricsMatching.Song("Teri Mitti", "Arko", 314)

        assertThat(LrcLibLyricsProvider.pick(listOf(result("Teri Mitti - Tribute", "B Praak & Arko", 242.0)), request)).isNull()
    }

    @Test
    fun `another song is refused whatever its length`() {
        val request = LyricsMatching.Song("Teri Mitti", "Arko", 314)

        assertThat(LrcLibLyricsProvider.pick(listOf(result("Malang", "Arko", 314.0)), request)).isNull()
    }

    @Test
    fun `another artist's song of the same name is refused`() {
        val request = LyricsMatching.Song("Teri Mitti", "Arko", 314)

        assertThat(LrcLibLyricsProvider.pick(listOf(result("Teri Mitti", "Someone Else", 314.0)), request)).isNull()
    }

    @Test
    fun `a synced match beats a closer plain one`() {
        val request = LyricsMatching.Song("Teri Mitti", "Arko", 314)
        val plain = result("Teri Mitti", "Arko", 314.0, synced = false)
        val synced = result("Teri Mitti", "Arko feat. B Praak", 318.0)

        assertThat(LrcLibLyricsProvider.pick(listOf(plain, synced), request)).isEqualTo(synced)
    }

    @Test
    fun `a plain match is kept when no synced match exists`() {
        val request = LyricsMatching.Song("Teri Mitti", "Arko", 314)
        val plain = result("Teri Mitti", "Arko feat. B Praak, Akshay Kumar & Parineeti Chopra", 314.0, synced = false)

        assertThat(LrcLibLyricsProvider.pick(listOf(result("Teri Mitti - Tribute", "B Praak & Arko", 242.0), plain), request))
            .isEqualTo(plain)
    }

    @Test
    fun `a row with no length can still match on its names`() {
        val request = LyricsMatching.Song("Teri Mitti", "Arko", 314)
        val noLength = result("Teri Mitti", "Arko", null)

        assertThat(LrcLibLyricsProvider.pick(listOf(noLength), request)).isEqualTo(noLength)
    }

    @Test
    fun `the search names the first artist and the check accepts any credit`() =
        runTest {
            val urls = mutableListOf<String>()
            val lyrics = "[00:01.00]one\\n[00:30.00]two"
            val row = """[{"trackName":"Song","artistName":"Arijit Singh, Shilpa Rao","duration":200,"syncedLyrics":"$lyrics"}]"""
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        urls += chain.request().url.toString()
                        val search = "/api/search" in chain.request().url.encodedPath
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(if (search) 200 else 404)
                            .message("test")
                            .body((if (search) row else "{}").toResponseBody("application/json".toMediaType()))
                            .build()
                    }.build()

            val entries = LrcLibLyricsProvider(client).getLyrics("v", "Song", "Vishal & Shekhar, Arijit Singh, Shilpa Rao", 200, null)

            assertThat(entries.getOrNull()?.first()?.text).isEqualTo("one")
            assertThat(urls.first()).contains("artist_name=Vishal&")
            assertThat(urls.last()).contains("q=Vishal+Song")
        }
}
