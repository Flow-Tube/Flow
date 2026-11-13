package io.github.aedev.flow.sync.mapping

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.entity.PlaylistEntity
import io.github.aedev.flow.sync.canonical.CanonicalPlaylist
import org.junit.Test

class PlaylistMapperTest {
    private val hlc = "1000:0:aaaaaaaa"

    private fun playlist(
        id: String,
        isUserCreated: Boolean,
    ) = PlaylistEntity(
        id = id,
        name = id,
        description = "",
        thumbnailUrl = "",
        isPrivate = false,
        createdAt = 1L,
        isUserCreated = isUserCreated,
    )

    @Test
    fun watch_later_stays_local_after_taking_the_desktop_flags() {
        val desktopWatchLater =
            CanonicalPlaylist(
                syncId = CanonicalPlaylist.RESERVED_WATCH_LATER,
                title = "Watch Later",
                isUserCreated = false,
                isProtected = true,
                updatedHlc = hlc,
            )
        val stored = PlaylistMapper.toPlaylistEntity(desktopWatchLater, PlaylistMapper.WATCH_LATER_ID)

        val sent = PlaylistMapper.toCanonical(stored, emptyList(), hlc)

        assertThat(sent.syncId).isEqualTo(CanonicalPlaylist.RESERVED_WATCH_LATER)
        assertThat(sent.origin).isEqualTo(CanonicalPlaylist.ORIGIN_LOCAL)
        assertThat(sent.youtubeId).isNull()
        assertThat(sent.isProtected).isTrue()
    }

    @Test
    fun a_saved_youtube_playlist_keeps_its_youtube_id() {
        val sent = PlaylistMapper.toCanonical(playlist("PLabc", isUserCreated = false), emptyList(), hlc)

        assertThat(sent.origin).isEqualTo(CanonicalPlaylist.ORIGIN_YOUTUBE)
        assertThat(sent.youtubeId).isEqualTo("PLabc")
    }
}
