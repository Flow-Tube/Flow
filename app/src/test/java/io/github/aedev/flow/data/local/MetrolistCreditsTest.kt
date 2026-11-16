package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MetrolistCreditsTest {
    private fun row(
        song: String,
        name: String?,
        channel: String?,
        position: Int,
    ) = MetrolistArtistRow(song, name, channel, position)

    @Test
    fun `artists join in credit order and the first one's channel is kept`() {
        val credits =
            metrolistCredits(
                listOf(
                    row("song", "Nile Rodgers", "UCnile", 2),
                    row("song", "Daft Punk", "UCdaft", 0),
                    row("song", "Pharrell Williams", "UCpharrell", 1),
                ),
            )

        assertThat(credits["song"]).isEqualTo(MetrolistCredit("Daft Punk, Pharrell Williams, Nile Rodgers", "UCdaft"))
    }

    @Test
    fun `a song with no named artist gets no credit`() {
        val credits = metrolistCredits(listOf(row("song", " ", "UCx", 0), row("song", null, "UCy", 1)))

        assertThat(credits).doesNotContainKey("song")
    }

    @Test
    fun `an unnamed artist is skipped and the next one leads`() {
        val credits = metrolistCredits(listOf(row("song", "", "UCx", 0), row("song", "Mxrci", "UCmxrci", 1)))

        assertThat(credits["song"]).isEqualTo(MetrolistCredit("Mxrci", "UCmxrci"))
    }

    @Test
    fun `a local artist id is not used as a channel`() {
        val credits = metrolistCredits(listOf(row("song", "Home Recording", "LA1234", 0)))

        assertThat(credits["song"]).isEqualTo(MetrolistCredit("Home Recording", ""))
    }

    @Test
    fun `songs keep their own artists`() {
        val credits = metrolistCredits(listOf(row("a", "Karan Aujla", "UCka", 0), row("b", "Ikky", "UCikky", 0)))

        assertThat(credits.mapValues { it.value.names }).containsExactly("a", "Karan Aujla", "b", "Ikky")
    }
}
