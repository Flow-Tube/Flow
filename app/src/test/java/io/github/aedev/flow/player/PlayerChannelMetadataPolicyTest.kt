package io.github.aedev.flow.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerChannelMetadataPolicyTest {
    @Test
    fun `fetched avatar replaces embedded fallback`() {
        val avatar =
            PlayerChannelMetadataPolicy.selectAvatarUrl(
                fetchedAvatarUrl = "fetched",
                embeddedAvatarUrl = "embedded",
                currentAvatarUrl = "current",
            )

        assertThat(avatar).isEqualTo("fetched")
    }

    @Test
    fun `empty fetch never clears existing avatar`() {
        val avatar =
            PlayerChannelMetadataPolicy.selectAvatarUrl(
                fetchedAvatarUrl = "",
                embeddedAvatarUrl = null,
                currentAvatarUrl = "current",
            )

        assertThat(avatar).isEqualTo("current")
    }

    @Test
    fun `a card's channel beats the hidden channel in the player response`() {
        val channel =
            PlayerChannelMetadataPolicy.streamChannel(
                cardId = "UCreal",
                cardName = "Camila Cabello",
                playerId = "UCvevo",
                playerName = "CamilaCabelloVEVO",
            )

        assertThat(channel).isEqualTo(PlayerChannelMetadataPolicy.ChannelIdentity("UCreal", "Camila Cabello"))
    }

    @Test
    fun `a card with no real id leaves the player response in charge`() {
        val channel =
            PlayerChannelMetadataPolicy.streamChannel(
                cardId = "@handle",
                cardName = "Card name",
                playerId = "UCplayer",
                playerName = "Player name",
            )

        assertThat(channel).isEqualTo(PlayerChannelMetadataPolicy.ChannelIdentity("UCplayer", "Player name"))
    }

    @Test
    fun `a deep link with no card uses the player response`() {
        val channel = PlayerChannelMetadataPolicy.streamChannel(null, null, "UCplayer", "Player name")

        assertThat(channel).isEqualTo(PlayerChannelMetadataPolicy.ChannelIdentity("UCplayer", "Player name"))
    }

    @Test
    fun `a card with an id and no name borrows the player's name`() {
        val channel = PlayerChannelMetadataPolicy.streamChannel("UCreal", " ", "UCvevo", "Player name")

        assertThat(channel).isEqualTo(PlayerChannelMetadataPolicy.ChannelIdentity("UCreal", "Player name"))
    }
}
