package io.github.aedev.flow.innertube.models

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SongItemKindTest {
    private fun song(type: String?) = SongItem(id = "s", title = "T", artists = emptyList(), musicVideoType = type, thumbnail = "")

    @Test
    fun `only official and user-made music videos are video songs`() {
        assertThat(song("MUSIC_VIDEO_TYPE_OMV").isVideoSong).isTrue()
        assertThat(song("MUSIC_VIDEO_TYPE_UGC").isVideoSong).isTrue()
        listOf("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC", "MUSIC_VIDEO_TYPE_PODCAST_EPISODE", null).forEach {
            assertThat(song(it).isVideoSong).isFalse()
        }
    }

    @Test
    fun `a podcast episode is no music video but stays out of song lists`() {
        val episode = song("MUSIC_VIDEO_TYPE_PODCAST_EPISODE")

        assertThat(episode.isPodcastEpisode).isTrue()
        assertThat(episode.isVideoTrack).isTrue()
        assertThat(song("MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC").isVideoTrack).isFalse()
        assertThat(song("MUSIC_VIDEO_TYPE_ATV").isVideoTrack).isFalse()
    }

    @Test
    fun `an official audio upload is no longer dropped as a video`() {
        val items: List<YTItem> = listOf(song("MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC"), song("MUSIC_VIDEO_TYPE_OMV").copy(id = "v"))

        assertThat(items.filterVideoSongs(disableVideos = true).map { it.id }).containsExactly("s")
    }
}
