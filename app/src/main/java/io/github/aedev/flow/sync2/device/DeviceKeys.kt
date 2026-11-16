package io.github.aedev.flow.sync2.device

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.KeystoreSecretBox
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.data.profile.StoreScope
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// This device's keys for its current membership, whatever profile runs; the backup rules never copy this file.
internal val Context.linkedDeviceDataStore by safePreferencesDataStore(name = "linked_device", scope = StoreScope.DEVICE)
private val KEY_DEVICE = stringPreferencesKey("device_keys")

/** Where the device keys are kept, already opened from their platform protection. */
interface SecretSlot {
    suspend fun read(): String?

    suspend fun write(value: String)
}

class DamagedKeysException : Exception("The device keys can't be read")

@Serializable
private class StoredKeys(
    val sig: String,
    val kem: String,
)

private fun decode(value: String): MemberSecrets? =
    runCatching {
        val stored = Json.decodeFromString<StoredKeys>(value)
        MemberSecrets(SyncBytes.b64urlDecode(stored.sig), SyncBytes.b64urlDecode(stored.kem))
    }.getOrNull()?.takeIf { it.sigSeed.size == 32 && it.kemSecret.size == 32 }

private fun encode(keys: MemberSecrets): String =
    Json.encodeToString(StoredKeys.serializer(), StoredKeys(SyncBytes.b64urlEncode(keys.sigSeed), SyncBytes.b64urlEncode(keys.kemSecret)))

/**
 * Loads the keys, creating them on first use. Damaged keys are an error and are never replaced:
 * new keys would silently take this device out of its group.
 */
suspend fun deviceKeys(slot: SecretSlot): MemberSecrets {
    slot.read()?.let { return decode(it) ?: throw DamagedKeysException() }
    return freshDeviceKeys(slot)
}

/** New keys for a new membership (spec/crypto.md, Device keys), replacing any before them. */
suspend fun freshDeviceKeys(slot: SecretSlot): MemberSecrets {
    val keys = MemberSecrets(SyncCrypto.randomBytes(32), SyncCrypto.randomBytes(32))
    slot.write(encode(keys))
    return keys
}

@Singleton
class DeviceKeys
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val store = context.linkedDeviceDataStore
        private val mutex = Mutex()

        private val slot =
            object : SecretSlot {
                // A value that no longer opens (the Keystore key went with the app's data) reads as empty.
                override suspend fun read(): String? = store.data.first()[KEY_DEVICE]?.let(KeystoreSecretBox::open)

                override suspend fun write(value: String) {
                    store.edit { it[KEY_DEVICE] = KeystoreSecretBox.seal(value) }
                }
            }

        suspend fun load(): MemberSecrets = mutex.withLock { deviceKeys(slot) }

        /** Keys for a group this device is about to create, join or restore. */
        suspend fun fresh(): MemberSecrets = mutex.withLock { freshDeviceKeys(slot) }

        /** Deletes the keys once this device left its group or learnt it was removed. */
        suspend fun delete() {
            mutex.withLock { store.edit { it.remove(KEY_DEVICE) } }
        }
    }
