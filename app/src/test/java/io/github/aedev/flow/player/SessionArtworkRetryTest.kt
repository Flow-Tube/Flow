package io.github.aedev.flow.player

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SessionArtworkRetryTest {
    @Test
    fun `a missing large YouTube thumbnail falls back to hqdefault`() {
        assertThat(sessionArtworkCandidates("https://i.ytimg.com/vi/abc/hq720.jpg"))
            .containsExactly("https://i.ytimg.com/vi/abc/hq720.jpg", "https://i.ytimg.com/vi/abc/hqdefault.jpg")
            .inOrder()
        assertThat(sessionArtworkCandidates("https://i.ytimg.com/vi_webp/abc/maxresdefault.webp"))
            .containsExactly("https://i.ytimg.com/vi_webp/abc/maxresdefault.webp", "https://i.ytimg.com/vi_webp/abc/hqdefault.webp")
            .inOrder()
    }

    @Test
    fun `music artwork and hqdefault have no fallback`() {
        val cover = "https://lh3.googleusercontent.com/abc=w1080-h1080-l90-rj"
        assertThat(sessionArtworkCandidates(cover)).containsExactly(cover)
        assertThat(sessionArtworkCandidates("https://i.ytimg.com/vi/abc/hqdefault.jpg")).hasSize(1)
    }

    @Test
    fun `a failed load is tried again after each delay until it succeeds`() =
        runTest {
            var calls = 0
            val result =
                fetchWithRetries(listOf("art"), listOf(2_000L, 10_000L, 30_000L)) {
                    calls++
                    if (calls < 3) throw IOException("offline") else "bitmap"
                }

            assertThat(result).isEqualTo("bitmap")
            assertThat(calls).isEqualTo(3)
            assertThat(currentTime).isEqualTo(12_000L)
        }

    @Test
    fun `the fallback is tried before waiting`() =
        runTest {
            val tried = mutableListOf<String>()
            val result =
                fetchWithRetries(listOf("hq720", "hqdefault"), listOf(2_000L)) {
                    tried += it
                    if (it == "hq720") throw IOException("404") else it
                }

            assertThat(result).isEqualTo("hqdefault")
            assertThat(tried).containsExactly("hq720", "hqdefault").inOrder()
            assertThat(currentTime).isEqualTo(0L)
        }

    @Test
    fun `it gives up after the last delay`() =
        runTest {
            var calls = 0
            val error =
                runCatching {
                    fetchWithRetries(listOf("art"), listOf(2_000L, 10_000L, 30_000L)) {
                        calls++
                        throw IOException("offline")
                    }
                }.exceptionOrNull()

            assertThat(error).isInstanceOf(IOException::class.java)
            assertThat(calls).isEqualTo(4)
            assertThat(currentTime).isEqualTo(42_000L)
        }
}
