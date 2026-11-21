package io.github.aedev.flow.data.music.model

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import kotlinx.serialization.json.Json
import org.junit.Test

class MusicTrackListThumbnailTest {
    private val track =
        MusicTrack(
            videoId = "dQw4w9WgXcQ",
            title = "t",
            artist = "a",
            thumbnailUrl = "https://lh3.googleusercontent.com/xyz=w544-h544-l90-rj",
            duration = 1,
        )

    @Test
    fun `the cached list url is never written by either serializer`() {
        val listUrl = track.listThumbnailUrl

        assertThat(Gson().toJson(track)).doesNotContain(listUrl)
        assertThat(Json.encodeToString(MusicTrack.serializer(), track)).doesNotContain(listUrl)
    }

    @Test
    fun `a track read by Gson still resolves its list url`() {
        val read = Gson().fromJson(Gson().toJson(track), MusicTrack::class.java)

        assertThat(read.listThumbnailUrl).isEqualTo("https://lh3.googleusercontent.com/xyz=w256-h256-l90-rj")
    }

    @Test
    fun `a copy with a new cover does not keep the old list url`() {
        track.listThumbnailUrl
        val moved = track.copy(thumbnailUrl = "https://lh3.googleusercontent.com/new=w544-h544-l90-rj")

        assertThat(moved.listThumbnailUrl).isEqualTo("https://lh3.googleusercontent.com/new=w256-h256-l90-rj")
    }
}
