package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.IOException

/** Only a real "nothing here" may read as a miss: a song is then remembered as having no lyrics. */
class LyricsProviderFailuresTest {
    private fun answering(
        status: Int,
        body: String,
    ) = OkHttpClient
        .Builder()
        .addInterceptor { chain ->
            Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("test")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

    private val unreachable =
        OkHttpClient
            .Builder()
            .addInterceptor { throw IOException("no network") }
            .build()

    private val allProviders: List<(OkHttpClient) -> LyricsProvider> =
        listOf(
            ::BetterLyricsProvider,
            ::LrcRedLyricsProvider,
            ::NetEaseLyricsProvider,
            ::AmllLyricsProvider,
            ::LyricsPlusProvider,
            ::SimpMusicLyricsProvider,
            ::KuGouLyricsProvider,
            ::PaxsenixLyricsProvider,
            ::LrcLibLyricsProvider,
            ::UnisonLyricsProvider,
        )

    private suspend fun failureOf(provider: LyricsProvider): Throwable? =
        provider.getLyrics("video", "Zzqxv Song", "Qqzzv Artist", 200, null).exceptionOrNull()

    @Test
    fun `no network is never a miss`() =
        runTest {
            allProviders.forEach { create ->
                val provider = create(unreachable)

                assertThat(failureOf(provider)).isNotNull()
                assertThat(failureOf(provider)).isNotInstanceOf(LyricsNotFoundException::class.java)
            }
        }

    @Test
    fun `a server error is never a miss`() =
        runTest {
            allProviders.forEach { create ->
                val provider = create(answering(503, "unavailable"))

                assertThat(failureOf(provider)).isNotNull()
                assertThat(failureOf(provider)).isNotInstanceOf(LyricsNotFoundException::class.java)
            }
        }

    @Test
    fun `a not found answer is a miss wherever the service sends one`() =
        runTest {
            listOf<(OkHttpClient) -> LyricsProvider>(
                ::BetterLyricsProvider,
                ::LyricsPlusProvider,
                ::SimpMusicLyricsProvider,
                ::LrcLibLyricsProvider,
                ::UnisonLyricsProvider,
            ).forEach { create ->
                assertThat(failureOf(create(answering(404, "{}")))).isInstanceOf(LyricsNotFoundException::class.java)
            }
        }

    @Test
    fun `better lyrics saying the song is uncached is a miss`() =
        runTest {
            val provider = BetterLyricsProvider(answering(401, """{"message":"Uncached queries require a valid API key"}"""))

            assertThat(failureOf(provider)).isInstanceOf(LyricsNotFoundException::class.java)
        }

    @Test
    fun `better lyrics refusing the app is not a miss`() =
        runTest {
            val provider = BetterLyricsProvider(answering(401, """{"message":"Bad key"}"""))

            assertThat(failureOf(provider)).isNotInstanceOf(LyricsNotFoundException::class.java)
            assertThat(LyricsChecks.isAuthFailure(failureOf(provider)?.message)).isTrue()
        }

    @Test
    fun `an empty search result is a miss`() =
        runTest {
            val empty = """{"results":[],"data":{"info":[]},"candidates":[]}"""

            assertThat(failureOf(LrcRedLyricsProvider(answering(200, empty)))).isInstanceOf(LyricsNotFoundException::class.java)
            assertThat(failureOf(KuGouLyricsProvider(answering(200, empty)))).isInstanceOf(LyricsNotFoundException::class.java)
            assertThat(failureOf(PaxsenixLyricsProvider(answering(200, empty)))).isInstanceOf(LyricsNotFoundException::class.java)
        }

    @Test
    fun `simpmusic sending something broken or an error type is not a miss`() =
        runTest {
            listOf("not json {", "\"text\"", """{"type":"error"}""", """{"data":[1]}""").forEach { body ->
                val failure = failureOf(SimpMusicLyricsProvider(answering(200, body)))

                assertThat(failure).isNotNull()
                assertThat(failure).isNotInstanceOf(LyricsNotFoundException::class.java)
            }
        }

    @Test
    fun `simpmusic with no rows is a miss`() =
        runTest {
            val failure = failureOf(SimpMusicLyricsProvider(answering(200, """{"type":"success","data":[]}""")))

            assertThat(failure).isInstanceOf(LyricsNotFoundException::class.java)
        }
}
