package io.github.aedev.flow.ui.components.music.section

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.music.model.MusicPlaylist
import org.junit.Test

class MusicHomeYourPlaylistsTest {
    private val mix = MusicPlaylist(id = "p1", title = "Mix", thumbnailUrl = "", trackCount = 4)
    private val liked = MusicPlaylist(id = PlaylistRepository.LIKED_MUSIC_ID, title = "", thumbnailUrl = "", trackCount = 2)

    private fun like(
        id: String,
        thumbnail: String,
    ) = LikedVideoInfo(videoId = id, title = id, thumbnail = thumbnail, channelName = "", likedAt = 0L, isMusic = true)

    @Test
    fun `the Liked music card comes before the playlists`() {
        assertThat(yourPlaylists(liked, listOf(mix), emptySet())).containsExactly(liked, mix).inOrder()
    }

    @Test
    fun `its own switch hides only the card`() {
        assertThat(yourPlaylists(liked, listOf(mix), setOf(MusicHomeShelf.LIKED_MUSIC))).containsExactly(mix)
    }

    @Test
    fun `hiding Your playlists hides the card too`() {
        assertThat(yourPlaylists(liked, listOf(mix), setOf(MusicHomeShelf.YOUR_PLAYLISTS))).isEmpty()
    }

    @Test
    fun `liked songs alone are enough for the shelf`() {
        assertThat(yourPlaylists(liked, emptyList(), emptySet())).containsExactly(liked)
    }

    @Test
    fun `no likes means no card`() {
        assertThat(likedMusicCard(emptyList())).isNull()
        assertThat(yourPlaylists(likedMusicCard(emptyList()), emptyList(), emptySet())).isEmpty()
    }

    @Test
    fun `the card shows the newest like's cover and the song count`() {
        val card = likedMusicCard(listOf(like("new", "https://i/new.jpg"), like("old", "https://i/old.jpg")))

        assertThat(card?.id).isEqualTo(PlaylistRepository.LIKED_MUSIC_ID)
        assertThat(card?.thumbnailUrl).isEqualTo("https://i/new.jpg")
        assertThat(card?.trackCount).isEqualTo(2)
    }
}
