package io.github.aedev.flow.ui.screens.playon

import android.app.Application
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.live.LiveMessage
import io.github.aedev.flow.sync2.live.LivePeer
import io.github.aedev.flow.sync2.service.LinkedDevice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Play on": devices as playing, available or offline in one list, and tapping one to pick it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h1600dp")
class PlayOnTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val me = "aa".repeat(16)
    private val tv = "bb".repeat(16)
    private val laptop = "cc".repeat(16)
    private val tablet = "dd".repeat(16)
    private val roster =
        listOf(
            LinkedDevice(me, "Phone", "android", thisDevice = true),
            LinkedDevice(tv, "Living room", "android", thisDevice = false),
            LinkedDevice(laptop, "Laptop", "desktop", thisDevice = false, seen = 1_000),
            LinkedDevice(tablet, "Tablet", "android", thisDevice = false),
        )
    private val song = LiveMessage.Now(video = "aaaaaaaaaaa", title = "Song", music = true, state = LiveMessage.PLAYING)

    private fun devices() =
        playOnDevices(
            roster = roster,
            peers =
                mapOf(
                    tv to LivePeer(tv, kind = "tv", now = song, nowAt = 1, heardAt = 1),
                    tablet to
                        LivePeer(tablet, kind = "tablet", now = song.copy(title = "Old song", state = LiveMessage.PAUSED), heardAt = 1),
                ),
            nearby = emptyMap(),
            localNow = LiveMessage.Now(video = null),
            me = me,
            ownKind = "phone",
        ) { it == tablet }

    @Test
    fun devices_are_playing_available_or_offline_with_what_they_know() {
        val byId = devices().associateBy { it.did }
        assertEquals(PlayOnGroup.PLAYING, byId.getValue(tv).group)
        assertEquals("tv", byId.getValue(tv).kind)
        assertEquals(PlayOnGroup.AVAILABLE, byId.getValue(tablet).group)
        assertEquals("this device is always reachable", PlayOnGroup.AVAILABLE, byId.getValue(me).group)
        assertEquals(PlayOnGroup.OFFLINE, byId.getValue(laptop).group)
        assertEquals("desktop", byId.getValue(laptop).kind)
        assertEquals(1_000L, byId.getValue(laptop).seenAt)
    }

    @Test
    fun one_devices_list_says_what_each_device_does_and_a_tap_picks_it() {
        val picked = ArrayList<String>()
        rule.setContent { Column { DeviceList(devices(), PlayOnRowState(active = null), onPick = { picked += it.did }) } }
        rule.onNodeWithText(context.getString(R.string.linked_devices)).assertExists()
        rule.onNodeWithText(context.getString(R.string.play_on_status_playing, "Song")).assertExists()
        rule.onNodeWithText(context.getString(R.string.play_on_status_paused, "Old song")).assertExists()
        rule.onNodeWithText(context.getString(R.string.play_on_status_ready)).assertExists()
        rule
            .onNodeWithText(
                context.getString(R.string.play_on_last_seen, DateUtils.getRelativeTimeSpanString(1_000).toString()),
            ).assertExists()

        rule.onNodeWithText("Tablet").performClick()
        rule.onNodeWithText("Laptop").performClick()
        assertEquals("an offline device can't be picked", listOf(tablet), picked)
    }
}
