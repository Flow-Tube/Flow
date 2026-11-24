package io.github.aedev.flow.ui.components.shared

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pins the pure helpers of the comments sheet: timestamp links and author handles. */
class CommentTimestampTest {
    @Test
    fun `minutes and seconds convert to milliseconds`() {
        assertThat(commentTimestampToMs("1:05")).isEqualTo(65_000L)
        assertThat(commentTimestampToMs("01:05")).isEqualTo(65_000L)
        assertThat(commentTimestampToMs("0:00")).isEqualTo(0L)
        assertThat(commentTimestampToMs("90:00")).isEqualTo(5_400_000L)
    }

    @Test
    fun `an hours field is honoured`() {
        assertThat(commentTimestampToMs("1:02:03")).isEqualTo(3_723_000L)
        assertThat(commentTimestampToMs("10:00:00")).isEqualTo(36_000_000L)
    }

    @Test
    fun `text that is not a timestamp maps to the start`() {
        assertThat(commentTimestampToMs("abc")).isEqualTo(0L)
        assertThat(commentTimestampToMs("")).isEqualTo(0L)
    }

    @Test
    fun `a bare number is read as seconds`() {
        // Changed with the move onto the shared parser, which the SponsorBlock dialog also uses:
        // "5" used to map to 0. The comment link regex always carries a colon, so no rendered
        // timestamp reaches this branch.
        assertThat(commentTimestampToMs("5")).isEqualTo(5_000L)
    }

    @Test
    fun `a non numeric field rejects the whole timestamp`() {
        // Changed with the move onto the shared parser: a bad field used to count as 0, so "1:xx"
        // seeked to a minute in. Now nothing is parsed and the seek goes to the start.
        assertThat(commentTimestampToMs("1:xx")).isEqualTo(0L)
        assertThat(commentTimestampToMs("x:30")).isEqualTo(0L)
    }

    @Test
    fun `four fields are rejected`() {
        assertThat(commentTimestampToMs("1:2:3:4")).isEqualTo(0L)
    }

    @Test
    fun `formatAuthorName always yields a single at prefix`() {
        assertThat(formatAuthorName("Blacke")).isEqualTo("@Blacke")
        assertThat(formatAuthorName("@Blacke")).isEqualTo("@Blacke")
        assertThat(formatAuthorName("  Blacke ")).isEqualTo("@Blacke")
    }

    @Test
    fun `formatAuthorName of a blank author is a lone at sign`() {
        // Pins current behaviour.
        assertThat(formatAuthorName("")).isEqualTo("@")
        assertThat(formatAuthorName("   ")).isEqualTo("@")
    }
}
