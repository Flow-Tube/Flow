package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsSyllableSpacingTest {
    private val lavenderHaze =
        """
        [01:39.839]v1:<01:39.839>I <01:40.05><01:40.05>find <01:40.252><01:40.252>it <01:40.55><01:40.55>dizzy<01:40.895><01:40.895>ing <01:41.134>
        [bg:<01:41.134><01:41.134>Yeah, <01:41.537><01:41.537>oh <01:41.823><01:41.823>yeah <01:42.173><01:42.173>]
        [01:44.64]v1:<01:44.64>But <01:44.89><01:44.89>you <01:44.99><01:44.99>weren't <01:45.096><01:45.096>even <01:45.412><01:45.412>liste<01:45.703><01:45.703>ning <01:46.306>
        [01:54.57]v1:<01:54.57>Sur<01:55.381><01:55.381>real <01:56.05>
        """.trimIndent()

    @Test
    fun `syllables with no space between their tags stay one word`() {
        val texts = LyricsUtils.parseLyrics(lavenderHaze).map { it.text }

        assertThat(texts).containsAtLeast("I find it dizzying", "But you weren't even listening", "Surreal")
    }

    @Test
    fun `each syllable keeps its own timing`() {
        val surreal = LyricsUtils.parseLyrics(lavenderHaze).single { it.text == "Surreal" }

        assertThat(surreal.words?.map { it.text }).containsExactly("Sur", "real").inOrder()
        assertThat(surreal.words?.map { it.startTime }).containsExactly(114_570L, 115_381L).inOrder()
    }

    @Test
    fun `background lines join their syllables the same way`() {
        val background = LyricsUtils.parseLyrics(lavenderHaze).single { it.isBackground }

        assertThat(background.text).contains("Yeah, oh yeah")
    }

    @Test
    fun `spaces written around the tags still separate words`() {
        val musixmatch = "[00:10.01] <00:10.01> When <00:11.12> I <00:11.45> see\n[00:14.78] <00:14.78> You <00:15.27> found <00:16.18> me"

        assertThat(LyricsUtils.parseLyrics(musixmatch).map { it.text }).containsExactly("When I see", "You found me").inOrder()
    }
}
