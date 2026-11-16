package io.github.aedev.flow.sync2

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.rotateEpoch
import io.github.aedev.flow.sync2.live.LiveChannel
import io.github.aedev.flow.sync2.live.LiveMessage
import io.github.aedev.flow.sync2.live.LiveReceived
import io.github.aedev.flow.sync2.live.LiveTransport
import io.github.aedev.flow.sync2.live.openLive
import io.github.aedev.flow.sync2.live.sealLive
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.parseObject
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Live messages between two devices of a group: sealed with a counter that never repeats, replays refused. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LiveChannelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = LiveMessage.Now(video = "aaaaaaaaaaa", title = "t", state = LiveMessage.PLAYING, queue = listOf("aaaaaaaaaaa"))

    /** [device]'s channel over what it holds of [g]: the roster and its wraps. */
    private suspend fun LinkedGroup.channel(
        device: MemberSecrets,
        db: LinkedDatabase,
    ): LiveChannel {
        val local = heldStore(db)
        local.addEntries(store.roster)
        store.keys.forEach { (key, wrap) -> local.putKey(key.epoch, key.kc, key.name, wrap) }
        return LiveChannel(context, db, mockk<DeviceKeys> { coEvery { load() } returns device }, local)
    }

    /** Envelopes [from] sends, handed to [to] and kept. */
    private fun relay(
        to: LiveChannel,
        kept: MutableList<ByteArray>,
    ) = object : LiveTransport {
        override suspend fun send(envelope: ByteArray) {
            kept += envelope
            to.receive(envelope)
        }
    }

    @Test
    fun a_device_hears_another_s_presence_and_now_and_a_replay_is_refused() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val dbA = g.deviceDb()
            val (a, b) = g.channel(g.a, dbA) to g.channel(g.b, g.deviceDb())
            val heard = async(start = CoroutineStart.UNDISPATCHED) { b.messages.take(2).toList() }
            val sent = ArrayList<ByteArray>()
            a.attach(relay(b, sent))
            a.send(now)
            val messages: List<LiveReceived> = heard.await()

            assertEquals(LiveMessage.Presence(listOf("play", "remote"), "phone"), messages[0].message)
            assertEquals(now, messages[1].message)
            assertTrue(messages.all { it.sender == SyncBytes.toHex(g.a.did) })
            assertEquals(listOf(1L, 2L), messages.map { it.seq })

            val replayed = async(start = CoroutineStart.UNDISPATCHED) { b.messages.take(1).toList() }
            sent.forEach { b.receive(it) }
            a.send(now)
            assertEquals("only the new message gets through", 3L, replayed.await().single().seq)

            val restarted = g.channel(g.a, dbA)
            assertEquals("the counter survives a restart", 4L, restarted.send(now))
        }

    @Test
    fun a_new_epoch_in_the_roster_moves_the_channel_though_the_group_record_stays_the_same() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val db = g.deviceDb()
            val local = heldStore(db)
            local.addEntries(g.store.roster)
            g.store.keys.forEach { (key, wrap) -> local.putKey(key.epoch, key.kc, key.name, wrap) }
            val channel = LiveChannel(context, db, mockk<DeviceKeys> { coEvery { load() } returns g.a }, local)
            val sent = ArrayList<ByteArray>()
            channel.attach(
                object : LiveTransport {
                    override suspend fun send(envelope: ByteArray) {
                        sent += envelope
                    }
                },
            )

            check(rotateEpoch(Session.open(g.deviceDb(), g.store, g.a), g.a))
            local.addEntries(g.store.roster)
            g.store.keys.forEach { (key, wrap) -> local.putKey(key.epoch, key.kc, key.name, wrap) }
            Session.open(db, GroupStore(local, null), g.a)
            channel.send(now)

            assertEquals(listOf(0, 1), sent.map { parseObject(it, g.group).header.epoch })
        }

    @Test
    fun a_device_ignores_its_own_messages_and_those_of_anyone_outside_the_group() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = g.channel(g.a, g.deviceDb())
            val outsider = LinkedGroup(this).apply { create() }
            val stranger = outsider.channel(outsider.a, outsider.deviceDb())
            val heard = async(start = CoroutineStart.UNDISPATCHED) { a.messages.take(1).toList() }

            val own = ArrayList<ByteArray>()
            a.attach(
                object : LiveTransport {
                    override suspend fun send(envelope: ByteArray) {
                        own += envelope
                    }
                },
            )
            own.forEach { a.receive(it) }
            stranger.attach(relay(a, ArrayList()))
            val b = g.channel(g.b, g.deviceDb())
            b.attach(relay(a, ArrayList()))

            assertEquals("only B's presence arrives", SyncBytes.toHex(g.b.did), heard.await().single().sender)
        }

    @Test
    fun a_message_beyond_the_limits_of_the_spec_is_dropped() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (a, b) = Session.open(g.deviceDb(), g.store, g.a) to Session.open(g.deviceDb(), g.store, g.b)

            fun play(
                size: Int,
                index: Int,
            ) = LiveMessage.Play(SyncBytes.toHex(g.a.did), "v0", 0, false, null, List(size) { "v$it" }, index, LiveMessage.PLAYING)

            assertEquals(play(50, 49), a.openLive(b.sealLive(play(50, 49), 1))?.message)
            assertNull("51 videos", a.openLive(b.sealLive(play(51, 0), 2)))
            assertNull("an index outside the queue", a.openLive(b.sealLive(play(3, 3), 3)))
            assertNull("a volume above 100", a.openLive(b.sealLive(now.copy(vol = 101), 4)))
        }
}
