package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.changes.collectionsOf
import io.github.aedev.flow.sync2.changes.collectionsToPush
import org.junit.Assert.assertEquals
import org.junit.Test

/** A push exports only collections that can have changed, and everything after a reset. */
class PushPlanTest {
    private val all = SyncCollection.ANDROID_SYNCABLE
    private val hour = 60 * 60 * 1000L
    private val now = 100 * hour
    private val tracked = setOf(SyncCollection.WATCH_HISTORY, SyncCollection.PLAYLISTS, SyncCollection.SUBSCRIPTIONS, SyncCollection.NOTES)

    @Test
    fun everything_goes_while_the_ledger_is_empty() {
        assertEquals(all, collectionsToPush(all, unchanged = tracked, lastSent = emptyMap(), nowMs = now))
    }

    @Test
    fun unchanged_room_collections_and_brains_inside_their_wait_stay_out() {
        val lastSent = all.associateWith { now - hour }
        val pushed = collectionsToPush(all, unchanged = tracked - SyncCollection.WATCH_HISTORY, lastSent = lastSent, nowMs = now)
        assertEquals(
            listOf(SyncCollection.WATCH_HISTORY, SyncCollection.LIKES, SyncCollection.SETTINGS, SyncCollection.SUBSCRIBED_CHANNELS),
            pushed,
        )
    }

    @Test
    fun a_brain_goes_again_once_six_hours_passed() {
        val lastSent = all.associateWith { now - 6 * hour }
        val pushed = collectionsToPush(all, unchanged = tracked, lastSent = lastSent, nowMs = now)
        assertEquals(true, SyncCollection.FLOW_NEURO_BRAIN in pushed && SyncCollection.MUSIC_BRAIN in pushed)
    }

    @Test
    fun a_room_collection_the_ledger_lost_goes_though_its_tables_stayed() {
        val lastSent = (all - SyncCollection.NOTES).associateWith { now }
        assertEquals(true, SyncCollection.NOTES in collectionsToPush(all, unchanged = tracked, lastSent = lastSent, nowMs = now))
    }

    @Test
    fun a_table_names_the_collections_exported_from_it() {
        assertEquals(setOf(SyncCollection.PLAYLISTS), collectionsOf(setOf("videos")))
        assertEquals(
            setOf(SyncCollection.WATCH_HISTORY, SyncCollection.NOTES),
            collectionsOf(setOf("watch_history", "notes", "search_history")),
        )
    }
}
