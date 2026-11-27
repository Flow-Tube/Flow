package io.github.aedev.flow.data.scrobble

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.KeystoreSecretBox
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.data.profile.StoreScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.scrobbleDataStore by safePreferencesDataStore(name = "scrobbling", scope = StoreScope.PROFILE_PRIVATE)

data class ScrobbleSettings(
    val accounts: Map<ScrobbleService, ScrobbleAccount> = emptyMap(),
    val ownKeyEnabled: Boolean = false,
    val ownKeys: AudioscrobblerKeys = AudioscrobblerKeys("", ""),
    val nowPlaying: Boolean = true,
    val scrobbleLocal: Boolean = false,
    val sendLikes: Boolean = false,
    val webhooks: List<ScrobbleWebhook> = emptyList(),
    val pending: Map<ScrobbleTarget, Int> = emptyMap(),
) {
    val targets: List<ScrobbleTarget>
        get() = accounts.keys.map(ScrobbleTarget::Service) + webhooks.map { ScrobbleTarget.Webhook(it.id) }
}

/**
 * Accounts, webhooks, the viewer's own Last.fm key, the options, and the listens still waiting to be
 * sent. Session keys, tokens, the API secret and webhook addresses are sealed with the Keystore before
 * they are written.
 */
@Singleton
class ScrobbleStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val json = Json { ignoreUnknownKeys = true }
        private val ownKeyEnabledKey = booleanPreferencesKey("lastfm_own_key_enabled")
        private val ownApiKeyKey = stringPreferencesKey("lastfm_own_api_key")
        private val ownSecretKey = stringPreferencesKey("lastfm_own_secret")
        private val nowPlayingKey = booleanPreferencesKey("now_playing")
        private val scrobbleLocalKey = booleanPreferencesKey("scrobble_local")
        private val sendLikesKey = booleanPreferencesKey("send_likes")
        private val webhooksKey = stringPreferencesKey("webhooks")

        private fun nameKey(service: ScrobbleService) = stringPreferencesKey("${service.name}_user")

        private fun secretKey(service: ScrobbleService) = stringPreferencesKey("${service.name}_secret")

        private fun ScrobbleTarget.prefix(): String =
            when (this) {
                is ScrobbleTarget.Service -> service.name
                is ScrobbleTarget.Webhook -> "webhook_$id"
            }

        private fun queueKey(target: ScrobbleTarget) = stringPreferencesKey("${target.prefix()}_queue")

        private fun lovesKey(target: ScrobbleTarget) = stringPreferencesKey("${target.prefix()}_loves")

        val settings: Flow<ScrobbleSettings> =
            context.scrobbleDataStore.data
                .map { preferences ->
                    val webhooks = preferences.webhooks()
                    val targets =
                        ScrobbleService.entries.map(ScrobbleTarget::Service) + webhooks.map { ScrobbleTarget.Webhook(it.id) }
                    ScrobbleSettings(
                        accounts =
                            ScrobbleService.entries
                                .mapNotNull { service ->
                                    preferences.account(service)?.let { service to it }
                                }.toMap(),
                        ownKeyEnabled = preferences[ownKeyEnabledKey] ?: false,
                        ownKeys =
                            AudioscrobblerKeys(
                                apiKey = preferences[ownApiKeyKey].orEmpty(),
                                secret = KeystoreSecretBox.open(preferences[ownSecretKey]),
                            ),
                        nowPlaying = preferences[nowPlayingKey] ?: true,
                        scrobbleLocal = preferences[scrobbleLocalKey] ?: false,
                        sendLikes = preferences[sendLikesKey] ?: false,
                        webhooks = webhooks,
                        pending = targets.associateWith { preferences.queue(it).size + preferences.loves(it).size },
                    )
                }.flowOn(Dispatchers.IO)

        suspend fun current(): ScrobbleSettings = settings.first()

        suspend fun setAccount(
            service: ScrobbleService,
            account: ScrobbleAccount?,
        ) {
            val sealed = account?.let { withContext(Dispatchers.IO) { KeystoreSecretBox.seal(it.secret) } }
            context.scrobbleDataStore.edit { preferences ->
                if (account == null || sealed == null) {
                    preferences.remove(nameKey(service))
                    preferences.remove(secretKey(service))
                    preferences.remove(queueKey(ScrobbleTarget.Service(service)))
                    preferences.remove(lovesKey(ScrobbleTarget.Service(service)))
                } else {
                    preferences[nameKey(service)] = account.userName
                    preferences[secretKey(service)] = sealed
                }
            }
        }

        suspend fun setOwnKeys(
            enabled: Boolean,
            keys: AudioscrobblerKeys,
        ) {
            val sealed = withContext(Dispatchers.IO) { KeystoreSecretBox.seal(keys.secret.trim()) }
            context.scrobbleDataStore.edit { preferences ->
                preferences[ownKeyEnabledKey] = enabled
                preferences[ownApiKeyKey] = keys.apiKey.trim()
                preferences[ownSecretKey] = sealed
            }
        }

        suspend fun setNowPlaying(enabled: Boolean) {
            context.scrobbleDataStore.edit { it[nowPlayingKey] = enabled }
        }

        suspend fun setScrobbleLocal(enabled: Boolean) {
            context.scrobbleDataStore.edit { it[scrobbleLocalKey] = enabled }
        }

        suspend fun setSendLikes(enabled: Boolean) {
            context.scrobbleDataStore.edit { it[sendLikesKey] = enabled }
        }

        suspend fun enqueueLove(
            target: ScrobbleTarget,
            love: LoveEntry,
        ) {
            context.scrobbleDataStore.edit { preferences ->
                preferences[lovesKey(target)] = json.encodeToString((preferences.loves(target) + love).takeLast(MAX_QUEUE))
            }
        }

        suspend fun pendingLoves(target: ScrobbleTarget): List<LoveEntry> =
            context.scrobbleDataStore.data
                .first()
                .loves(target)

        suspend fun dropLove(
            target: ScrobbleTarget,
            love: LoveEntry,
        ) {
            context.scrobbleDataStore.edit { preferences ->
                val loves = preferences.loves(target)
                val index = loves.indexOf(love)
                if (index >= 0) preferences[lovesKey(target)] = json.encodeToString(loves.filterIndexed { i, _ -> i != index })
            }
        }

        suspend fun enqueue(
            target: ScrobbleTarget,
            entry: ScrobbleEntry,
        ) {
            context.scrobbleDataStore.edit { preferences ->
                preferences[queueKey(target)] = json.encodeToString((preferences.queue(target) + entry).takeLast(MAX_QUEUE))
            }
        }

        suspend fun pending(target: ScrobbleTarget): List<ScrobbleEntry> =
            context.scrobbleDataStore.data
                .first()
                .queue(target)

        /** Removes the [sent] entries from the head of the queue, leaving anything queued since. */
        suspend fun drop(
            target: ScrobbleTarget,
            sent: List<ScrobbleEntry>,
        ) {
            context.scrobbleDataStore.edit { preferences ->
                val queue = preferences.queue(target)
                val remaining = if (queue.take(sent.size) == sent) queue.drop(sent.size) else queue - sent.toSet()
                preferences[queueKey(target)] = json.encodeToString(remaining)
            }
        }

        /** Adds [webhook], or replaces the one with its id. */
        suspend fun saveWebhook(webhook: ScrobbleWebhook) {
            val sealed = withContext(Dispatchers.IO) { webhook.copy(url = KeystoreSecretBox.seal(webhook.url.trim())) }
            context.scrobbleDataStore.edit { preferences ->
                val stored = preferences.storedWebhooks()
                val updated =
                    if (stored.any { it.id == webhook.id }) {
                        stored.map { if (it.id == webhook.id) sealed else it }
                    } else {
                        stored + sealed
                    }
                preferences[webhooksKey] = json.encodeToString(updated)
            }
        }

        /** Removes the webhook and everything still queued for it. */
        suspend fun removeWebhook(id: String) {
            context.scrobbleDataStore.edit { preferences ->
                preferences[webhooksKey] = json.encodeToString(preferences.storedWebhooks().filterNot { it.id == id })
                preferences.remove(queueKey(ScrobbleTarget.Webhook(id)))
                preferences.remove(lovesKey(ScrobbleTarget.Webhook(id)))
            }
        }

        private fun Preferences.storedWebhooks(): List<ScrobbleWebhook> =
            this[webhooksKey]
                ?.let { runCatching { json.decodeFromString<List<ScrobbleWebhook>>(it) }.getOrNull() }
                .orEmpty()

        private fun Preferences.webhooks(): List<ScrobbleWebhook> =
            storedWebhooks().mapNotNull { stored ->
                KeystoreSecretBox.open(stored.url).takeIf { it.isNotEmpty() }?.let { stored.copy(url = it) }
            }

        private fun Preferences.account(service: ScrobbleService): ScrobbleAccount? {
            val sealed = this[secretKey(service)] ?: return null
            val secret = KeystoreSecretBox.open(sealed).takeIf { it.isNotEmpty() } ?: return null
            return ScrobbleAccount(userName = this[nameKey(service)].orEmpty(), secret = secret)
        }

        private fun Preferences.loves(target: ScrobbleTarget): List<LoveEntry> =
            this[lovesKey(target)]
                ?.let { runCatching { json.decodeFromString<List<LoveEntry>>(it) }.getOrNull() }
                .orEmpty()

        private fun Preferences.queue(target: ScrobbleTarget): List<ScrobbleEntry> =
            this[queueKey(target)]
                ?.let { runCatching { json.decodeFromString<List<ScrobbleEntry>>(it) }.getOrNull() }
                .orEmpty()

        private companion object {
            const val MAX_QUEUE = 2_000
        }
    }
