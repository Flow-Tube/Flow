package io.github.aedev.flow.player

object PlayerChannelMetadataPolicy {
    fun selectAvatarUrl(
        fetchedAvatarUrl: String?,
        embeddedAvatarUrl: String?,
        currentAvatarUrl: String?,
    ): String? =
        sequenceOf(fetchedAvatarUrl, embeddedAvatarUrl, currentAvatarUrl)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
}
