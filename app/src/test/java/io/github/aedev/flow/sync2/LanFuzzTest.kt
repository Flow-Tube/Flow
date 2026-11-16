package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.encodePayload
import io.github.aedev.flow.sync2.crypto.lanFrameAad
import io.github.aedev.flow.sync2.crypto.seal
import io.github.aedev.flow.sync2.crypto.u32
import io.github.aedev.flow.sync2.crypto.u64
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.lan.LanLink
import io.github.aedev.flow.sync2.lan.acceptLink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What a LAN peer sends: the hello, then numbered frames (spec/live.md, Session). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LanFuzzTest {
    /** Hands over [frames] in order, then reports the connection closed. */
    private class Frames(
        vararg frames: ByteArray,
    ) : SyncConnection {
        private val left = ArrayDeque(frames.toList())

        override suspend fun send(bytes: ByteArray) = Unit

        override suspend fun receive() = left.removeFirstOrNull()

        override fun close() = Unit
    }

    private val key = ByteArray(32) { 6 }

    private fun frame(content: ByteArray) = u64(0) + seal(key, lanFrameAad(0), encodePayload(content))

    private suspend fun receive(frame: ByteArray) = LanLink(Frames(frame), "peer", null, key, key).receive()

    @Test
    fun frames_round_trip() =
        runTest {
            forAll(generate = { bytes(512) }) { assertArrayEquals(it, receive(frame(it))) }
        }

    @Test
    fun any_change_to_a_frame_is_refused() =
        runTest {
            val valid = frame(ByteArray(64) { 1 })
            forAll(generate = { mutation(valid) }) { refused<ProtocolException> { receive(it) } }
        }

    @Test
    fun arbitrary_frames_are_refused() =
        runTest {
            forAll(generate = { if (nextBoolean()) bytes(1024) else u64(0) + bytes(512) }) { refused<ProtocolException> { receive(it) } }
        }

    @Test
    fun arbitrary_first_hellos_are_refused_cleanly() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            forAll(generate = { if (nextBoolean()) bytes(600) else u32(listOf(0, 1, -1, nextInt()).random(this)) + bytes(600) }) { hello ->
                try {
                    acceptLink(Frames(hello), session)
                } catch (_: ProtocolException) {
                } catch (_: LinkedException.Unexpected) {
                }
            }
        }
}
