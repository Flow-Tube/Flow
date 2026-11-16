package io.github.aedev.flow.ui.components

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Playlist
import org.junit.Test

class PlaylistCardCountTest {
    private fun playlist(
        count: Int,
        isLocal: Boolean,
    ) = Playlist(id = "RDCLAK", name = "Mix • Lofi hip hop", thumbnailUrl = "", videoCount = count, isLocal = isLocal)

    @Test
    fun `a YouTube mix with no count shows none`() {
        assertThat(playlist(0, isLocal = false).knownVideoCount()).isNull()
    }

    @Test
    fun `a YouTube playlist keeps its count`() {
        assertThat(playlist(42, isLocal = false).knownVideoCount()).isEqualTo(42)
    }

    @Test
    fun `an empty local playlist still says it is empty`() {
        assertThat(playlist(0, isLocal = true).knownVideoCount()).isEqualTo(0)
    }
}
