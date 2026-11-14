package io.github.aedev.flow.data.contentfilter.aislist

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A channel the viewer chose to see despite the list; the name lets Hidden from you show it offline. */
@Serializable
data class AllowedAiChannel(
    val id: String,
    val name: String,
    val handle: String? = null,
)

/** How much of the list the device holds, for the settings status row. */
data class AiListStatus(
    val channelCount: Int,
    val updatedAtMs: Long,
)

internal data class AiListSettings(
    val blockEnabled: Boolean,
    val warnEnabled: Boolean,
    val allowed: List<AllowedAiChannel>,
) {
    val isOn: Boolean get() = blockEnabled || warnEnabled
}

/** The list as lookup sets, built once per stored version and kept in memory while a switch is on. */
private class AiListData(
    val block: AiListSets,
    val warn: AiListSets,
    val status: AiListStatus,
    val sourceId: String,
)

/**
 * The AiSList channel filter (issue #865). Off by default: while both switches are off nothing is
 * read, fetched or scheduled. The list itself is device-wide and never backed up or synced; the
 * switches and the allowlist are ordinary per-profile settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AiListRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val preferences: PlayerPreferences,
        private val subscriptions: SubscriptionRepository,
        private val client: OkHttpClient,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val directory = File(context.noBackupFilesDir, DIRECTORY)
        private val store = AiListStore(directory)
        private val index = ChannelHandleIndex(directory, scope)
        private val data = MutableStateFlow<AiListData?>(null)
        private val loadLock = Mutex()
        private var loadedFromDisk = false

        internal val settings: Flow<AiListSettings> =
            combine(preferences.aiListBlockEnabled, preferences.aiListWarnEnabled, preferences.aiListAllowed) { block, warn, allowed ->
                AiListSettings(block, warn, decodeAllowed(allowed))
            }.distinctUntilChanged()

        /** Where the list is downloaded from; Flow's own mirror unless the viewer set another. */
        val source: Flow<AiListSource> =
            preferences.aiListSource
                .map { (url, key) -> AiListSource.from(url, key) }
                .distinctUntilChanged()

        private val subscribedIds: Flow<Set<String>> =
            subscriptions
                .getAllSubscriptions()
                .map { list -> list.mapTo(HashSet()) { it.channelId } }
                .distinctUntilChanged()

        /**
         * Hot because the feed filters read it synchronously from non-UI code. While the filter is
         * off it holds [AiListMatcher.NONE] and follows only the two switches.
         */
        val matcher: StateFlow<AiListMatcher> =
            settings
                .flatMapLatest { settings ->
                    if (!settings.isOn) {
                        flowOf(AiListMatcher.NONE)
                    } else {
                        flow {
                            ensureLoaded()
                            emit(Unit)
                        }.flatMapLatest {
                            combine(data.filterNotNull(), subscribedIds) { list, subscribed -> build(settings, list, subscribed) }
                        }
                    }
                }.flowOn(Dispatchers.Default)
                .stateIn(scope, SharingStarted.Eagerly, AiListMatcher.NONE)

        val blockEnabled: Flow<Boolean> = preferences.aiListBlockEnabled

        val warnEnabled: Flow<Boolean> = preferences.aiListWarnEnabled

        val allowedChannels: Flow<List<AllowedAiChannel>> = settings.map { it.allowed }.distinctUntilChanged()

        /** Null while the filter is off or until a list has been downloaded on this device. */
        val status: Flow<AiListStatus?> =
            settings
                .map { it.isOn }
                .distinctUntilChanged()
                .flatMapLatest { on ->
                    if (!on) {
                        flowOf(null)
                    } else {
                        flow {
                            ensureLoaded()
                            emit(Unit)
                        }.flatMapLatest { data.map { it?.status } }
                    }
                }.flowOn(Dispatchers.IO)

        init {
            scope.launch { followSchedule() }
            scope.launch { followSource() }
        }

        fun currentMatcher(): AiListMatcher = matcher.value

        suspend fun setBlockEnabled(enabled: Boolean) = preferences.setAiListBlockEnabled(enabled)

        suspend fun setWarnEnabled(enabled: Boolean) = preferences.setAiListWarnEnabled(enabled)

        suspend fun allow(channel: AllowedAiChannel) =
            preferences.updateAiListAllowed { raw ->
                encodeAllowed(decodeAllowed(raw).filterNot { it.id == channel.id } + channel)
            }

        suspend fun disallow(channelId: String) =
            preferences.updateAiListAllowed { raw ->
                encodeAllowed(decodeAllowed(raw).filterNot { it.id == channelId })
            }

        /**
         * Saves a custom source, or goes back to Flow's own mirror when [url] is blank. Returns false,
         * saving nothing, when the URL is not https or the key is not an Ed25519 public key.
         */
        suspend fun setSource(
            url: String,
            key: String,
        ): Boolean {
            if (url.isBlank()) {
                preferences.setAiListSource(null, null)
                return true
            }
            val normalized = AiListSource.normalizeUrl(url) ?: return false
            if (key.isNotBlank() && AiListSource.decodeKey(key) == null) return false
            val isDefault = normalized == AiListSource.DEFAULT_URL && key.isBlank()
            preferences.setAiListSource(normalized.takeUnless { isDefault }, key.trim().takeUnless { isDefault })
            return true
        }

        /** Which list [channelId] or [handle] is on, whatever the switches say; for reporting. */
        internal suspend fun listing(
            channelId: String,
            handle: String?,
        ): AiVerdict {
            ensureLoaded()
            val list = data.value ?: return AiVerdict.NONE
            return AiListMatcher(true, true, list.block, list.warn, emptySet(), emptySet(), index).verdict(channelId, handle)
        }

        /** One update run, for the worker. Makes no request while both switches are off. */
        internal suspend fun refresh(): AiListOutcome {
            if (!settings.first().isOn) return AiListOutcome.UP_TO_DATE
            ensureLoaded()
            val current = source.first()
            val update =
                withContext(Dispatchers.IO) { AiListUpdater(OkHttpAiListFetcher(client, current.baseUrl), store, current).run() }
            Log.d(TAG, "Refresh ${update.outcome} at version ${update.list?.version}${update.reason?.let { ": $it" }.orEmpty()}")
            val committed =
                update.outcome == AiListOutcome.SNAPSHOT ||
                    update.outcome == AiListOutcome.DELTAS ||
                    update.outcome == AiListOutcome.FALLBACK_SNAPSHOT
            if (committed && source.first() == current) {
                update.list?.let { data.value = withContext(Dispatchers.Default) { it.toData() } }
            }
            return update.outcome
        }

        private suspend fun ensureLoaded() {
            loadLock.withLock {
                if (loadedFromDisk) return
                loadedFromDisk = true
                withContext(Dispatchers.IO) {
                    index.load()
                    val current = source.first()
                    store
                        .load()
                        ?.takeIf { (it.source ?: AiListSource.DEFAULT.id) == current.id }
                        ?.let { stored -> if (data.value == null) data.value = stored.toData() }
                }
            }
        }

        private suspend fun followSchedule() {
            var first = true
            settings
                .map { it.isOn }
                .distinctUntilChanged()
                .collect { on ->
                    when {
                        !on -> if (!first) AiListRefreshWorker.cancel(context)
                        first -> AiListRefreshWorker.schedule(context, refreshNow = !withContext(Dispatchers.IO) { store.exists() })
                        else -> AiListRefreshWorker.schedule(context, refreshNow = true)
                    }
                    first = false
                }
        }

        /** A new source starts empty and downloads at once; the old list never filters for it. */
        private suspend fun followSource() {
            var previous: AiListSource? = null
            source.collect { current ->
                val changed = previous != null && previous != current
                previous = current
                if (!changed) return@collect
                if (data.value?.sourceId != current.id) data.value = null
                if (settings.first().isOn) AiListRefreshWorker.schedule(context, refreshNow = true)
            }
        }

        private fun build(
            settings: AiListSettings,
            list: AiListData,
            subscribed: Set<String>,
        ): AiListMatcher =
            AiListMatcher(
                blockEnabled = settings.blockEnabled,
                warnEnabled = settings.warnEnabled,
                block = list.block,
                warn = list.warn,
                exemptChannelIds = subscribed + settings.allowed.map { it.id },
                exemptHandles = settings.allowed.mapNotNullTo(HashSet()) { channel -> channel.handle?.let(AiHandle::normalize) },
                index = index,
            )

        private fun StoredAiList.toData() =
            AiListData(
                block = AiListSets(block.handles.toHashSet(), block.ids.toHashSet()),
                warn = AiListSets(warn.handles.toHashSet(), warn.ids.toHashSet()),
                status = AiListStatus(channelCount, updatedAtMs),
                sourceId = this.source ?: AiListSource.DEFAULT.id,
            )

        private fun decodeAllowed(raw: String?): List<AllowedAiChannel> =
            raw?.let { runCatching { aiListJson.decodeFromString(ALLOWED_SERIALIZER, it) }.getOrNull() }.orEmpty()

        private fun encodeAllowed(channels: List<AllowedAiChannel>): String = aiListJson.encodeToString(ALLOWED_SERIALIZER, channels)

        private companion object {
            const val TAG = "AiListRepository"
            const val DIRECTORY = "aislist"
            val ALLOWED_SERIALIZER = ListSerializer(AllowedAiChannel.serializer())
        }
    }
