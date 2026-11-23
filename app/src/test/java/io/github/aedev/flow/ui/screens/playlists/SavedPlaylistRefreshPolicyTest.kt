package io.github.aedev.flow.ui.screens.playlists

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SavedPlaylistRefreshPolicyTest {
    private val now = Instant.parse("2026-11-23T12:00:00Z")
    private val saved = listOf("a", "b", "c", "d")

    @Test
    fun `a check younger than six hours is fresh`() {
        assertThat(isSavedCopyFresh(now.minus(Duration.ofHours(5)), now)).isTrue()
    }

    @Test
    fun `a check six hours old or older is stale`() {
        assertThat(isSavedCopyFresh(now.minus(Duration.ofHours(6)), now)).isFalse()
        assertThat(isSavedCopyFresh(now.minus(Duration.ofDays(2)), now)).isFalse()
    }

    @Test
    fun `a playlist never checked is stale`() {
        assertThat(isSavedCopyFresh(null, now)).isFalse()
    }

    @Test
    fun `a check stamped in the future is not trusted`() {
        assertThat(isSavedCopyFresh(now.plus(Duration.ofHours(1)), now)).isFalse()
    }

    @Test
    fun `same first videos and same count match`() {
        assertThat(firstPageMatchesSavedCopy(saved, listOf("a", "b"), hasMorePages = true, headerCount = 4, lastHeaderCount = null))
            .isTrue()
    }

    @Test
    fun `a different count needs the whole list`() {
        assertThat(firstPageMatchesSavedCopy(saved, listOf("a", "b"), hasMorePages = true, headerCount = 5, lastHeaderCount = null))
            .isFalse()
    }

    @Test
    fun `the count the header gave last time matches, since it includes unavailable videos`() {
        assertThat(firstPageMatchesSavedCopy(saved, listOf("a", "b"), hasMorePages = true, headerCount = 6, lastHeaderCount = 6))
            .isTrue()
    }

    @Test
    fun `an unknown header count needs the whole list`() {
        assertThat(firstPageMatchesSavedCopy(saved, listOf("a", "b"), hasMorePages = true, headerCount = null, lastHeaderCount = null))
            .isFalse()
    }

    @Test
    fun `a reordered or new first video needs the whole list`() {
        assertThat(firstPageMatchesSavedCopy(saved, listOf("b", "a"), hasMorePages = true, headerCount = 4, lastHeaderCount = null))
            .isFalse()
        assertThat(firstPageMatchesSavedCopy(saved, listOf("new", "a"), hasMorePages = true, headerCount = 4, lastHeaderCount = null))
            .isFalse()
    }

    @Test
    fun `an empty first page with more to come needs the whole list`() {
        assertThat(firstPageMatchesSavedCopy(saved, emptyList(), hasMorePages = true, headerCount = 4, lastHeaderCount = null))
            .isFalse()
    }

    @Test
    fun `a single page must equal the saved list`() {
        assertThat(firstPageMatchesSavedCopy(saved, saved, hasMorePages = false, headerCount = 9, lastHeaderCount = null)).isTrue()
        assertThat(firstPageMatchesSavedCopy(saved, listOf("a", "b", "c"), hasMorePages = false, headerCount = 3, lastHeaderCount = null))
            .isFalse()
    }
}
