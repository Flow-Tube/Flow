package io.github.aedev.flow.service.auto

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.BuildConfig
import org.junit.Test

class AutoArtworkUrlTest {
    private val prefix = "content://${BuildConfig.APPLICATION_ID}.auto.artwork/art?u="

    @Test
    fun `the car's content form unwraps to the web address it was made from`() {
        val web = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=-oaymwE&rs=AOn4"
        val wrapped = prefix + "https%3A%2F%2Fi.ytimg.com%2Fvi%2FdQw4w9WgXcQ%2Fhqdefault.jpg%3Fsqp%3D-oaymwE%26rs%3DAOn4"

        assertThat(AutoArtworkProvider.webUrlOf(wrapped)).isEqualTo(web)
    }

    @Test
    fun `any other address comes back as it is`() {
        listOf(
            "https://i.ytimg.com/vi/x/hqdefault.jpg",
            "content://media/external/audio/albumart/12",
            "content://other.app.auto.artwork/art?u=https%3A%2F%2Fi.ytimg.com%2Fx.jpg",
            "",
        ).forEach { assertThat(AutoArtworkProvider.webUrlOf(it)).isEqualTo(it) }
    }

    @Test
    fun `web artwork round-trips through the car's content form, and other hosts are refused`() {
        val web = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=-oaymwE&rs=AOn4"
        val wrapped = AutoArtworkProvider.artworkUriString(web)

        assertThat(wrapped).startsWith(prefix)
        assertThat(AutoArtworkProvider.webUrlOf(wrapped!!)).isEqualTo(web)
        assertThat(AutoArtworkProvider.artworkUriString("content://media/external/audio/albumart/12"))
            .isEqualTo("content://media/external/audio/albumart/12")
        assertThat(AutoArtworkProvider.artworkUriString("https://example.com/a.jpg")).isNull()
        assertThat(AutoArtworkProvider.artworkUriString("")).isNull()
    }
}
