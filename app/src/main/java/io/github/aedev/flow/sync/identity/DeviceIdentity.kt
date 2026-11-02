package io.github.aedev.flow.sync.identity

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.data.profile.StoreScope
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A local edit's stamp: [nowMs], but never before [seen], the newest stamp applied, nor past the largest one. */
internal fun editStamp(
    nowMs: Long,
    seen: Long,
): Long = maxOf(nowMs, if (seen < Long.MAX_VALUE) seen + 1 else seen)

private val Context.syncIdentityDataStore by safePreferencesDataStore(name = "sync_identity", scope = StoreScope.DEVICE)

/**
 * Stable per-install identity for sync: a persisted [deviceId] (UUID v4, generated once), a
 * user-editable [deviceName], the [platform] tag, and [appVersion]. Backs the HELLO/HELLO_ACK
 * frames and the HLC node id.
 */
@Singleton
class DeviceIdentity
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val store = context.syncIdentityDataStore

        val platform: String = "android"
        val appVersion: String = BuildConfig.VERSION_NAME

        /** Read the persisted device id, generating + persisting one on first use. */
        suspend fun deviceId(): String {
            store.data.first()[KEY_DEVICE_ID]?.let { return it }
            val generated = UUID.randomUUID().toString()
            // Converge under concurrent first-use: keep whoever wrote first.
            return store.edit { it[KEY_DEVICE_ID] = it[KEY_DEVICE_ID] ?: generated }[KEY_DEVICE_ID]!!
        }

        suspend fun deviceName(): String = store.data.first()[KEY_DEVICE_NAME]?.takeIf { it.isNotBlank() } ?: defaultDeviceName()

        suspend fun setDeviceName(name: String) {
            store.edit { it[KEY_DEVICE_NAME] = name.trim().take(64) }
        }

        /**
         * When a local edit happens, for its stamp: never before a stamp this device has applied, so an
         * edit made after reading another device's change wins even when this clock is behind.
         */
        suspend fun editTime(): Long = editStamp(System.currentTimeMillis(), store.data.first()[KEY_CLOCK] ?: 0L)

        /** Remembers [physicalMs] if it is the newest time among the stamps this device has applied. */
        suspend fun observe(physicalMs: Long) {
            store.edit { if (physicalMs > (it[KEY_CLOCK] ?: 0L)) it[KEY_CLOCK] = physicalMs }
        }

        /** The 8-char HLC node id for this device. */
        suspend fun hlcNode(): String = Hlc.nodeFromDeviceId(deviceId())

        /** A fresh HLC clock seeded from this device's node id. */
        suspend fun newClock(): HlcClock = HlcClock(node = hlcNode())

        private fun defaultDeviceName(): String {
            val model = Build.MODEL?.trim().orEmpty()
            return if (model.isEmpty()) "Flow (Android)" else "Flow ($model)"
        }

        companion object {
            private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
            private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
            private val KEY_CLOCK = longPreferencesKey("clock_ms")
        }
    }
