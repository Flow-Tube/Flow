package io.github.aedev.flow.data.local

/** Channels subscribed once on first launch of a build that ships this list. */
internal object DefaultChannels {
    private const val CHANNEL_URL_PREFIX = "https://www.youtube.com/channel/"

    private val entries =
        listOf(
            "https://www.youtube.com/channel/UCjCNdxBqo7HZLMo9j9zNkjg" to "-ТАЁЖНЫЙ-",
            "https://www.youtube.com/channel/UCY03gpyR__MuJtBpoSyIGnw" to "Droider",
            "https://www.youtube.com/channel/UC9QCSnXY8JR32jYNUVU8rgA" to
                "Solo wild hikes in Siberia from ABVGAT. Survival.",
            "https://www.youtube.com/channel/UCdubelOloxR3wzwJG9x8YqQ" to "TV Rain",
            "https://www.youtube.com/channel/UCBpeYNfLtLMuxBYeQTeeuog" to "Всё из за Метало Копа",
            "https://www.youtube.com/channel/UCuUH2_MlIpWpJnWZKeyVGrQ" to "ЗА ВЕРСТУ",
            "https://www.youtube.com/channel/UCgpSieplNxXxLXYAzJLLpng" to "Майкл Наки",
            "https://www.youtube.com/channel/UC5bhU-LtufD3tl2C9OyAzkw" to "НАЕДИНЕ С ТАЙГОЙ",
            "https://www.youtube.com/channel/UCRGiaVdWE7nvjH9Ak8-oIPw" to "Реки Севера. Northern Rivers",
            "https://www.youtube.com/channel/UCk94O116OXhPAOWZM7S2Leg" to "Ремонтяш",
            "https://www.youtube.com/channel/UC9Omlbb17r_o7WwQsyvGV-A" to "Руслан Шемякин",
            "https://www.youtube.com/channel/UCX-Yk13ftllhgSt6FrOrO8A" to "hackmyth",
        )

    /** Avatars start empty and are filled in by the startup avatar repair. */
    val subscriptions: List<ChannelSubscription> =
        entries.map { (url, name) ->
            ChannelSubscription(
                channelId = url.removePrefix(CHANNEL_URL_PREFIX),
                channelName = name,
                channelThumbnail = "",
            )
        }
}
