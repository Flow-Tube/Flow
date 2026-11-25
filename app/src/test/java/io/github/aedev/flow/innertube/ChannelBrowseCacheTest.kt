package io.github.aedev.flow.innertube

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.YouTubeLocale
import io.github.aedev.flow.innertube.models.responseMaxAgeSeconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ChannelBrowseCacheTest {
    private var nowMs = 0L
    private val cache = ChannelBrowseCache(capacity = 3) { nowMs }
    private var requests = 0

    private val english = YouTubeLocale(gl = "US", hl = "en")

    private fun key(
        browseId: String = "UCchannel",
        params: String? = null,
        locale: YouTubeLocale = english,
        visitorData: String? = "visitor",
    ) = ChannelBrowseCache.Key(browseId, params, continuation = null, locale = locale, visitorData = visitorData)

    private fun body(maxAgeSeconds: Int?): suspend () -> String =
        {
            requests++
            val context = maxAgeSeconds?.let { """"responseContext":{"maxAgeSeconds":$it},""" }.orEmpty()
            """{$context"request":$requests}"""
        }

    private suspend fun read(
        key: ChannelBrowseCache.Key,
        maxAgeSeconds: Int? = 300,
    ): Int =
        cache
            .read(key, body(maxAgeSeconds))
            .jsonObject["request"]!!
            .jsonPrimitive.content
            .toInt()

    @Test
    fun `reopening a channel within its max age sends nothing`() =
        runTest {
            assertThat(read(key())).isEqualTo(1)
            nowMs = 299_999L
            assertThat(read(key())).isEqualTo(1)
            assertThat(requests).isEqualTo(1)
        }

    @Test
    fun `a response past its max age is fetched again`() =
        runTest {
            read(key())
            nowMs = 300_000L
            assertThat(read(key())).isEqualTo(2)
        }

    @Test
    fun `a response that names no lifetime is never kept`() =
        runTest {
            read(key(), maxAgeSeconds = null)
            read(key(), maxAgeSeconds = null)
            read(key("UCother"), maxAgeSeconds = 0)
            read(key("UCother"), maxAgeSeconds = 0)
            assertThat(requests).isEqualTo(4)
        }

    @Test
    fun `a language, region or visitor change misses the old response`() =
        runTest {
            read(key())
            assertThat(read(key(locale = YouTubeLocale(gl = "US", hl = "de")))).isEqualTo(2)
            assertThat(read(key(locale = YouTubeLocale(gl = "FR", hl = "en")))).isEqualTo(3)
            assertThat(read(key())).isEqualTo(1)
            assertThat(read(key(visitorData = "another"))).isEqualTo(4)
        }

    @Test
    fun `each tab is its own entry`() =
        runTest {
            read(key())
            assertThat(read(key(params = "posts"))).isEqualTo(2)
            assertThat(read(key(params = "posts"))).isEqualTo(2)
        }

    @Test
    fun `the oldest channel goes once the cache is full`() =
        runTest {
            read(key("UC1"))
            read(key("UC2"))
            read(key("UC3"))
            read(key("UC4"))
            assertThat(read(key("UC1"))).isEqualTo(5)
            assertThat(read(key("UC4"))).isEqualTo(4)
        }

    @Test
    fun `max age is read from the response context only`() {
        fun maxAge(json: String) = Json.parseToJsonElement(json).responseMaxAgeSeconds()

        assertThat(maxAge("""{"responseContext":{"maxAgeSeconds":300}}""")).isEqualTo(300)
        assertThat(maxAge("""{"responseContext":{"maxAgeSeconds":0}}""")).isNull()
        assertThat(maxAge("""{"responseContext":{}}""")).isNull()
        assertThat(maxAge("""{"contents":{"maxAgeSeconds":300}}""")).isNull()
        assertThat(maxAge("""{"responseContext":{"maxAgeSeconds":"soon"}}""")).isNull()
        assertThat(maxAge("""[]""")).isNull()
    }
}
