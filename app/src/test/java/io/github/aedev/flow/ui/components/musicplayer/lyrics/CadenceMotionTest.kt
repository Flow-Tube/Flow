package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.ui.text.style.TextAlign
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CadenceMotionTest {
    @Test
    fun `an accent stays with its letter`() {
        assertThat(letterStarts("éx").toList()).containsExactly(0, 2).inOrder()
    }

    @Test
    fun `an f stays with the letter it may form a ligature with`() {
        assertThat(letterStarts("fine").toList()).containsExactly(0, 2, 3).inOrder()
        assertThat(letterStarts("office").toList()).containsExactly(0, 1, 4, 5).inOrder()
    }

    @Test
    fun `only scripts that don't join rise letter by letter`() {
        assertThat(isLetterSplittable("hello")).isTrue()
        assertThat(isLetterSplittable("שלום")).isTrue()
        assertThat(isLetterSplittable("你好")).isTrue()
        assertThat(isLetterSplittable("привет")).isTrue()
        assertThat(isLetterSplittable("नमस्ते")).isFalse()
        assertThat(isLetterSplittable("ভালোবাসা")).isFalse()
        assertThat(isLetterSplittable("سلام")).isFalse()
        assertThat(isLetterSplittable("สวัสดี")).isFalse()
    }

    @Test
    fun `a single letter has nothing to split`() {
        assertThat(isLetterSplittable("a")).isFalse()
    }

    @Test
    fun `a letter is at its crest the moment it is fully lit`() {
        // Word 0-1000 ms, letter from 20% to 40% of it, a soft edge 5% wide: it starts rising at
        // 150 ms and is lit at 450 ms.
        assertThat(rippleLift(149, 0, 1_000, 0.2f, 0.4f, 0.05f)).isEqualTo(0f)
        assertThat(rippleLift(450, 0, 1_000, 0.2f, 0.4f, 0.05f)).isWithin(0.001f).of(1f)
        assertThat(rippleLift(300, 0, 1_000, 0.2f, 0.4f, 0.05f)).isIn(
            com.google.common.collect.Range
                .open(0f, 1f),
        )
    }

    @Test
    fun `a sung letter settles a little raised`() {
        assertThat(rippleLift(5_000, 0, 1_000, 0.2f, 0.4f, 0.05f)).isEqualTo(0.4f)
        assertThat(rippleSettledMs(0, 1_000, 0.4f, 0.05f)).isEqualTo(1_350f)
    }

    @Test
    fun `only a long held note swells, most in its middle`() {
        assertThat(heldNoteSwell(400, 0, 800)).isEqualTo(1f)
        assertThat(heldNoteSwell(900, 0, 1_800)).isWithin(0.0001f).of(1.035f)
        assertThat(heldNoteSwell(1_900, 0, 1_800)).isEqualTo(1f)
    }

    @Test
    fun `a hyphenated run climbs and springs back`() {
        val words = listOf(KaraokeWord(0, 300), KaraokeWord(300, 600), KaraokeWord(600, 1_000), KaraokeWord(1_100, 1_500))
        markHyphenRuns(
            texts = listOf("so-", "o-", "o", "long"),
            ranges = listOf(0..2, 3..4, 5..5, 7..10),
            words = words,
        )

        assertThat(words.map { it.group }).containsExactly(0, 1, 2, -1).inOrder()
        assertThat(words[0].groupEndMs).isEqualTo(1_000)
        assertThat(crescendoScale(200, words[0])).isEqualTo(1f)
        assertThat(crescendoScale(400, words[1])).isWithin(0.0001f).of(1.012f)
        assertThat(crescendoScale(1_000, words[2])).isWithin(0.0001f).of(1f + 0.024f + 0.06f)
        assertThat(crescendoScale(1_600, words[2])).isWithin(0.0001f).of(1f)
        assertThat(crescendoScale(1_200, words[3])).isEqualTo(1f)
    }

    @Test
    fun `syllables split by a space are not a run`() {
        val words = listOf(KaraokeWord(0, 300), KaraokeWord(300, 600))
        markHyphenRuns(listOf("so-", "long"), listOf(0..2, 4..7), words)

        assertThat(words.map { it.group }).containsExactly(-1, -1)
    }

    @Test
    fun `grown words move a row the way its alignment holds it`() {
        assertThat(reflowAnchor(TextAlign.Center, rtl = false)).isEqualTo(0.5f)
        assertThat(reflowAnchor(TextAlign.Start, rtl = false)).isEqualTo(0f)
        assertThat(reflowAnchor(TextAlign.Start, rtl = true)).isEqualTo(1f)
        assertThat(reflowAnchor(TextAlign.End, rtl = false)).isEqualTo(1f)
    }

    @Test
    fun `a line lets go by finishing its fill, then fading the sung colour`() {
        val fillDone = 180f / CADENCE_EXIT_MS

        assertThat(exitFill(fillDone / 2f)).isWithin(0.001f).of(0.5f)
        assertThat(exitFill(fillDone)).isEqualTo(1f)
        assertThat(exitLitAlpha(fillDone)).isEqualTo(1f)
        assertThat(exitLitAlpha(1f)).isEqualTo(0f)
    }
}
