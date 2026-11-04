package io.github.aedev.flow.player

object PlayerChannelMetadataPolicy {
    private const val CHANNEL_ID_PREFIX = "UC"

    data class ChannelIdentity(
        val id: String,
        val name: String,
    )

    /**
     * Which channel a video is shown under before the watch response arrives. A music video's player
     * response can name a hidden VEVO channel, so a card that already knew the real channel wins, and
     * its id and name stay together.
     */
    fun streamChannel(
        cardId: String?,
        cardName: String?,
        playerId: String?,
        playerName: String?,
    ): ChannelIdentity {
        val card = cardId?.takeIf { it.startsWith(CHANNEL_ID_PREFIX) }
        if (card != null) {
            return ChannelIdentity(card, cardName?.takeIf { it.isNotBlank() } ?: playerName.orEmpty())
        }
        return ChannelIdentity(
            id = playerId?.takeIf { it.isNotBlank() } ?: cardId.orEmpty(),
            name = playerName?.takeIf { it.isNotBlank() } ?: cardName.orEmpty(),
        )
    }

    fun selectAvatarUrl(
        fetchedAvatarUrl: String?,
        embeddedAvatarUrl: String?,
        currentAvatarUrl: String?,
    ): String? =
        sequenceOf(fetchedAvatarUrl, embeddedAvatarUrl, currentAvatarUrl)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
}
