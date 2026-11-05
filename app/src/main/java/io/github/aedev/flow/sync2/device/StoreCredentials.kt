package io.github.aedev.flow.sync2.device

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.KeystoreSecretBox
import io.github.aedev.flow.sync2.store.StoreLogin
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_WEBDAV = stringPreferencesKey("webdav_login")
private val KEY_PENDING = stringPreferencesKey("webdav_login_pending")

/**
 * A WebDAV login. [conditional]: the setup probe found the server honours `If-None-Match`.
 * [refused]: the server refused it, so nothing is sent with it until the user enters it again.
 */
@Serializable
data class WebDavLogin(
    val user: String,
    val password: String,
    val conditional: Boolean = false,
    val refused: Boolean = false,
)

/** The group's store credentials, sealed by the Keystore in the device store; never in settings, backups or sync. */
@Singleton
class StoreCredentials
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val store = context.linkedDeviceDataStore

        suspend fun webDav(): WebDavLogin? =
            store.data
                .first()[KEY_WEBDAV]
                ?.let(KeystoreSecretBox::open)
                ?.let { runCatching { Json.decodeFromString(WebDavLogin.serializer(), it) }.getOrNull() }

        suspend fun save(login: WebDavLogin) {
            store.edit { it[KEY_WEBDAV] = KeystoreSecretBox.seal(Json.encodeToString(WebDavLogin.serializer(), login)) }
        }

        /** The login that replaces the saved one once the folder refuses it (spec/protocol.md, Replacing a store's login). */
        suspend fun pending(): StoreLogin? =
            store.data
                .first()[KEY_PENDING]
                ?.let(KeystoreSecretBox::open)
                ?.let { runCatching { Json.decodeFromString(WebDavLogin.serializer(), it) }.getOrNull() }
                ?.let { StoreLogin(it.user, it.password) }

        suspend fun savePending(login: StoreLogin) {
            val sealed = KeystoreSecretBox.seal(Json.encodeToString(WebDavLogin.serializer(), WebDavLogin(login.user, login.password)))
            store.edit { it[KEY_PENDING] = sealed }
        }

        suspend fun forgetPending() {
            store.edit { it.remove(KEY_PENDING) }
        }

        suspend fun clear() {
            store.edit {
                it.remove(KEY_WEBDAV)
                it.remove(KEY_PENDING)
            }
        }
    }
