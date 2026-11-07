package io.github.aedev.flow.data.lyrics

/**
 * The order lyrics providers are tried in, as saved in settings. A provider the saved order has
 * never seen joins next to the provider it follows by default, not at the end, so an update that
 * adds one reaches people who reordered the list once.
 */
internal object LyricsProviderOrder {
    val DEFAULT: List<String> =
        listOf(
            YouTubeLyricsProvider.NAME,
            "BetterLyrics",
            LrcRedLyricsProvider.NAME,
            "LyricsPlus",
            "SimpMusic",
            "KuGou",
            "Paxsenix",
            "LrcLib",
            UnisonLyricsProvider.NAME,
            NetEaseLyricsProvider.NAME,
            AmllLyricsProvider.NAME,
            "YouTubeSubtitle",
        )

    fun resolve(saved: String): List<String> {
        if (saved.isBlank()) return DEFAULT
        val order =
            saved
                .split(",")
                .map { it.trim() }
                .filter { it in DEFAULT }
                .distinct()
                .toMutableList()
        DEFAULT.forEachIndexed { index, name ->
            if (name in order) return@forEachIndexed
            val predecessor = DEFAULT.subList(0, index).lastOrNull { it in order }
            order.add(predecessor?.let { order.indexOf(it) + 1 } ?: 0, name)
        }
        return order
    }

    fun serialize(names: List<String>): String = names.filter { it in DEFAULT }.distinct().joinToString(",")
}
