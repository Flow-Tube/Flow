package io.github.aedev.flow.ui.screens.music.collection

import androidx.annotation.StringRes
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack

/** How a music page orders its songs; remembered per collection. */
enum class MusicSortOrder(
    val storageValue: String,
    @param:StringRes val labelRes: Int,
) {
    COLLECTION("music_collection", R.string.music_sort_collection),
    NEWEST_ADDED("music_newest_added", R.string.playlist_sort_date_added_newest),
    OLDEST_ADDED("music_oldest_added", R.string.playlist_sort_date_added_oldest),
    TITLE("music_title", R.string.music_sort_title),
    ARTIST("music_artist", R.string.music_sort_artist),
    ALBUM("music_album", R.string.music_sort_album),
    LONGEST("music_longest", R.string.music_sort_longest),
    SHORTEST("music_shortest", R.string.music_sort_shortest),
    ;

    companion object {
        fun fromStorage(value: String?): MusicSortOrder? = entries.firstOrNull { it.storageValue == value }

        /**
         * The orders a collection has data for. An album keeps its track order and a single artist;
         * only stored collections know when a song was added; album only when the songs carry one.
         * Liked music is already newest first, so "newest added" would repeat its own order.
         */
        fun availableFor(
            kind: MusicCollectionKind?,
            hasAddedDates: Boolean,
            hasAlbums: Boolean,
        ): List<MusicSortOrder> =
            entries.filter { order ->
                when (order) {
                    COLLECTION, TITLE, LONGEST, SHORTEST -> true
                    NEWEST_ADDED -> hasAddedDates && kind != MusicCollectionKind.LIKED
                    OLDEST_ADDED -> hasAddedDates
                    ARTIST -> kind != MusicCollectionKind.ALBUM
                    ALBUM -> hasAlbums && kind != MusicCollectionKind.ALBUM
                }
            }
    }
}

/**
 * [this] in [order]. Equal songs keep the collection's order, except that newest added reverses it:
 * an import gives every song one date, and the collection order is the order they were added in.
 */
internal fun List<MusicTrack>.sortedForCollection(
    order: MusicSortOrder,
    addedAt: Map<String, Long>,
): List<MusicTrack> =
    when (order) {
        MusicSortOrder.COLLECTION -> this
        MusicSortOrder.NEWEST_ADDED -> asReversed().sortedByDescending { addedAt[it.videoId] ?: Long.MIN_VALUE }
        MusicSortOrder.OLDEST_ADDED -> sortedBy { addedAt[it.videoId] ?: Long.MAX_VALUE }
        MusicSortOrder.TITLE -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        MusicSortOrder.ARTIST -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
        MusicSortOrder.ALBUM -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.album.ifBlank { "￿" } })
        MusicSortOrder.LONGEST -> sortedByDescending { it.duration }
        MusicSortOrder.SHORTEST -> sortedBy { if (it.duration > 0) it.duration else Int.MAX_VALUE }
    }
