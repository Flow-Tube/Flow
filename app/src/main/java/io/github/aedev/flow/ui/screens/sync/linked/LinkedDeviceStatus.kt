package io.github.aedev.flow.ui.screens.sync.linked

import io.github.aedev.flow.sync2.live.LivePeer
import io.github.aedev.flow.sync2.service.LinkedDevice

/** What the live channel says of a device that can be reached now: the title it plays, if it plays. */
internal data class DevicePresence(
    val playing: String? = null,
)

/** The one fact a device's row leads with. */
internal sealed interface DeviceStatus {
    data object ThisDevice : DeviceStatus

    data class Playing(
        val title: String,
    ) : DeviceStatus

    data class Online(
        val platform: String,
    ) : DeviceStatus

    data class Nearby(
        val platform: String,
        val at: Long,
    ) : DeviceStatus

    data class Synced(
        val platform: String,
        val at: Long,
    ) : DeviceStatus

    data class Platform(
        val platform: String,
    ) : DeviceStatus
}

/** This device, then playing, online, on this network, when it last synced, and its platform when nothing more is known. */
internal fun deviceStatusOf(
    device: LinkedDevice,
    presence: Map<String, DevicePresence>,
    nearby: Map<String, Long>,
): DeviceStatus {
    val live = presence[device.id]
    val met = nearby[device.id]
    return when {
        device.thisDevice -> DeviceStatus.ThisDevice
        live?.playing != null -> DeviceStatus.Playing(live.playing)
        live != null -> DeviceStatus.Online(device.platform)
        met != null -> DeviceStatus.Nearby(device.platform, met)
        device.seen != null -> DeviceStatus.Synced(device.platform, device.seen)
        else -> DeviceStatus.Platform(device.platform)
    }
}

/** The devices that can be reached at [at], with what each plays; a `now` from a device out of reach says nothing. */
internal fun devicePresence(
    peers: Map<String, LivePeer>,
    reachable: Set<String>,
    at: Long,
    online: (did: String, at: Long) -> Boolean,
): Map<String, DevicePresence> =
    (peers.keys + reachable)
        .filter { online(it, at) }
        .associateWith { did -> DevicePresence(peers[did]?.takeIf { it.playing }?.now?.title) }
