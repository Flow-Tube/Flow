package io.github.aedev.flow.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerChannelMetadataPolicyTest {
    @Test
    fun `fetched avatar replaces embedded fallback`() {
        val avatar = PlayerChannelMetadataPolicy.selectAvatarUrl(
            fetchedAvatarUrl = "fetched",
            embeddedAvatarUrl = "embedded",
            currentAvatarUrl = "current"
        )

        assertThat(avatar).isEqualTo("fetched")
    }

    @Test
    fun `empty fetch never clears existing avatar`() {
        val avatar = PlayerChannelMetadataPolicy.selectAvatarUrl(
            fetchedAvatarUrl = "",
            embeddedAvatarUrl = null,
            currentAvatarUrl = "current"
        )

        assertThat(avatar).isEqualTo("current")
    }
}
