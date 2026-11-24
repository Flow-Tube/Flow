package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyllableJoinTest {
    private fun line(text: String) = LyricsEntry(0L, text, listOf(WordTimestamp(text, 0L, 1L)))

    private fun rejoin(
        text: String,
        reference: String,
    ) = SyllableJoin.rejoin(listOf(line(text)), reference).single().text

    @Test
    fun `parts that spell a word of the reference are joined`() {
        val reference = "[00:10.00] When I see the devil's eyes\n[00:15.00] I'll look away and smile wide"

        assertThat(rejoin("When I see the de vil's eyes", reference)).isEqualTo("When I see the devil's eyes")
        assertThat(rejoin("I'll look a way and smile wide", reference)).isEqualTo("I'll look away and smile wide")
    }

    @Test
    fun `a word split in three is joined whole`() {
        assertThat(rejoin("As ev i dence I left", "a drag path etched in the surface as evidence")).isEqualTo("As evidence I left")
        assertThat(rejoin("My melan choli a", "My melancholia")).isEqualTo("My melancholia")
    }

    @Test
    fun `parts the reference also sings apart stay apart`() {
        assertThat(rejoin("Turn a way", "Find away, then turn a way")).isEqualTo("Turn a way")
    }

    @Test
    fun `a word the reference spells differently stays split`() {
        assertThat(rejoin("in the ad ver saire", "'Cause proof is in the adversere")).isEqualTo("in the ad ver saire")
    }

    @Test
    fun `entity-encoded apostrophes match the reference`() {
        assertThat(rejoin("the de vil&#x27;s eyes", "the devil's eyes")).isEqualTo("the devil&#x27;s eyes")
    }

    @Test
    fun `timings are left alone and an empty reference changes nothing`() {
        val entry = LyricsEntry(5L, "mid night", listOf(WordTimestamp("mid", 5L, 6L), WordTimestamp("night", 6L, 7L)))

        val joined = SyllableJoin.rejoin(listOf(entry), "Meet me at midnight").single()

        assertThat(joined.text).isEqualTo("midnight")
        assertThat(joined.words).isEqualTo(entry.words)
        assertThat(SyllableJoin.rejoin(listOf(entry), "")).containsExactly(entry)
    }
}
