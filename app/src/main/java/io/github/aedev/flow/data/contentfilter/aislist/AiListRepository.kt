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

/**
 * How much of each list the device holds, for the settings status row. A count is null while its
 * switch is off or until that list has been downloaded on this device.
 */
data class AiListStatus(
    val channelCount: Int?,
    val artistCount: Int?,
    val updatedAtMs: Long,
)

internal data class AiListSettings(
    val blockEnabled: Boolean,
    val warnEnabled: Boolean,
    val musicEnabled: Boolean,
    val allowed: List<AllowedAiChannel>,
) {
    /** The AiSList channel list is needed. */
    val channelsOn: Boolean get() = blockEnabled || warnEnabled

    val isOn: Boolean get() = channelsOn || musicEnabled
}

/** The two lists the app follows: AiSList channels and AI music artists. */
internal enum class AiListKind(
    val listId: String,
    val fileName: String,
) {
    CHANNELS(AI_LIST_ID, AiListStore.FILE_NAME),
    MUSIC(AI_MUSIC_LIST_ID, AiListStore.MUSIC_FILE_NAME),
    ;

    fun isOn(settings: AiListSettings): Boolean = if (this == CHANNELS) settings.channelsOn else settings.musicEnabled
}

/** A list as lookup sets, built once per stored version and kept in memory while a switch is on. */
private class AiListData(
    val block: AiListSets,
    val warn: AiListSets,
    val count: Int,
    val updatedAtMs: Long,
    val sourceId: String,
)

