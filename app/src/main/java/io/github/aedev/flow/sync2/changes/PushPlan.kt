package io.github.aedev.flow.sync2.changes

import io.github.aedev.flow.sync.protocol.SyncCollection

/** The Room tables each collection is exported from; their changes decide whether a push exports it. */
internal val COLLECTION_TABLES: Map<String, Set<String>> =
    mapOf(
        SyncCollection.WATCH_HISTORY to setOf("watch_history"),
        SyncCollection.PLAYLISTS to setOf("playlists", "playlist_video_cross_ref", "videos"),
        SyncCollection.SUBSCRIPTIONS to setOf("subscription_groups"),
        SyncCollection.NOTES to setOf("notes"),
    )

/** The collections whose tables include any of [tables]. */
internal fun collectionsOf(tables: Set<String>): Set<String> = COLLECTION_TABLES.filterValues { owned -> owned.any { it in tables } }.keys

/**
 * The collections a push exports, out of [all]. A brain goes once its 6-hour wait is over; a Room
 * collection once its tables changed, or while the ledger holds nothing of it, as after a reset;
 * any other collection always. [lastSent] is each collection's latest upload in the ledger.
 * Leaving a collection out sends what exporting it would have sent: nothing.
 */
internal fun collectionsToPush(
    all: List<String>,
    unchanged: Set<String>,
    lastSent: Map<String, Long>,
    nowMs: Long,
): List<String> =
    all.filter { collection ->
        when (collection) {
            in BRAINS -> brainUploadDue(lastSent[collection], nowMs)
            in COLLECTION_TABLES -> collection !in unchanged || collection !in lastSent
            else -> true
        }
    }
