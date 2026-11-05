package io.github.aedev.flow.sync2

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.live.ConsentAnswer
import io.github.aedev.flow.sync2.live.Handoff
import io.github.aedev.flow.sync2.live.LiveChannel
import io.github.aedev.flow.sync2.live.LiveConsent
import io.github.aedev.flow.sync2.live.LiveDevices
import io.github.aedev.flow.sync2.live.LiveMessage
import io.github.aedev.flow.sync2.live.LivePeer
import io.github.aedev.flow.sync2.live.LiveReceived
import io.github.aedev.flow.sync2.live.LiveTransport
import io.github.aedev.flow.sync2.live.LocalPlayback
import io.github.aedev.flow.sync2.live.NowPlaying
import io.github.aedev.flow.sync2.live.queueWindow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Handoff and remote control between two devices (spec/live.md, Playing on another device, Consent). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HandoffTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val playing =
        LiveMessage.Now(
            video = "aaaaaaaaaaa",
            title = "Song",
            by = "Artist",
            music = true,
            pos = 61_000,
            dur = 200_000,
            state = LiveMessage.PLAYING,
            queue = listOf("zzzzzzzzzzz", "aaaaaaaaaaa"),
            index = 1,
        )

    private class Device(
        val did: String,
        val channel: LiveChannel,
        val consent: LiveConsent,
        val local: LocalPlayback,
        val handoff: Handoff,
        val devices: LiveDevices,
    )

    private suspend fun LinkedGroup.device(
        secrets: MemberSecrets,
        db: LinkedDatabase,
        now: LiveMessage.Now = playing,
    ): Device {
        val held = heldStore(db)
        held.addEntries(store.roster)
        store.keys.forEach { (key, wrap) -> held.putKey(key.epoch, key.kc, key.name, wrap) }
        val channel = LiveChannel(context, db, mockk<DeviceKeys> { coEvery { load() } returns secrets }, held)
        val consent = LiveConsent(db)
        val local =
            mockk<LocalPlayback>(relaxed = true) {
                coEvery { start(any(), any()) } returns null
                coEvery { command(any()) } returns null
            }
        val devices = LiveDevices()
        val handoff = Handoff(channel, devices, consent, local, mockk<NowPlaying> { coEvery { snapshot() } returns now })
        return Device(SyncBytes.toHex(secrets.did), channel, consent, local, handoff, devices)
    }

    /** Each device's envelopes reach the other a moment later, as over a real transport. */
    private suspend fun CoroutineScope.connect(
        a: Device,
        b: Device,
    ) {
        for ((from, to) in listOf(a to b, b to a)) {
            from.channel.attach(
                object : LiveTransport {
                    override suspend fun send(envelope: ByteArray) {
                        launch { to.channel.receive(envelope) }
                    }
                },
            )
            launch { from.handoff.run() }
        }
    }

    private fun live(block: suspend CoroutineScope.(LinkedGroup) -> Unit) =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            withContext(Dispatchers.Default) {
                val scope = CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())
                try {
                    scope.block(g)
                } finally {
                    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                }
            }
        }

    @Test
    fun a_handoff_starts_the_session_there_and_only_then_pauses_here() =
        live { g ->
            val a = g.device(g.a, g.deviceDb())
            val b = g.device(g.b, g.deviceDb())
            b.consent.remember(a.did, true)
            connect(a, b)
            // A's `now` reaches B first, so B starts the track with its title and artist, not blank.
            a.channel.send(playing)
            b.devices.devices.first { it[a.did]?.now != null }
            assertTrue(a.handoff.transfer(b.did))
            coVerify {
                b.local.start(
                    LiveMessage.Play(b.did, "aaaaaaaaaaa", 61_000, true, null, playing.queue, 1, LiveMessage.PLAYING),
                    playing,
                )
            }
            coVerify { a.local.release() }
        }

    @Test
    fun a_failed_handoff_never_stops_the_music() =
        live { g ->
            val a = g.device(g.a, g.deviceDb())
            val b = g.device(g.b, g.deviceDb())
            b.consent.remember(a.did, true)
            coEvery { b.local.start(any(), any()) } returns "unavailable"
            connect(a, b)
            assertFalse(a.handoff.transfer(b.did))
            coVerify(exactly = 0) { a.local.release() }
        }

    @Test
    fun a_declined_device_gets_nothing_applied_and_a_first_request_asks() =
        live { g ->
            val a = g.device(g.a, g.deviceDb())
            val b = g.device(g.b, g.deviceDb())
            connect(a, b)

            val asked =
                async {
                    b.consent.request
                        .filterNotNull()
                        .first()
                }
            val first = async { a.handoff.control(b.did, "seek", pos = 5_000) }
            b.consent.answer(asked.await(), ConsentAnswer.ONCE)
            assertTrue(first.await())
            coVerify { b.local.command(LiveMessage.Cmd(b.did, "seek", pos = 5_000)) }

            b.consent.remember(a.did, false)
            assertFalse(a.handoff.control(b.did, "pause"))
            coVerify(exactly = 0) { b.local.command(LiveMessage.Cmd(b.did, "pause")) }
        }

    @Test
    fun pulling_pauses_the_active_device_and_starts_here_where_it_is_now() {
        val devices = LiveDevices()
        val other = "bb".repeat(16)
        devices.heard(LiveReceived(other, 1, playing), at = 1_000)
        val active = checkNotNull(devices.active())
        assertEquals(other, active.did)
        assertEquals("position moves on while it plays", 65_000, active.position(5_000))
        assertEquals("never past the end", 200_000, active.position(10_000_000))
        assertEquals(61_000, LivePeer(other, now = playing.copy(state = LiveMessage.PAUSED), nowAt = 1_000).position(5_000))

        devices.heard(LiveReceived(other, 2, playing.copy(state = LiveMessage.PAUSED)), at = 6_000)
        assertEquals("a paused device isn't the active one", null, devices.active())
    }

    @Test
    fun a_quiet_device_goes_offline_and_pull_takes_the_chosen_device_first() {
        val devices = LiveDevices()
        val tv = "bb".repeat(16)
        val laptop = "cc".repeat(16)
        devices.heard(LiveReceived(tv, 1, playing.copy(state = LiveMessage.PAUSED)), at = 1_000)
        devices.heard(LiveReceived(laptop, 1, playing), at = 2_000)
        assertTrue("heard within 90 s", devices.isOnline(tv, at = 62_000))
        assertFalse("nothing for over 90 s", devices.isOnline(tv, at = 92_000))

        assertEquals(tv, devices.controlled(tv, at = 3_000)?.did)
        assertEquals("the latest session when none is chosen", laptop, devices.controlled(null, at = 3_000)?.did)
        assertEquals("not a chosen device that went quiet", null, devices.controlled(tv, at = 100_000))
    }

    @Test
    fun a_lan_peer_stays_online_while_its_address_answers() {
        val devices = LiveDevices()
        val mac = "bb".repeat(16)
        devices.heard(LiveReceived(mac, 1, playing.copy(state = LiveMessage.PAUSED)), at = 1_000)
        devices.lanPeers { it + mac }
        devices.serverPeers(emptySet())
        assertTrue("quiet, but its LAN address answered", devices.isOnline(mac, at = 600_000))
        assertEquals(mac, devices.controlled(mac, at = 600_000)?.did)

        devices.lanPeers { it - mac }
        assertFalse(devices.isOnline(mac, at = 600_000))
    }

    @Test
    fun a_played_queue_is_cut_to_the_limit_around_the_chosen_item() {
        val queue = (0 until 120).map { "v$it" }
        val (ids, at) = checkNotNull(queueWindow(queue, 100))
        assertEquals(LiveMessage.QUEUE_LIMIT, ids.size)
        assertEquals("v100", ids[at])
        assertEquals(listOf("v1", "v2") to 1, queueWindow(listOf("v1", "v2"), 1))
        assertEquals(null, queueWindow(queue, 120))
    }

    @Test
    fun moving_the_driven_device_starts_its_session_on_the_other_and_only_then_pauses_it() =
        live { g ->
            val a = g.device(g.a, g.deviceDb())
            val b = g.device(g.b, g.deviceDb())
            val mac = "ee".repeat(16)
            b.consent.remember(a.did, true)
            connect(a, b)
            val heardAt = System.currentTimeMillis() - 10_000
            a.devices.heard(LiveReceived(mac, 1, playing.copy(link = "link")), at = heardAt)
            val paused =
                async(start = CoroutineStart.UNDISPATCHED) {
                    b.channel.messages
                        .first { (it.message as? LiveMessage.Cmd)?.to == mac }
                        .message
                }

            assertTrue(a.handoff.move(mac, b.did))

            coVerify {
                b.local.start(
                    match {
                        it.to == b.did && it.video == playing.video && it.queue == playing.queue && it.index == 1 &&
                            it.state == LiveMessage.PLAYING && it.link == "link" && it.pos >= 71_000 && it.pos < 120_000
                    },
                    any(),
                )
            }
            assertEquals(LiveMessage.Cmd(mac, "pause"), paused.await())
        }

    @Test
    fun a_failed_move_leaves_the_driven_device_playing() =
        live { g ->
            val a = g.device(g.a, g.deviceDb())
            val b = g.device(g.b, g.deviceDb())
            val mac = "ee".repeat(16)
            b.consent.remember(a.did, true)
            coEvery { b.local.start(any(), any()) } returns "unavailable"
            connect(a, b)
            a.devices.heard(LiveReceived(mac, 1, playing))
            val paused =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(2_000) { b.channel.messages.first { (it.message as? LiveMessage.Cmd)?.to == mac } }
                }

            assertFalse(a.handoff.move(mac, b.did))
            assertEquals(null, paused.await())
        }
}
