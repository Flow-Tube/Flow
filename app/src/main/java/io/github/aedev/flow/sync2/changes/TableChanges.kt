package io.github.aedev.flow.sync2.changes

import androidx.room.InvalidationTracker
import io.github.aedev.flow.data.local.AppDatabase
import io.github.aedev.flow.di.ProfileDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which Room-backed collections changed since a push last exported them, from Room's own
 * invalidation tracker. Each counts as changed until its first export in this process.
 */
@Singleton
class TableChanges
    @Inject
    constructor(
        @ProfileDb private val db: AppDatabase,
    ) {
        private val changed = MutableStateFlow(COLLECTION_TABLES.keys)
        private val watching = AtomicBoolean(false)
        private val observer =
            object : InvalidationTracker.Observer(
                COLLECTION_TABLES.values
                    .flatten()
                    .distinct()
                    .toTypedArray(),
            ) {
                override fun onInvalidated(tables: Set<String>) {
                    changed.update { it + collectionsOf(tables) }
                }
            }

        /** The tracked collections whose tables stayed as they were since their last export. */
        fun unchanged(): Set<String> {
            if (watching.compareAndSet(false, true)) db.invalidationTracker.addObserver(observer)
            return COLLECTION_TABLES.keys - changed.value
        }

        /** Counts [collections] as exported now: a change from here on marks them again. */
        fun exporting(collections: Collection<String>) = changed.update { it - collections.toSet() }

        /** Counts [collections] as changed again, after an export that never reached the store. */
        fun failed(collections: Collection<String>) = changed.update { it + collections }
    }
