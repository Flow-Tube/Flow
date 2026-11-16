package io.github.aedev.flow.sync.apply

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.profile.ProfileFiles
import io.github.aedev.flow.data.profile.StoreScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One [CollectionLedger] file per sync collection, so the watch-history ledger (tens of thousands
 * of keys) is not rewritten when a playlist changes. A process singleton: DataStore allows one
 * instance per file.
 */
@Singleton
class SyncChangeLedgerStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val stores = ConcurrentHashMap<String, DataStore<CollectionLedger>>()

        suspend fun load(collection: String): CollectionLedger = storeFor(collection).data.first()

        suspend fun update(
            collection: String,
            transform: (CollectionLedger) -> CollectionLedger,
        ): CollectionLedger = storeFor(collection).updateData(transform)

        /**
         * Forget every ledger. A restored backup replaces local data wholesale, so diffing it
         * against the old ledger would read every record missing from the backup as a deletion
         * and push it to the other devices.
         */
        suspend fun reset() {
            for (collection in LEDGERED) storeFor(collection).updateData { CollectionLedger() }
        }

        private fun storeFor(collection: String): DataStore<CollectionLedger> =
            stores.getOrPut(collection) {
                DataStoreFactory.create(
                    serializer = LedgerSerializer,
                    corruptionHandler = ReplaceFileCorruptionHandler { CollectionLedger() },
                    produceFile = { ProfileFiles.dataStore(context, StoreScope.PROFILE_PRIVATE, "sync_ledger_$collection.json") },
                )
            }

        companion object {
            const val WATCH_HISTORY = "watch_history"
            const val LIKES = "likes"
            const val NOTES = "notes"
            const val GROUPS = "subscriptions"
            const val GROUP_MEMBERS = "subscription_members"
            const val PLAYLISTS = "playlists"
            const val PLAYLIST_ITEMS = "playlist_items"
            const val SETTINGS = "settings"
            const val SETTINGS_RELAY = "settings_relay"

            val LEDGERED = listOf(WATCH_HISTORY, LIKES, NOTES, GROUPS, GROUP_MEMBERS, PLAYLISTS, PLAYLIST_ITEMS, SETTINGS, SETTINGS_RELAY)
        }
    }

private object LedgerSerializer : Serializer<CollectionLedger> {
    private const val TAG = "SyncChangeLedger"
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue = CollectionLedger()

    override suspend fun readFrom(input: InputStream): CollectionLedger =
        try {
            val text = input.readBytes().decodeToString()
            if (text.isBlank()) defaultValue else json.decodeFromString(CollectionLedger.serializer(), text)
        } catch (e: SerializationException) {
            throw CorruptionException("Corrupted sync ledger", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unreadable sync ledger, starting empty: ${e.message}")
            defaultValue
        }

    override suspend fun writeTo(
        t: CollectionLedger,
        output: OutputStream,
    ) {
        output.write(json.encodeToString(CollectionLedger.serializer(), t).encodeToByteArray())
    }
}
