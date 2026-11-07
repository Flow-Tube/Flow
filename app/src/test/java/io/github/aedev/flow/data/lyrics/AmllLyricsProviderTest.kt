package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AmllLyricsProviderTest {
    private val request = LyricsMatching.Song("Espresso", "Sabrina Carpenter", 175)

    @Test
    fun `an entry matches under any of its song names`() {
        val item = AmllLyricsProvider.Item(1, listOf("Espresso (Explicit)", "Espresso"), listOf("Sabrina Carpenter"))

        assertThat(AmllLyricsProvider.pick(listOf(item), request)).isEqualTo(item)
    }

    @Test
    fun `another song or another artist does not match`() {
        val items =
            listOf(
                AmllLyricsProvider.Item(1, listOf("Taste"), listOf("Sabrina Carpenter")),
                AmllLyricsProvider.Item(2, listOf("Espresso"), listOf("Someone Else")),
            )

        assertThat(AmllLyricsProvider.pick(items, request)).isNull()
    }
}
