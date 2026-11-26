package io.github.aedev.flow.data.music.artwork

/** Where the music player's animated artwork comes from. */
enum class ArtworkSource {
    /** Apple Music's album loops. */
    APPLE_MUSIC,

    /** Spotify's per-song Canvas, matched by the artwork service. */
    SPOTIFY_CANVAS,

    /** Apple Music's album loop, or the song's Canvas when the album has none. */
    BOTH,
    ;

    internal val asksAppleMusic: Boolean get() = this != SPOTIFY_CANVAS
}

internal fun resolveArtworkSource(stored: String?): ArtworkSource =
    stored?.let { value -> ArtworkSource.entries.firstOrNull { it.name == value } } ?: ArtworkSource.APPLE_MUSIC
