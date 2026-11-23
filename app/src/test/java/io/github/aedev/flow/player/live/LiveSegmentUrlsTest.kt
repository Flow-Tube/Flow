package io.github.aedev.flow.player.live

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

class LiveSegmentUrlsTest {
    private val expireSeconds = 1_795_471_385L
    private val expiresAtMs = expireSeconds * 1_000
    private val scope = TestScope()
    private var now = expiresAtMs - 6 * 3_600_000
    private var refreshes = 0
    private var fresh: Map<Int, String>? =
        mapOf(140 to "https://r2.googlevideo.com/videoplayback?itag=140&expire=${expireSeconds + 21_540}")

    private val urls =
        LiveSegmentUrls(
            urls = mapOf(140 to "https://r1.googlevideo.com/videoplayback?itag=140&expire=$expireSeconds"),
            scope = scope,
            refresh = {
                refreshes++
                fresh
            },
            serverNow = { now },
        )

    @Test
    fun `URLs far from expiry are used as they are`() {
        assertThat(urls.urlFor(140)).contains("r1.")
        scope.advanceUntilIdle()
        assertThat(refreshes).isEqualTo(0)
    }

    @Test
    fun `under twenty minutes from expiry, one refresh replaces them`() {
        now = expiresAtMs - 19 * 60_000
        repeat(5) { urls.urlFor(140) }
        scope.advanceUntilIdle()

        assertThat(refreshes).isEqualTo(1)
        assertThat(urls.urlFor(140)).contains("r2.")
        scope.advanceUntilIdle()
        assertThat(refreshes).isEqualTo(1)
    }

    @Test
    fun `a refused segment refreshes whatever the expiry says`() {
        urls.onRefused()
        scope.advanceUntilIdle()

        assertThat(refreshes).isEqualTo(1)
        assertThat(urls.urlFor(140)).contains("r2.")
    }

    @Test
    fun `a refresh that fails keeps the URLs and can be tried again`() {
        fresh = null
        urls.onRefused()
        scope.advanceUntilIdle()
        assertThat(urls.urlFor(140)).contains("r1.")

        fresh = mapOf(140 to "https://r3.googlevideo.com/videoplayback?itag=140&expire=${expireSeconds + 1}", 999 to "https://unknown")
        urls.onRefused()
        scope.advanceUntilIdle()
        assertThat(refreshes).isEqualTo(2)
        assertThat(urls.urlFor(140)).contains("r3.")
        assertThat(urls.urlFor(999)).isNull()
    }
}
