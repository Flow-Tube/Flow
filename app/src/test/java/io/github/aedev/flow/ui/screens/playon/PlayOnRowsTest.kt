package io.github.aedev.flow.ui.screens.playon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sheet's rows: this device first, the rest by name, offline last and folded once there are a few. */
class PlayOnRowsTest {
    private fun device(
        did: String,
        name: String,
        group: PlayOnGroup = PlayOnGroup.AVAILABLE,
        thisDevice: Boolean = false,
        seenAt: Long? = null,
    ) = PlayOnDevice(did, name, "phone", thisDevice, group, now = null, seenAt = seenAt)

    private val phone = device("a", "Pixel", thisDevice = true)
    private val tv = device("b", "living room", PlayOnGroup.PLAYING)
    private val tablet = device("c", "Kitchen")
    private val laptop = device("d", "Laptop", PlayOnGroup.OFFLINE, seenAt = 10)
    private val desktop = device("e", "Desk", PlayOnGroup.OFFLINE, seenAt = 20)

    @Test
    fun this_device_comes_first_then_the_rest_by_name_whatever_plays() {
        val rows = playOnRows(listOf(tv, tablet, phone), active = tv.did)
        assertEquals(listOf(phone, tablet, tv), rows.reachable)
    }

    @Test
    fun offline_devices_come_last_most_recently_seen_first() {
        val rows = playOnRows(listOf(laptop, phone, desktop), active = phone.did)
        assertEquals(listOf(phone), rows.reachable)
        assertEquals(listOf(desktop, laptop), rows.offline)
        assertTrue(rows.foldOffline)
    }

    @Test
    fun one_offline_device_stays_inline() {
        val rows = playOnRows(listOf(phone, laptop), active = phone.did)
        assertEquals(listOf(laptop), rows.offline)
        assertFalse(rows.foldOffline)
    }

    @Test
    fun the_active_device_is_never_folded_away() {
        val rows = playOnRows(listOf(phone, laptop, desktop), active = laptop.did)
        assertEquals(listOf(phone, laptop), rows.reachable)
        assertEquals(listOf(desktop), rows.offline)
    }
}
