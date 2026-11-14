package io.github.aedev.flow.sync2.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.sync.protocol.SyncCollection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** The collections this device sends: everything it syncs but what it [leftOut]. */
internal fun sendable(leftOut: Set<String>): List<String> = SyncCollection.ANDROID_SYNCABLE.filterNot { it in leftOut }

/**
 * The collections still catching up after [before] becomes [after]: those already [pending], and those [after]
 * takes back, but none it leaves out again.
 */
internal fun catchingUpAfter(
    pending: Set<String>,
    before: Set<String>,
    after: Set<String>,
): Set<String> = (pending + (before - after)) - after

/**
 * What this device leaves out of every link it syncs (spec/protocol.md, Leaving collections out). A device
 * setting: it is never written to the store and doesn't follow a profile switch.
 */
@Singleton
class LeftOut
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val preferences = PlayerPreferences(context)

        val collections: Flow<Set<String>> = preferences.linkedLeftOut

        suspend fun current(): Set<String> = collections.first()

        /** Collections taken back that this device doesn't send yet: it still reads them from the links' snapshots. */
        suspend fun catchingUp(): Set<String> = preferences.linkedTakenBack.first()

        suspend fun caughtUp(collections: Set<String>) = preferences.clearLinkedTakenBack(collections)

        /** Saves [next] and syncs; a collection it takes back catches up before this device sends it again. */
        suspend fun set(next: Set<String>) {
            val before = current()
            if (next == before) return
            preferences.setLinkedLeftOut(next, catchingUpAfter(catchingUp(), before, next))
            LinkedSyncWorker.syncSoon(context)
        }
    }