/**
 * The AI content filter: AiSList channels (issue #865) and AI music artists. Off by default: while
 * every switch is off nothing is read, fetched or scheduled, and each list is fetched only while
 * its own switch is on. The lists are device-wide and never backed up or synced; the switches and
 * the allowlist are ordinary per-profile settings.
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
        private val stores = AiListKind.entries.associateWith { AiListStore(directory, it.fileName) }
        private val index = ChannelHandleIndex(directory, scope)
        private val data = AiListKind.entries.associateWith { MutableStateFlow<AiListData?>(null) }
        private val loadLock = Mutex()
        private var loadedFromDisk = false

        internal val settings: Flow<AiListSettings> =
            combine(
                preferences.aiListBlockEnabled,
                preferences.aiListWarnEnabled,
                preferences.aiMusicBlockEnabled,
                preferences.aiListAllowed,
            ) { block, warn, music, allowed ->
                AiListSettings(block, warn, music, decodeAllowed(allowed))
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
                            combine(data.getValue(AiListKind.CHANNELS), data.getValue(AiListKind.MUSIC), subscribedIds) {
                                channels,
                                music,
                                subscribed,
                                ->
                                build(settings, channels, music, subscribed)
                            }
                        }
                    }
                }.flowOn(Dispatchers.Default)
                .stateIn(scope, SharingStarted.Eagerly, AiListMatcher.NONE)

        val blockEnabled: Flow<Boolean> = preferences.aiListBlockEnabled

        val warnEnabled: Flow<Boolean> = preferences.aiListWarnEnabled

        val musicEnabled: Flow<Boolean> = preferences.aiMusicBlockEnabled

        val allowedChannels: Flow<List<AllowedAiChannel>> = settings.map { it.allowed }.distinctUntilChanged()

        /** Null while every switch is off or until a list in use has been downloaded on this device. */
        val status: Flow<AiListStatus?> =
            settings
                .map { it.channelsOn to it.musicEnabled }
                .distinctUntilChanged()
                .flatMapLatest { (channelsOn, musicOn) ->
                    if (!channelsOn && !musicOn) {
                        flowOf(null)
                    } else {
                        flow {
                            ensureLoaded()
                            emit(Unit)
                        }.flatMapLatest {
                            combine(data.getValue(AiListKind.CHANNELS), data.getValue(AiListKind.MUSIC)) { channels, music ->
                                val shown = listOfNotNull(channels.takeIf { channelsOn }, music.takeIf { musicOn })
                                if (shown.isEmpty()) {
                                    null
                                } else {
                                    AiListStatus(
                                        channelCount = channels?.count?.takeIf { channelsOn },
                                        artistCount = music?.count?.takeIf { musicOn },
                                        updatedAtMs = shown.maxOf { it.updatedAtMs },
                                    )
                                }
                            }
                        }
                    }
                }.flowOn(Dispatchers.IO)

        init {
            scope.launch { followSchedule() }
            scope.launch { followSource() }
        }

        fun currentMatcher(): AiListMatcher = matcher.value

        suspend fun setBlockEnabled(enabled: Boolean) = preferences.setAiListBlockEnabled(enabled)

        suspend fun setWarnEnabled(enabled: Boolean) = preferences.setAiListWarnEnabled(enabled)

        suspend fun setMusicEnabled(enabled: Boolean) = preferences.setAiMusicBlockEnabled(enabled)

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
            val list = data.getValue(AiListKind.CHANNELS).value ?: return AiVerdict.NONE
            return AiListMatcher(true, true, list.block, list.warn, emptySet(), emptySet(), index).verdict(channelId, handle)
        }

        /**
         * One update run of every list whose switch is on, for the worker. Makes no request while
         * every switch is off. Asks for a retry when any list's run does.
         */
        internal suspend fun refresh(): AiListOutcome {
            val current = settings.first()
            if (!current.isOn) return AiListOutcome.UP_TO_DATE
            ensureLoaded()
            val outcomes = AiListKind.entries.filter { it.isOn(current) }.map { refresh(it) }
            return if (AiListOutcome.RETRY in outcomes) AiListOutcome.RETRY else outcomes.first()
        }

        private suspend fun refresh(kind: AiListKind): AiListOutcome {
            val current = source.first()
            val update =
                withContext(Dispatchers.IO) {
                    AiListUpdater(OkHttpAiListFetcher(client, current.baseUrl), stores.getValue(kind), current, kind.listId).run()
                }
            Log.d(
                TAG,
                "Refresh ${kind.listId} ${update.outcome} at version ${update.list?.version}" +
                    update.reason?.let { ": $it" }.orEmpty(),
            )
            val committed =
                update.outcome == AiListOutcome.SNAPSHOT ||
                    update.outcome == AiListOutcome.DELTAS ||
                    update.outcome == AiListOutcome.FALLBACK_SNAPSHOT
            if (committed && source.first() == current) {
                update.list?.let { list -> data.getValue(kind).value = withContext(Dispatchers.Default) { list.toData() } }
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
                    for (kind in AiListKind.entries) {
                        val held = data.getValue(kind)
                        stores
                            .getValue(kind)
                            .load()
                            ?.takeIf { (it.source ?: AiListSource.DEFAULT.id) == current.id }
                            ?.let { stored -> if (held.value == null) held.value = stored.toData() }
                    }
                }
            }
        }

        /**
         * Runs the worker while any switch is on. A list switched on downloads at once; at app start
         * only a list that is on but was never downloaded does.
         */
        private suspend fun followSchedule() {
            var previous: Set<AiListKind>? = null
            settings
                .map { settings -> AiListKind.entries.filterTo(HashSet()) { it.isOn(settings) } }
                .distinctUntilChanged()
                .collect { on ->
                    val before = previous
                    previous = on
                    when {
                        on.isEmpty() -> {
                            if (before != null) AiListRefreshWorker.cancel(context)
                        }

                        before == null -> {
                            val missing = withContext(Dispatchers.IO) { on.any { !stores.getValue(it).exists() } }
                            AiListRefreshWorker.schedule(context, refreshNow = missing)
                        }

                        else -> {
                            AiListRefreshWorker.schedule(context, refreshNow = (on - before).isNotEmpty())
                        }
                    }
                }
        }

        /** A new source starts empty and downloads at once; the old list never filters for it. */
        private suspend fun followSource() {
            var previous: AiListSource? = null
            source.collect { current ->
                val changed = previous != null && previous != current
                previous = current
                if (!changed) return@collect
                for (held in data.values) if (held.value?.sourceId != current.id) held.value = null
                if (settings.first().isOn) AiListRefreshWorker.schedule(context, refreshNow = true)
            }
        }

        private fun build(
            settings: AiListSettings,
            channels: AiListData?,
            music: AiListData?,
            subscribed: Set<String>,
        ): AiListMatcher =
            AiListMatcher(
                blockEnabled = settings.blockEnabled,
                warnEnabled = settings.warnEnabled,
                block = channels?.block ?: AiListSets.EMPTY,
                warn = channels?.warn ?: AiListSets.EMPTY,
                exemptChannelIds = subscribed + settings.allowed.map { it.id },
                exemptHandles = settings.allowed.mapNotNullTo(HashSet()) { channel -> channel.handle?.let(AiHandle::normalize) },
                index = index,
                musicEnabled = settings.musicEnabled,
                music = music?.block ?: AiListSets.EMPTY,
            )

        private fun StoredAiList.toData() =
            AiListData(
                block = AiListSets(block.handles.toHashSet(), block.ids.toHashSet()),
                warn = AiListSets(warn.handles.toHashSet(), warn.ids.toHashSet()),
                count = channelCount,
                updatedAtMs = updatedAtMs,
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
