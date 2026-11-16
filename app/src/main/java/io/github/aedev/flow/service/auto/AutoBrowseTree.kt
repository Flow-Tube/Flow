package io.github.aedev.flow.service.auto

/**
 * The ids of the car's browse tree. A song's id names the list it was picked from, so a tap can
 * play that whole list from the song, as tapping a song in the app does.
 */
internal object AutoBrowseTree {
    const val ROOT = "auto:root"
    const val FOR_YOU = "auto:for_you"
    const val LIBRARY = "auto:library"
    const val PLAYLISTS = "auto:playlists"
    const val QUEUE = "auto:queue"

    /** The root and its tabs, which a car keeps showing until told they changed. */
    val TABS = listOf(ROOT, FOR_YOU, LIBRARY, PLAYLISTS, QUEUE)

    const val ON_REPEAT = "auto:on_repeat"
    const val REDISCOVER = "auto:rediscover"
    const val RIGHT_NOW = "auto:right_now"

    const val LIKED = "auto:liked"
    const val DOWNLOADS = "auto:downloads"
    const val RECENT = "auto:recent"
    const val WATCH_LATER = "auto:watch_later"

    private const val PLAYLIST_PREFIX = "auto:playlist:"
    private const val VIDEO_PLAYLIST_PREFIX = "auto:vplaylist:"
    private const val SEARCH_PREFIX = "auto:search:"
    private const val SONG_SEPARATOR = '|'

    /** The car shows at most four tabs, and fewer when its root hint says so. */
    const val DEFAULT_ROOT_LIMIT = 4

    val tabs = listOf(FOR_YOU, LIBRARY, PLAYLISTS, QUEUE)
    val mixes = listOf(ON_REPEAT, RIGHT_NOW, REDISCOVER)
    val libraryLists = listOf(LIKED, DOWNLOADS, RECENT, WATCH_LATER)

    fun rootTabs(limit: Int): List<String> = tabs.take(limit.takeIf { it > 0 } ?: DEFAULT_ROOT_LIMIT)

    fun playlist(playlistId: String) = PLAYLIST_PREFIX + playlistId

    fun playlistIdOf(parentId: String): String? = parentId.removePrefix(PLAYLIST_PREFIX).takeIf { parentId.startsWith(PLAYLIST_PREFIX) }

    fun videoPlaylist(playlistId: String) = VIDEO_PLAYLIST_PREFIX + playlistId

    fun videoPlaylistIdOf(parentId: String): String? =
        parentId.removePrefix(VIDEO_PLAYLIST_PREFIX).takeIf { parentId.startsWith(VIDEO_PLAYLIST_PREFIX) }

    /** Whether [parentId] lists videos rather than songs (#1657): they play as audio but are not music. */
    fun isVideoList(parentId: String): Boolean = parentId == WATCH_LATER || videoPlaylistIdOf(parentId) != null

    fun search(query: String) = SEARCH_PREFIX + query.trim()

    fun searchQueryOf(parentId: String): String? = parentId.removePrefix(SEARCH_PREFIX).takeIf { parentId.startsWith(SEARCH_PREFIX) }

    fun song(
        parentId: String,
        videoId: String,
    ) = "$parentId$SONG_SEPARATOR$videoId"

    /** The list a song was picked from and its video id, or null for an id that is not a song in a list. */
    fun songOf(mediaId: String): Pair<String, String>? {
        val split = mediaId.lastIndexOf(SONG_SEPARATOR)
        if (split <= 0 || split == mediaId.lastIndex) return null
        return mediaId.substring(0, split) to mediaId.substring(split + 1)
    }
}
