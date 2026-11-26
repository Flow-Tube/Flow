package io.github.aedev.flow.data.music.artwork

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArtworkSourceTest {
    @Test
    fun `nothing saved or an unknown value keeps apple music`() {
        assertThat(resolveArtworkSource(null)).isEqualTo(ArtworkSource.APPLE_MUSIC)
        assertThat(resolveArtworkSource("TIDAL")).isEqualTo(ArtworkSource.APPLE_MUSIC)
        assertThat(resolveArtworkSource("BOTH")).isEqualTo(ArtworkSource.BOTH)
    }

    @Test
    fun `only canvas alone skips apple music`() {
        assertThat(ArtworkSource.entries.filterNot { it.asksAppleMusic }).containsExactly(ArtworkSource.SPOTIFY_CANVAS)
    }
}
