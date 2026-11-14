package io.github.aedev.flow.ui.screens.sync.linked

import io.github.aedev.flow.sync2.live.LiveMessage
import io.github.aedev.flow.sync2.live.LivePeer
import io.github.aedev.flow.sync2.service.LinkedDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkedDeviceStatusTest {
    private val phone = LinkedDevice("p", "Phone", "android", thisDevice = false, seen = 1_000)
    private val playing = mapOf("p" to DevicePresence("Song"))
    private val online = mapOf("p" to DevicePresence())
    private val nearby = mapOf("p" to 2_000L)

    @Test
    fun this_device_comes_before_anything_the_others_say() {
        val me = phone.copy(thisDevice = true)
        assertEquals(DeviceStatus.ThisDevice, deviceStatusOf(me, playing, nearby))
    }

    @Test
    fun playing_comes_before_online_nearby_and_synced() {
        assertEquals(DeviceStatus.Playing("Song"), deviceStatusOf(phone, playing, nearby))
    }

    @Test
    fun online_comes_before_nearby_and_synced() {
        assertEquals(DeviceStatus.Online("android"), deviceStatusOf(phone, online, nearby))
    }

    @Test
    fun on_this_network_comes_before_when_the_store_last_heard_from_it() {
        assertEquals(DeviceStatus.Nearby("android", 2_000), deviceStatusOf(phone, emptyMap(), nearby))
    }

    @Test
    fun synced_shows_when_nothing_live_is_known() {
        assertEquals(DeviceStatus.Synced("android", 1_000), deviceStatusOf(phone, emptyMap(), emptyMap()))
    }

    @Test
    fun the_platform_alone_when_nothing_is_known() {
        assertEquals(DeviceStatus.Platform("android"), deviceStatusOf(phone.copy(seen = null), emptyMap(), emptyMap()))
    }

    @Test
    fun another_device_presence_does_not_change_this_row() {
        val other = mapOf("q" to DevicePresence("Song"))
        assertEquals(DeviceStatus.Synced("android", 1_000), deviceStatusOf(phone, other, emptyMap()))
    }

    private fun now(state: String) = LiveMessage.Now(video = "v", title = "Song", state = state)

    @Test
    fun presence_lists_reachable_devices_with_what_plays_on_them() {
        val peers =
            mapOf(
                "a" to LivePeer("a", now = now(LiveMessage.PLAYING), heardAt = 1),
                "b" to LivePeer("b", now = now(LiveMessage.PAUSED), heardAt = 1),
                "c" to LivePeer("c", now = now(LiveMessage.PLAYING), heardAt = 1),
            )
        val reachable = setOf("a", "b", "d")
        val presence = devicePresence(peers, reachable, at = 5) { did, _ -> did in reachable }
        assertEquals(
            "a paused device and one reached without a live message are online; one out of reach is left out",
            mapOf("a" to DevicePresence("Song"), "b" to DevicePresence(), "d" to DevicePresence()),
            presence,
        )
    }

    @Test
    fun presence_asks_whether_each_device_is_online_at_the_given_time() {
        val peers = mapOf("a" to LivePeer("a", heardAt = 100))
        val presence = devicePresence(peers, emptySet(), at = 150) { did, at -> peers.getValue(did).heardAt > at - 60 }
        assertEquals(mapOf("a" to DevicePresence()), presence)
        assertEquals(
            emptyMap<String, DevicePresence>(),
            devicePresence(peers, emptySet(), at = 500) { did, at ->
                peers.getValue(did).heardAt >
                    at - 60
            },
        )
    }
}
