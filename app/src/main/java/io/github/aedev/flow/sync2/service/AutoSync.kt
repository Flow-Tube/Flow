package io.github.aedev.flow.sync2.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** What asked for a sync. */
internal enum class SyncTrigger {
    /** Sync now. */
    MANUAL,

    /** A change the user just made that only takes effect once synced, such as which profiles sync. */
    FOLLOW_UP,
    OPEN,
    LEAVE,
    HOURLY,
    LAN_SEARCH,
    LAN_MEETING,
    SERVER_CHANGE,
}

/** Only Sync now and the syncs a user's own change needs run while this device doesn't sync automatically. */
internal fun SyncTrigger.runs(autoSync: Boolean): Boolean = autoSync || this == SyncTrigger.MANUAL || this == SyncTrigger.FOLLOW_UP

/** Which trigger a [LinkedSyncWorker] run stands for, from how it was enqueued. */
internal fun workerTrigger(
    open: Boolean,
    lan: Boolean,
    soon: Boolean,
    announce: Boolean,
    automatic: Boolean,
): SyncTrigger =
    when {
        lan -> SyncTrigger.LAN_SEARCH
        open -> SyncTrigger.OPEN
        !soon -> SyncTrigger.HOURLY
        announce -> SyncTrigger.MANUAL
        automatic -> SyncTrigger.LEAVE
        else -> SyncTrigger.FOLLOW_UP
    }

/** This device's "Sync automatically" switch. It is a device setting: it never syncs and ignores profiles. */
@Singleton
class AutoSync
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val preferences = PlayerPreferences(context)

        val enabled: Flow<Boolean> = preferences.linkedAutoSync

        internal suspend fun allows(trigger: SyncTrigger): Boolean = trigger.runs(enabled.first())

        /** Off stops the hourly runs too, so a device that doesn't sync on its own isn't woken for nothing. */
        suspend fun set(on: Boolean) {
            preferences.setLinkedAutoSync(on)
            if (on) LinkedSyncWorker.schedule(context) else LinkedSyncWorker.cancelAutomatic(context)
        }
    }
