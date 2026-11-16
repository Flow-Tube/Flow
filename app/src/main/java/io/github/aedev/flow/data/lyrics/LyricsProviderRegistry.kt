package io.github.aedev.flow.data.lyrics

import okhttp3.OkHttpClient

class LyricsProviderRegistry(
    private val providerMap: Map<String, LyricsProvider>,
) {
    fun getOrderedProviders(orderString: String): List<LyricsProvider> =
        LyricsProviderOrder.resolve(orderString).mapNotNull { providerMap[it] }

    companion object {
        fun create(client: OkHttpClient): LyricsProviderRegistry {
            val providers =
                listOf(
                    YouTubeLyricsProvider(),
                    BetterLyricsProvider(client),
                    LrcRedLyricsProvider(client),
                    LyricsPlusProvider(client),
                    SimpMusicLyricsProvider(client),
                    KuGouLyricsProvider(client),
                    PaxsenixLyricsProvider(client),
                    LrcLibLyricsProvider(client),
                    UnisonLyricsProvider(client),
                    NetEaseLyricsProvider(client),
                    AmllLyricsProvider(client),
                    YouTubeSubtitleLyricsProvider(),
                )
            return LyricsProviderRegistry(providers.associateBy { it.name })
        }
    }
}
