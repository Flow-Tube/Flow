package io.github.aedev.flow.ui.screens.playon

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.sync2.lan.LanSync
import io.github.aedev.flow.sync2.live.ConsentAnswer
import io.github.aedev.flow.sync2.live.ConsentRequest
import io.github.aedev.flow.sync2.live.Handoff
import io.github.aedev.flow.sync2.live.LiveChannel
import io.github.aedev.flow.sync2.live.LiveConsent
import io.github.aedev.flow.sync2.live.LiveDevices
import io.github.aedev.flow.sync2.live.LiveMessage
import io.github.aedev.flow.sync2.live.LivePeer
import io.github.aedev.flow.sync2.live.LocalPlayback
import io.github.aedev.flow.sync2.live.NowPlaying
import io.github.aedev.flow.sync2.live.RemoteChoice
import io.github.aedev.flow.sync2.live.TrackDetails
import io.github.aedev.flow.sync2.live.deviceKind
import io.github.aedev.flow.sync2.service.LinkedActions
import io.github.aedev.flow.sync2.service.LinkedDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** How long a device's row says a move to it failed. */
private const val FAILED_MS = 4_000L

/** Whether the "Play on" sheet is open, shared by the buttons that open it and the shell that shows it. */
@Singleton
class PlayOnSheet
    @Inject
    constructor() {
        val open = MutableStateFlow(false)
    }

/** Where a device sits in the list (spec/live.md, Playing on another device). */
enum class PlayOnGroup { PLAYING, AVAILABLE, OFFLINE }

/**
 * A device of the group as the sheet shows it; [now] is what it plays, [seenAt] when it was last
 * heard of, [nearby] whether it was met on this network.
 */
data class PlayOnDevice(
    val did: String,
    val name: String,
    val kind: String,
    val thisDevice: Boolean,
    val group: PlayOnGroup,
    val now: LiveMessage.Now?,
    val seenAt: Long?,
    val nearby: Boolean = false,
)

/** Another device's session as the players show it. */
data class Elsewhere(
    val did: String,
    val name: String,
    val now: LiveMessage.Now,
) {
    val playing: Boolean get() = now.state == LiveMessage.PLAYING
}

/** What plays on the active device. */
data class PlayOnSession(
    val device: PlayOnDevice,
    val now: LiveMessage.Now,
)

