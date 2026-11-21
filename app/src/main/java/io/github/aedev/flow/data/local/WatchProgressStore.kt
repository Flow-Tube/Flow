package io.github.aedev.flow.data.local

import io.github.aedev.flow.data.local.dao.WatchProgress
import io.github.aedev.flow.data.local.dao.WatchProgressFingerprint
import io.github.aedev.flow.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** Which history rows a write touched; [All] when it may have touched any of them. */
sealed interface HistoryChange {
    data class Rows(
        val videoIds: Set<String>,
    ) : HistoryChange

    data object All : HistoryChange
}

/**
 * Every history row's progress by video id, shared by the cards, Home and the Subscriptions filters.
 *
 * The table is read once when the first reader subscribes. After that a write through [ViewHistory]
 * re-reads only the rows it touched, so a playback save every 10 s costs one row instead of the
 * whole history. Bulk writes (import, clear, restore) reload everything. Writers that bypass
 * [ViewHistory], such as a sync merge, are caught by comparing [WatchProgressFingerprint] sums after
 * the table settles, and a mismatch reloads.
 *
 * Lazy: nothing is read until a reader subscribes, and the rows are dropped
 * [STOP_TIMEOUT_MS] after the last reader leaves.
 */
@Singleton
class WatchProgressStore
    @Inject
    constructor(
        private val viewHistory: ViewHistory,
        @ApplicationScope scope: CoroutineScope,
    ) {
        val entries: Flow<Map<String, WatchProgress>> =
            observeRows()
                .flowOn(Dispatchers.IO)
                .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)
                .filterNotNull()

        /** Rows for YouTube videos only, as watched checks read them: no music, no device files. */
        val videoProgress: Flow<List<WatchProgress>> =
            entries.map { rows -> rows.values.filter { !it.isMusic && !it.isLocal } }

        @OptIn(FlowPreview::class)
        private fun observeRows(): Flow<Map<String, WatchProgress>?> =
            flow {
                val table = WatchProgressTable()
                table.replaceAll(viewHistory.readAllProgress())
                emit(table.snapshot())
                merge(
                    viewHistory.changes,
                    viewHistory.invalidations().debounce(SETTLE_MS).map { null },
                ).collect { change ->
                    if (table.applyChange(change, viewHistory)) emit(table.snapshot())
                }
            }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L

            /** Lets an announced write's own delta land before the table is checked for strangers. */
            const val SETTLE_MS = 500L
        }
    }

/** The store's rows and how each change moves them. Not thread-safe: one collector owns it. */
internal class WatchProgressTable {
    private val rows = HashMap<String, WatchProgress>()

    fun replaceAll(all: List<WatchProgress>) {
        rows.clear()
        all.associateByTo(rows) { it.videoId }
    }

    /** Applies [change], or checks for unannounced writes when it is null. Returns whether any row changed. */
    suspend fun applyChange(
        change: HistoryChange?,
        history: ViewHistory,
    ): Boolean =
        when (change) {
            is HistoryChange.Rows -> {
                applyRows(change.videoIds, history.readProgressRows(change.videoIds))
            }

            HistoryChange.All -> {
                replaceAll(history.readAllProgress())
                true
            }

            null -> {
                if (fingerprint() == history.progressFingerprint()) {
                    false
                } else {
                    replaceAll(history.readAllProgress())
                    true
                }
            }
        }

    /** [found] holds the current rows for [videoIds]; an id missing from it was deleted. */
    fun applyRows(
        videoIds: Set<String>,
        found: List<WatchProgress>,
    ): Boolean {
        var changed = false
        val byId = found.associateBy { it.videoId }
        for (id in videoIds) {
            val row = byId[id]
            val previous = if (row == null) rows.remove(id) else rows.put(id, row)
            if (previous != row) changed = true
        }
        return changed
    }

    fun fingerprint(): WatchProgressFingerprint {
        var position = 0L
        var duration = 0L
        var timestamp = 0L
        var flags = 0L
        for (row in rows.values) {
            position += row.position
            duration += row.duration
            timestamp += row.timestamp
            flags += row.isMusic.bit() + 2 * row.isLocal.bit() + 4 * row.isShort.bit()
        }
        return WatchProgressFingerprint(rows.size.toLong(), position, duration, timestamp, flags)
    }

    fun snapshot(): Map<String, WatchProgress> = HashMap(rows)

    private fun Boolean.bit(): Long = if (this) 1L else 0L
}
