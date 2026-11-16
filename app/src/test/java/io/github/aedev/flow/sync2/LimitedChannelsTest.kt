package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.transport.BINARY
import io.github.aedev.flow.sync.transport.RawWebSocketPeer
import io.github.aedev.flow.sync.transport.frameHeader
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.lan.MAX_LAN_FRAME
import io.github.aedev.flow.sync2.linking.LanEndpoint
import io.github.aedev.flow.sync2.linking.dial
import io.github.aedev.flow.sync2.store.MAX_LIVE_MESSAGE
import io.github.aedev.flow.sync2.store.ServerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LimitedChannelsTest {
    @Test(expected = LinkedException.Stopped::class)
    fun `a pairing dial stops at a header past the LAN frame limit`() {
        runBlocking {
            RawWebSocketPeer(frames = { it.write(frameHeader(BINARY, MAX_LAN_FRAME + 1)) }).use { peer ->
                val pipe = checkNotNull(withContext(Dispatchers.IO) { dial(listOf(LanEndpoint("127.0.0.1", peer.port))) })
                try {
                    withTimeout(20_000) { pipe.receive() }
                } finally {
                    pipe.close()
                }
            }
        }
    }

    @Test
    fun `the live channel fails at a header past the live message limit`() {
        val failed = CountDownLatch(1)
        val listener =
            object : WebSocketListener() {
                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    failed.countDown()
                }
            }
        runBlocking {
            RawWebSocketPeer(
                beforeUpgrade = ::signIn,
                frames = { it.write(frameHeader(BINARY, MAX_LIVE_MESSAGE + 1)) },
            ).use { server ->
                val socket = ServerStore.connect("http://127.0.0.1:${server.port}/", newMember(), null, null).live(listener)
                assertTrue(withContext(Dispatchers.IO) { failed.await(30, TimeUnit.SECONDS) })
                socket.cancel()
            }
        }
    }

    private fun signIn(
        head: String,
        socket: Socket,
    ): Boolean {
        val hex = "ab".repeat(16)
        val body =
            when {
                head.startsWith("POST /v1/auth/challenge") -> {
                    """{"challenge":"${SyncBytes.b64urlEncode(ByteArray(32))}","instance":"$hex","group":"$hex","time":0}"""
                }

                head.startsWith("POST /v1/auth/token") -> {
                    """{"token":"t","expires":${System.currentTimeMillis() + 600_000}}"""
                }

                else -> {
                    return false
                }
            }
        socket.getOutputStream().write(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray(),
        )
        socket.getOutputStream().flush()
        return true
    }
}
