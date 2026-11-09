package io.github.aedev.flow.data.community

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration

class PostAgeTest {
    private val now = 1_700_000_000_000L
    private val day = Duration.ofDays(1).toMillis()

    private fun age(
        text: String,
        hl: String = "en",
    ) = PostAge.publishedAt(text, hl, now)

    @Test
    fun `reads the relative times YouTube prints on posts`() {
        assertThat(age("3 days ago")).isEqualTo(now - 3 * day)
        assertThat(age("2 weeks ago")).isEqualTo(now - 14 * day)
        assertThat(age("1 month ago (edited)")).isEqualTo(now - 30 * day)
        assertThat(age("5 hours ago (edited)")).isEqualTo(now - Duration.ofHours(5).toMillis())
        assertThat(age("Streamed 2 days ago")).isEqualTo(now - 2 * day)
        assertThat(age("yesterday")).isEqualTo(now - day)
    }

    @Test
    fun `reads the age in the language the request was made in`() {
        assertThat(age("hace 3 días", hl = "es")).isEqualTo(now - 3 * day)
    }

    @Test
    fun `text without an age reads as unknown`() {
        assertThat(age("")).isNull()
        assertThat(age("Members only")).isNull()
    }

    @Test
    fun `recent means under thirty days`() {
        assertThat(PostAge.isRecent(age("4 weeks ago"), now)).isTrue()
        assertThat(PostAge.isRecent(age("29 days ago"), now)).isTrue()
        assertThat(PostAge.isRecent(age("30 days ago"), now)).isFalse()
        assertThat(PostAge.isRecent(age("1 month ago (edited)"), now)).isFalse()
        assertThat(PostAge.isRecent(age("2 years ago"), now)).isFalse()
    }

    @Test
    fun `an unknown age is treated as old`() {
        assertThat(PostAge.isRecent(null, now)).isFalse()
    }
}
