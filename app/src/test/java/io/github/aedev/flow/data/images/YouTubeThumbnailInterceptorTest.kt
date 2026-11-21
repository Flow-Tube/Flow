package io.github.aedev.flow.data.images

import android.content.Context
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.network.NetworkResponse
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Size
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.ThumbnailQuality
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class YouTubeThumbnailInterceptorTest {
    private val context = mockk<Context>(relaxed = true)
    private val id = "jNQXAC9IVRw"
    private val fullWidthCard = Size(1080, 608)
    private val row = Size(462, 260)

    private fun url(name: String) = "https://i.ytimg.com/vi/$id/$name.jpg"

    private fun webp(name: String) = "https://i.ytimg.com/vi_webp/$id/$name.webp"

    private fun request(data: Any) = ImageRequest.Builder(context).data(data).build()

    private inner class FakeChain(
        override val request: ImageRequest,
        override val size: Size,
        private val answer: (String) -> ImageResult?,
        val fetched: MutableList<Any> = mutableListOf(),
    ) : Interceptor.Chain {
        override fun withRequest(request: ImageRequest) = FakeChain(request, size, answer, fetched)

        override fun withSize(size: Size) = FakeChain(request, size, answer, fetched)

        override suspend fun proceed(): ImageResult {
            fetched += request.data
            return answer(request.data as String) ?: SuccessResult(mockk(), request, DataSource.NETWORK)
        }
    }

    private fun notFound(request: ImageRequest) = ErrorResult(null, request, HttpException(NetworkResponse(code = 404)))

    private fun chain(
        size: Size,
        missing: Set<String> = emptySet(),
        quality: ThumbnailQuality = ThumbnailQuality.HIGH,
        offline: Boolean = false,
    ): FakeChain {
        val start = request(YouTubeThumbnail(id, quality))
        return FakeChain(start, size, { url ->
            when {
                offline -> ErrorResult(null, start, IOException("offline"))
                url in missing -> notFound(start)
                else -> null
            }
        })
    }

    @Test
    fun `a row thumbnail asks only for hqdefault`() =
        runTest {
            val chain = chain(row)

            val result = YouTubeThumbnailInterceptor().intercept(chain)

            assertThat(result).isInstanceOf(SuccessResult::class.java)
            assertThat(chain.fetched).containsExactly(webp("hqdefault"))
        }

    @Test
    fun `a missing tier falls through inside one load and is never asked for again`() =
        runTest {
            val interceptor = YouTubeThumbnailInterceptor()
            val missing = setOf(webp("hq720"), url("hq720"))
            val first = chain(fullWidthCard, missing)
            val second = chain(fullWidthCard, missing)

            assertThat(interceptor.intercept(first)).isInstanceOf(SuccessResult::class.java)
            interceptor.intercept(second)

            assertThat(first.fetched).containsExactly(webp("hq720"), url("hq720"), webp("hqdefault")).inOrder()
            assertThat(second.fetched).containsExactly(webp("hqdefault"))
        }

    @Test
    fun `a failure other than 404 is not remembered`() =
        runTest {
            val interceptor = YouTubeThumbnailInterceptor()
            val offline = chain(fullWidthCard, offline = true)
            val online = chain(fullWidthCard)

            assertThat(interceptor.intercept(offline)).isInstanceOf(ErrorResult::class.java)
            interceptor.intercept(online)

            assertThat(offline.fetched)
                .containsExactly(webp("hq720"), url("hq720"), webp("hqdefault"), url("hqdefault"))
                .inOrder()
            assertThat(online.fetched).containsExactly(webp("hq720"))
        }

    @Test
    fun `the last tier is still asked for after it once went missing`() =
        runTest {
            val interceptor = YouTubeThumbnailInterceptor()
            interceptor.intercept(chain(row, missing = setOf(webp("hqdefault"), url("hqdefault"))))
            val retry = chain(row)

            interceptor.intercept(retry)

            assertThat(retry.fetched).containsExactly(url("hqdefault"))
        }

    @Test
    fun `an unbounded size loads the quality ceiling`() =
        runTest {
            val original = chain(Size.ORIGINAL, quality = ThumbnailQuality.MEDIUM)

            YouTubeThumbnailInterceptor().intercept(original)

            assertThat(original.fetched.first()).isEqualTo(webp("sddefault"))
        }

    @Test
    fun `other images pass through untouched`() =
        runTest {
            val plain = "https://example.com/art.png"
            val chain = FakeChain(request(plain), row, { null })

            YouTubeThumbnailInterceptor().intercept(chain)

            assertThat(chain.fetched).containsExactly(plain)
        }
}
