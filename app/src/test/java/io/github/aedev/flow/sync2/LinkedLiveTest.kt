package io.github.aedev.flow.sync2

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.live.LiveChannel
import io.github.aedev.flow.sync2.live.LiveDevices
import io.github.aedev.flow.sync2.live.LiveTransport
import io.github.aedev.flow.sync2.service.LastSync
import io.github.aedev.flow.sync2.service.LinkedLive
import io.github.aedev.flow.sync2.service.LinkedSync
import io.github.aedev.flow.sync2.store.ServerStore
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** The live socket: signed in through its subprotocol, a burst of `changed` frames leads to one sync, and other frames to none. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LinkedLiveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bearers = MutableStateFlow<List<String>>(emptyList())
    private val relayed = MutableStateFlow<List<List<Byte>>>(emptyList())

    private val server =
        embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                post("/v1/auth/{step}") {
                    if (call.request.path().endsWith("challenge")) {
                        call.respondJson(
                            200,
                            """{"challenge":"${SyncBytes.b64urlEncode(
                                ByteArray(32),
                            )}","instance":"${"01".repeat(16)}","group":"${"02".repeat(16)}","time":0}""",
                        )
                    } else {
                        call.respondJson(200, """{"token":"t","expires":${Long.MAX_VALUE / 2}}""")
                    }
                }
                webSocket("/v1/live", protocol = "flow-sync.v1") {
                    bearers.value += call.request.headers["Sec-WebSocket-Protocol"].orEmpty()
                    send(Frame.Text("""{"type":"peers","online":["${"bb".repeat(16)}"]}"""))
                    send(Frame.Text("""{"type":"later"}"""))
                    send(Frame.Text("not json"))
                    repeat(3) { send(Frame.Text("""{"type":"changed"}""")) }
                    send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
                    for (frame in incoming) if (frame is Frame.Binary) relayed.value += listOf(frame.readBytes().toList())
                }
            }
        }.start(wait = false)

    @After
    fun stop() {
        scope.cancel()
        server.stop(0, 0)
    }

    @Test
    fun a_burst_of_changes_leads_to_one_sync() =
        runBlocking {
            val url = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}/"
            val record = GroupRecord("02".repeat(16), url, null, "link")
            val db = mockk<LinkedDatabase> { every { groups() } returns mockk { every { observeGroup() } returns flowOf(record) } }
            val syncs = AtomicInteger()
            val sync =
                mockk<LinkedSync> {
                    every { server(any(), any()) } answers { ServerStore.connect(url, secondArg(), null, null) }
                    // Synced just now, so opening the socket alone syncs nothing.
                    every { lastSync } returns MutableStateFlow(LastSync(Long.MAX_VALUE, null))
                    coEvery { syncNow() } answers {
                        syncs.incrementAndGet()
                        LastSync(System.currentTimeMillis(), null)
                    }
                }
            val keys = mockk<DeviceKeys> { coEvery { load() } returns newMember() }
            val received = MutableStateFlow<List<List<Byte>>>(emptyList())
            val channel =
                mockk<LiveChannel> {
                    coEvery { attach(any()) } coAnswers {
                        firstArg<LiveTransport>().send(byteArrayOf(9))
                        val detach: () -> Unit = {}
                        detach
                    }
                    coEvery { receive(any()) } coAnswers { received.value += listOf(firstArg<ByteArray>().toList()) }
                }
            val devices = LiveDevices()
            val live = LinkedLive(db, keys, sync, channel, devices, scope)
            live.start()
            withTimeout(10_000) { bearers.first { it.isNotEmpty() } }
            assertEquals(listOf("flow-sync.v1, bearer.t"), bearers.value)
            withTimeout(10_000) { received.first { it.isNotEmpty() } }
            assertEquals("the server says who is connected", setOf("bb".repeat(16)), devices.online.value)
            assertEquals("a binary frame is a live envelope for the channel", listOf(listOf<Byte>(1, 2, 3)), received.value)
            withTimeout(10_000) { relayed.first { it.isNotEmpty() } }
            assertEquals("what the channel sends goes out as a binary frame", listOf(listOf<Byte>(9)), relayed.value)
            delay(5_000)
            assertEquals(1, syncs.get())
            live.stop()
        }
}
