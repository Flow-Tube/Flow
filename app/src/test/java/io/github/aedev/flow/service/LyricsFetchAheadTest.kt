package io.github.aedev.flow.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsFetchAheadTest {
    private fun allowed(
        nextId: String? = "next",
        nextHasNames: Boolean = true,
        online: Boolean = true,
        dataSaving: Boolean = false,
        playingElsewhere: Boolean = false,
    ) = shouldFetchLyricsAhead("current", nextId, nextHasNames, online, dataSaving, playingElsewhere)

    @Test
    fun `the next song is fetched ahead on a normal connection`() {
        assertThat(allowed()).isTrue()
    }

    @Test
    fun `no next song means nothing to fetch`() {
        assertThat(allowed(nextId = null)).isFalse()
        assertThat(allowed(nextId = "")).isFalse()
    }

    @Test
    fun `repeating the same song fetches nothing`() {
        assertThat(allowed(nextId = "current")).isFalse()
    }

    @Test
    fun `a local file reads its own lyrics when it plays`() {
        assertThat(allowed(nextId = "local_7")).isFalse()
    }

    @Test
    fun `a song with no title or artist can't be looked up`() {
        assertThat(allowed(nextHasNames = false)).isFalse()
    }

    @Test
    fun `offline, data saving and play on all skip it`() {
        assertThat(allowed(online = false)).isFalse()
        assertThat(allowed(dataSaving = true)).isFalse()
        assertThat(allowed(playingElsewhere = true)).isFalse()
    }
}
