package io.github.aedev.flow.sync2.service

import android.os.SystemClock
import android.util.Log
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.changes.COLLECTION_TABLES
import io.github.aedev.flow.sync2.changes.LocalChanges
import io.github.aedev.flow.sync2.changes.PushSchedule
import io.github.aedev.flow.sync2.changes.TableChanges
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.live.LiveDevices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends what changes on this device soon after it changes, while the app is in the foreground and
 * another device of the group can be reached: over the server's live channel or a LAN session.
 * Leaving the app, the hourly sync and Sync now send everything else, as before.
 */
@Singleton
class LinkedPush
    @Inject
    constructor(
        private val sync: LinkedSync,
        private val changes: LocalChanges,
        private val tables: TableChanges,
        private val devices: LiveDevices,
        private val keys: DeviceKeys,
        private val leftOut: LeftOut,
    ) {
        @Volatile
        private var schedule: PushSchedule? = null

        /** A change to [collection] made on this device: the one place every trigger of a push goes through. */
        fun onLocalChange(collection: String) {
            schedule?.changed(collection)
        }

        /** Pushes changes until cancelled, which drops what still waits: leaving the app sends it. */
        suspend fun run() {
            val me =
                try {
                    SyncBytes.toHex(keys.load().did)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Pushing on change unavailable: ${e.linkedError()}")
                    return
                }
            val pushes = PushSchedule(SystemClock::elapsedRealtime)
            schedule = pushes
            try {
                coroutineScope {
                    // What a sync applies is another device's change coming in, not one to send back.
                    launch { changes.stored().collect { if (!sync.syncing.value) onLocalChange(it) } }
                    launch { changes.historySettles().collect { onLocalChange(SyncCollection.WATCH_HISTORY) } }
                    // The server lists this device among those connected, so only another one counts.
                    pushes.run(ready = { devices.online.first { (it - me).isNotEmpty() } }, push = ::push)
                }
            } finally {
                schedule = null
            }
        }

        private suspend fun push(collections: Set<String>): Boolean {
            val unchanged = tables.unchanged()
            val left = leftOut.current()
            val due = collections.filterNot { (it in COLLECTION_TABLES && it in unchanged) || it in left }
            if (due.isEmpty()) {
                Log.d(TAG, "Nothing to push after a change to ${collections.joinToString()}")
                return false
            }
            Log.d(TAG, "Pushing after a change to ${due.joinToString()}")
            sync.syncNow()
            return true
        }

        private companion object {
            const val TAG = "LinkedPush"
        }
    }
