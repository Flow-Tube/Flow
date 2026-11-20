package io.github.aedev.flow.sync2

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.sync2.live.LiveTransport
import io.github.aedev.flow.sync2.live.startSessionLive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * A session dialed to carry a waiting live message, such as "Play on" another device. The device at
 * the other end takes a sender's messages only in counter order and drops anything not newer than
 * the last one it took (spec/live.md), so the waiting message must not be overtaken by the presence
 * that joining the live channel sends.
 */
class SessionLiveStartTest {
    /** The other device: keeps a message only when its counter is above the last one taken. */
    private class Peer : LiveTransport {
        val taken = mutableListOf<Int>()

        override suspend fun send(envelope: ByteArray) {
            val seq = envelope.single().toInt()
            if (seq > (taken.lastOrNull() ?: 0)) taken += seq
        }
    }

    private val play = byteArrayOf(7)
    private val presence = byteArrayOf(8)

    private fun joinSending(message: ByteArray): suspend (LiveTransport) -> () -> Unit =
        { transport ->
            transport.send(message)
            val leave: () -> Unit = {}
            leave
        }

    @Test
    fun `the message the session was dialed for reaches the other device`() =
        runTest {
            val peer = Peer()

            startSessionLive(peer, waiting = play, attach = joinSending(presence))

            assertThat(peer.taken).containsExactly(7, 8).inOrder()
        }

    @Test
    fun `a session that only serves still carries the waiting message`() =
        runTest {
            val peer = Peer()

            val detach = startSessionLive(peer, waiting = play, attach = null)

            assertThat(peer.taken).containsExactly(7)
            assertThat(detach).isNull()
        }

    @Test
    fun `a session with nothing waiting just joins`() =
        runTest {
            val peer = Peer()

            startSessionLive(peer, waiting = null, attach = joinSending(presence))

            assertThat(peer.taken).containsExactly(8)
        }

    @Test
    fun `a session that closes while sending leaves nothing joined`() {
        val closing =
            object : LiveTransport {
                override suspend fun send(envelope: ByteArray) {
                    if (envelope.contentEquals(play)) throw IOException("closed")
                }
            }
        var joined = 0

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                startSessionLive(closing, waiting = play) {
                    joined++
                    val leave: () -> Unit = { joined-- }
                    leave
                }
            }
        }
        assertThat(joined).isEqualTo(0)
    }
}