/** The "Play on" sheet: the active session, the devices, and moving playback between them. */
@HiltViewModel
class PlayOnViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val actions: LinkedActions,
        private val channel: LiveChannel,
        private val live: LiveDevices,
        private val handoff: Handoff,
        private val playback: LocalPlayback,
        private val nowPlaying: NowPlaying,
        private val consent: LiveConsent,
        private val remote: RemoteChoice,
        private val details: TrackDetails,
        lan: LanSync,
        sheet: PlayOnSheet,
    ) : ViewModel() {
        private val local = MutableStateFlow<LiveMessage.Now?>(null)
        private val roster = MutableStateFlow<List<LinkedDevice>>(emptyList())
        private val moving = MutableStateFlow(false)
        private val target = MutableStateFlow<String?>(null)
        private val failure = MutableStateFlow<String?>(null)
        private val me = MutableStateFlow<String?>(null)

        private val names = MutableStateFlow<Map<String, String>>(emptyMap())

        val open: MutableStateFlow<Boolean> = sheet.open

        init {
            viewModelScope.launch { names.value = channel.names() }
        }

        /**
         * The session of the device the user chose to drive from here, which the players then show
         * and control (Spotify Connect style).
         */
        val elsewhere: StateFlow<Elsewhere?> =
            combine(remote.did, live.devices, names) { did, peers, known ->
                did?.let { peers[it]?.now }?.takeIf { it.video != null }?.let { Elsewhere(did, known[did] ?: did.take(8), it) }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /** Drives the device the players show as playing elsewhere. */
        fun controlElsewhere(cmd: String) {
            val did = remote.did.value ?: return
            act { handoff.control(did, cmd) }
        }

        /** Shown when the group can reach a device: the server's live channel is up, or a LAN peer is near. */
        val available: StateFlow<Boolean> =
            combine(actions.group, channel.connected, lan.nearby) { group, transports, nearby ->
                group != null && (transports.isNotEmpty() || nearby.isNotEmpty())
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        val busy: StateFlow<Boolean> = moving.asStateFlow()

        /** The device playback is being moved to, while the move runs. */
        val movingTo: StateFlow<String?> = target.asStateFlow()

        /** The device a move just failed to reach, for a few seconds. */
        val failed: StateFlow<String?> = failure.asStateFlow()

        val consentRequest: StateFlow<ConsentRequest?> = consent.request

        val controlledBy: SharedFlow<String> = handoff.controlledBy

        val musicStarted: SharedFlow<Unit> = playback.musicStarted

        val devices: StateFlow<List<PlayOnDevice>> =
            combine(
                roster,
                live.devices,
                live.online,
                lan.nearby,
                combine(local, me) { now, id -> now to id },
            ) { all, peers, _, nearby, (localNow, id) ->
                val at = System.currentTimeMillis()
                playOnDevices(all, peers, nearby, localNow, id, deviceKind(context)) { live.isOnline(it, at) }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        /** The active device's session: this device playing, else the device driven from here, else what this one holds. */
        val session: StateFlow<PlayOnSession?> =
            combine(devices, remote.did, live.devices) { list, driven, _ -> sessionOf(list, driven) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /** The device the sheet checks: the session's, else this one. */
        val activeDevice: StateFlow<String?> =
            combine(session, devices) { current, list -> current?.device?.did ?: list.firstOrNull { it.thisDevice }?.did }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        private fun sessionOf(
            list: List<PlayOnDevice>,
            driven: String?,
        ): PlayOnSession? {
            val mine = list.firstOrNull { it.thisDevice }
            val here = mine?.now?.takeIf { it.video != null }
            if (mine != null && here?.state == LiveMessage.PLAYING) return PlayOnSession(mine, here)
            live.controlled(driven)?.let { peer ->
                val now = peer.now ?: return@let
                // Before the sheet reads the roster, the live channel says enough about the device.
                val device =
                    list.firstOrNull { it.did == peer.did }
                        ?: PlayOnDevice(peer.did, nameOf(peer.did), peer.kind ?: "phone", false, PlayOnGroup.PLAYING, now, peer.heardAt)
                return PlayOnSession(device, now)
            }
            return if (mine != null && here != null) PlayOnSession(mine, here) else null
        }

        /**
         * Reads the devices once and what plays here, then again each time playback or the media
         * volume changes here, until the sheet closes. Other devices tell their own changes.
         */
        suspend fun watch() {
            me.value = channel.me()
            local.value = nowPlaying.snapshot()
            roster.value = runCatching { actions.devices() }.getOrDefault(roster.value)
            merge(nowPlaying.changes, volumeChanges(context))
                .conflate()
                .collect { local.value = nowPlaying.snapshot() }
        }

        /**
         * Picks [device]: this device pulls playback back; another one is driven from here, getting the
         * driven device's session, or this device's when this one plays or it holds nothing to drive.
         */
        fun playOn(device: PlayOnDevice) =
            act(to = device.did) {
                val driven = remote.did.value
                when {
                    device.thisDevice -> {
                        handoff.pull(driven)
                    }

                    driven != null && driven != device.did -> {
                        handoff.move(driven, device.did).also { if (it) remote.choose(device.did) }
                    }

                    local.value?.state != LiveMessage.PLAYING && device.now?.video != null -> {
                        remote.choose(device.did)
                        true
                    }

                    else -> {
                        handoff.transfer(device.did).also { if (it) remote.choose(device.did) }
                    }
                }
            }

        /** Drives the active session, here or on another device. */
        fun control(
            session: PlayOnSession,
            cmd: String,
            pos: Long? = null,
            vol: Int? = null,
        ) = act {
            if (session.device.thisDevice) {
                playback.command(LiveMessage.Cmd(session.device.did, cmd, pos, vol)) == null
            } else {
                remote.choose(session.device.did)
                handoff.control(session.device.did, cmd, pos, vol)
            }
        }

        /** A song's album art from the music service. */
        suspend fun musicArt(video: String): String? = details.songs(listOf(video))[video]?.highResThumbnailUrl

        fun answer(
            request: ConsentRequest,
            answer: ConsentAnswer,
        ) = consent.answer(request, answer)

        /** The name the user knows a device by. */
        fun nameOf(did: String): String = devices.value.firstOrNull { it.did == did }?.name ?: names.value[did] ?: did.take(8)

        /** Runs one action at a time; [to] names the device a move goes to, which its row shows. */
        private fun act(
            to: String? = null,
            block: suspend () -> Boolean,
        ) {
            if (moving.value) return
            viewModelScope.launch {
                moving.value = true
                target.value = to
                failure.value = null
                try {
                    val done = block()
                    local.value = nowPlaying.snapshot()
                    if (!done && to != null) showFailure(to)
                } finally {
                    target.value = null
                    moving.value = false
                }
            }
        }

        private fun showFailure(did: String) {
            failure.value = did
            viewModelScope.launch {
                delay(FAILED_MS)
                failure.compareAndSet(did, null)
            }
        }
    }

/**
 * The group's devices for the sheet: playing when their latest `now` plays, available when this
 * device can reach them, offline otherwise with when they were last seen (spec/live.md).
 */
internal fun playOnDevices(
    roster: List<LinkedDevice>,
    peers: Map<String, LivePeer>,
    nearby: Map<String, Long>,
    localNow: LiveMessage.Now?,
    me: String?,
    ownKind: String,
    online: (String) -> Boolean,
): List<PlayOnDevice> =
    roster.map { device ->
        val peer = peers[device.id]
        val mine = device.thisDevice || device.id == me
        val now = if (mine) localNow else peer?.now
        PlayOnDevice(
            did = device.id,
            name = device.name.ifEmpty { device.id.take(8) },
            kind = if (mine) ownKind else peer?.kind ?: if (device.platform == "android") "phone" else "desktop",
            thisDevice = mine,
            group =
                when {
                    now?.video != null && now.state == LiveMessage.PLAYING -> PlayOnGroup.PLAYING
                    mine || online(device.id) || device.id in nearby -> PlayOnGroup.AVAILABLE
                    else -> PlayOnGroup.OFFLINE
                },
            now = now,
            seenAt = listOfNotNull(device.seen, nearby[device.id], peer?.heardAt?.takeIf { it > 0 }).maxOrNull(),
            nearby = !mine && device.id in nearby,
        )
    }

/** Each change of the media volume, which the platform saves to system settings as it moves. */
private fun volumeChanges(context: Context): Flow<Unit> =
    callbackFlow {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }
