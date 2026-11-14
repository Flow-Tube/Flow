package io.github.aedev.flow.sync2.lan

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.data.profile.StoreScope
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// Never synced and never backed up: the times are this device's own.
private val Context.lanMetDataStore by safePreferencesDataStore(name = "linked_lan_met", scope = StoreScope.DEVICE)
private val GROUP = stringPreferencesKey("group")
private const val TAG = "LanMet"

/** Notes that this device synced with [did] of [group] at [at]; times kept for another group are dropped first. */
internal fun MutablePreferences.noteMet(
    group: String,
    did: String,
    at: Long,
) {
    if (this[GROUP] != group) {
        clear()
        this[GROUP] = group
    }
    this[longPreferencesKey(did)] = at
}

/** When this device last synced with each device of [group]; nothing for another group. */
internal fun Preferences.metIn(group: String): Map<String, Long> {
    if (this[GROUP] != group) return emptyMap()
    return asMap().mapNotNull { (key, value) -> (value as? Long)?.let { key.name to it } }.toMap()
}

/** When this device last synced with each member of its group on this network, across restarts. */
@Singleton
class LanMet
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val store = context.applicationContext.lanMetDataStore

        suspend fun met(
            group: String,
            did: String,
            at: Long,
        ) {
            try {
                store.edit { it.noteMet(group, did, at) }
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't note a LAN session", e)
            }
        }

        suspend fun times(group: String): Map<String, Long> = store.data.first().metIn(group)
    }
