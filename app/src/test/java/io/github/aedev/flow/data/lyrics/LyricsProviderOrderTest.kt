package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsProviderOrderTest {
    @Test
    fun `nothing saved means the default order`() {
        assertThat(LyricsProviderOrder.resolve("")).isEqualTo(LyricsProviderOrder.DEFAULT)
    }

    @Test
    fun `youtube music is asked first by default`() {
        assertThat(LyricsProviderOrder.DEFAULT.first()).isEqualTo("YouTube")
    }

    @Test
    fun `a saved order keeps youtube music where it was`() {
        val saved =
            listOf(
                "BetterLyrics",
                "LrcRed",
                "LyricsPlus",
                "SimpMusic",
                "KuGou",
                "Paxsenix",
                "LrcLib",
                "Unison",
                "YouTubeSubtitle",
                "YouTube",
            )

        assertThat(LyricsProviderOrder.resolve(saved.joinToString(",")).filter { it in saved }).isEqualTo(saved)
    }

    @Test
    fun `an order saved before NetEase and AMLL existed gets them right after Unison`() {
        val saved = listOf("LrcLib", "Unison", "YouTube")

        val order = LyricsProviderOrder.resolve(saved.joinToString(","))

        assertThat(order.subList(order.indexOf("Unison"), order.indexOf("Unison") + 3))
            .containsExactly("Unison", "NetEase", "AMLL")
            .inOrder()
    }

    @Test
    fun `a full saved order is kept as it is`() {
        val saved = LyricsProviderOrder.DEFAULT.reversed()

        assertThat(LyricsProviderOrder.resolve(saved.joinToString(","))).isEqualTo(saved)
    }

    @Test
    fun `an order saved before lrc red existed gets it right after BetterLyrics`() {
        val saved = listOf("LyricsPlus", "BetterLyrics", "KuGou", "SimpMusic", "Paxsenix", "LrcLib", "YouTubeSubtitle", "YouTube")

        val resolved = LyricsProviderOrder.resolve(saved.joinToString(","))

        assertThat(resolved.indexOf("LrcRed")).isEqualTo(resolved.indexOf("BetterLyrics") + 1)
        assertThat(resolved.filter { it in saved }).isEqualTo(saved)
    }

    @Test
    fun `a missing first provider goes to the top`() {
        val saved = LyricsProviderOrder.DEFAULT.drop(1)

        assertThat(LyricsProviderOrder.resolve(saved.joinToString(","))).isEqualTo(LyricsProviderOrder.DEFAULT)
    }

    @Test
    fun `unknown and repeated names are dropped`() {
        val resolved = LyricsProviderOrder.resolve("Gone, KuGou,KuGou," + LyricsProviderOrder.DEFAULT.joinToString(","))

        assertThat(resolved.first()).isEqualTo("KuGou")
        assertThat(resolved).containsExactlyElementsIn(LyricsProviderOrder.DEFAULT)
    }

    @Test
    fun `serialize keeps known names once`() {
        assertThat(LyricsProviderOrder.serialize(listOf("KuGou", "Gone", "KuGou", "LrcLib"))).isEqualTo("KuGou,LrcLib")
    }
}
