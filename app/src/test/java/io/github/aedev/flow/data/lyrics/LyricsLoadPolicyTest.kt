package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsLoadPolicyTest {
    @Test
    fun `off demand every song change looks lyrics up, as before`() {
        assertThat(LyricsLoadPolicy.playerMayFetch(onDemand = false, playerOpen = false)).isTrue()
        assertThat(LyricsLoadPolicy.playerMayFetch(onDemand = false, playerOpen = true)).isTrue()
        assertThat(LyricsLoadPolicy.serviceMayFetch(onDemand = false, lockScreenLyrics = false)).isTrue()
        assertThat(LyricsLoadPolicy.serviceMayFetch(onDemand = false, lockScreenLyrics = true)).isTrue()
    }

    @Test
    fun `on demand the player looks lyrics up only while it is open`() {
        assertThat(LyricsLoadPolicy.playerMayFetch(onDemand = true, playerOpen = false)).isFalse()
        assertThat(LyricsLoadPolicy.playerMayFetch(onDemand = true, playerOpen = true)).isTrue()
    }

    @Test
    fun `on demand the service looks lyrics up only for lock screen lyrics`() {
        assertThat(LyricsLoadPolicy.serviceMayFetch(onDemand = true, lockScreenLyrics = false)).isFalse()
        assertThat(LyricsLoadPolicy.serviceMayFetch(onDemand = true, lockScreenLyrics = true)).isTrue()
    }
}
