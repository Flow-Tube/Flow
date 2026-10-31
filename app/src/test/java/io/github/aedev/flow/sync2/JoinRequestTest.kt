package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.StoreLocation
import io.github.aedev.flow.sync2.linking.addDevice
import io.github.aedev.flow.sync2.linking.askToJoin
import io.github.aedev.flow.sync2.linking.join
import io.github.aedev.flow.sync2.linking.newOffer
import io.github.aedev.flow.sync2.store.ServerStore
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Joining with the admin token: a new device asks, a member answers with its code, and they pair as usual. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class JoinRequestTest {
    private class Request(
        val name: String,
        var code: String? = null,
        var declined: Boolean = false,
    )

    private val requests = LinkedHashMap<String, Request>()
    private val server = StandInServer { joinRoutes() }

    /** The join routes of spec/api.md, with `admin` as the admin token and sign-in accepting any device. */
    private suspend fun ApplicationCall.joinRoutes() {
        val path = request.path()
        val id = path.removePrefix("/v1/join/")
        val admin = request.headers["Authorization"] == "Bearer admin"
        when {
            path == "/v1/auth/challenge" -> {
                respondJson(
                    200,
                    """{"challenge":"${SyncBytes.b64urlEncode(
                        ByteArray(32),
                    )}","instance":"${"01".repeat(16)}","group":"${"02".repeat(16)}","time":0}""",
                )
            }

            path == "/v1/auth/token" -> {
                respondJson(200, """{"token":"t","expires":${Long.MAX_VALUE / 2}}""")
            }

            path == "/v1/join" && request.httpMethod.value == "POST" -> {
                val body = Json.parseToJsonElement(receiveText()).jsonObject
                requests[body.getValue("id").jsonPrimitive.content] = Request(body.getValue("name").jsonPrimitive.content)
                if (admin) respondJson(201, """{"expires":0}""") else respondJson(401, "{}")
            }

            path == "/v1/join" -> {
                val open = requests.filterValues { it.code == null && !it.declined }
                val list =
                    buildJsonArray {
                        open.forEach { (id, request) ->
                            add(
                                buildJsonObject {
                                    put("id", id)
                                    put("name", request.name)
                                    put("expires", 0)
                                },
                            )
                        }
                    }
                respondJson(200, buildJsonObject { put("requests", list) }.toString())
            }

            request.httpMethod.value == "PUT" -> {
                requests.getValue(id).code =
                    Json
                        .parseToJsonElement(receiveText())
                        .jsonObject
                        .getValue("code")
                        .jsonPrimitive.content
                respondJson(201, "{}")
            }

            request.httpMethod.value == "DELETE" -> {
                val asked = requests[id]
                when {
                    asked == null -> respondJson(404, """{"error":"not_found"}""")
                    asked.code != null || asked.declined -> respondJson(409, """{"error":"conflict"}""")
                    else -> respondJson(204, "").also { asked.declined = true }
                }
            }

            admin && requests[id]?.declined == true -> {
                respondJson(410, """{"error":"declined"}""")
            }

            else -> {
                val code = requests[id]?.code?.takeIf { admin }
                if (code ==
                    null
                ) {
                    respondJson(404, """{"error":"not_found"}""")
                } else {
                    respondJson(200, buildJsonObject { put("code", JsonPrimitive(code)) }.toString())
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    @Test
    fun a_device_that_asks_pairs_with_the_member_that_accepts() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            val newcomer = newMember()
            val dbN = emptyDb()
            val codes = ArrayList<String>()
            val shown = { sas: String ->
                codes += sas
                true
            }
            withContext(Dispatchers.Default) {
                coroutineScope {
                    val joined =
                        async {
                            val code = askToJoin(ServerStore.connect(server.url, newcomer, null, null), "admin", "Living room TV")
                            join(
                                dbN,
                                heldStore(dbN),
                                { g.store },
                                g.store,
                                code,
                                newcomer,
                                DeviceMeta("app-tv", "Living room TV", "android"),
                                JOINER,
                            ) { shown(it) }
                        }
                    val member = ServerStore.connect(server.url, g.a, null, null)
                    var asking = member.joinRequests()
                    while (asking.isEmpty()) {
                        delay(50)
                        asking = member.joinRequests()
                    }
                    assertEquals("Living room TV", asking.single().name)
                    val offer = newOffer(StoreLocation("server", "memory"), "Desktop A", null)
                    member.answerJoin(asking.single().id, offer.qr)
                    assertEquals(SyncBytes.toHex(newcomer.did), addDevice(session, g.store, offer, g.a) { shown(it) })
                    joined.await()
                }
            }
            assertEquals(2, codes.size)
            assertEquals(codes[0], codes[1])
            assertTrue(SyncBytes.toHex(newcomer.did) in g.store.head().members)
            assertTrue(member().joinRequests().isEmpty())
        }

    @Test
    fun a_declined_request_stops_the_asking_device() =
        runTest {
            val error =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val asking =
                            async { runCatching { askToJoin(ServerStore.connect(server.url, newMember(), null, null), "admin", "TV") } }
                        var listed = member().joinRequests()
                        while (listed.isEmpty()) {
                            delay(50)
                            listed = member().joinRequests()
                        }
                        member().declineJoin(listed.single().id)
                        asking.await().exceptionOrNull()
                    }
                }
            assertTrue("$error", error is LinkedException.JoinDeclined)
        }

    @Test
    fun asking_gives_up_when_no_member_answers() =
        runTest {
            val error = runCatching { askToJoin(ServerStore.connect(server.url, newMember(), null, null), "admin", "TV") }.exceptionOrNull()
            assertTrue("$error", error is LinkedException.TimedOut)
        }

    @Test
    fun a_declined_request_is_no_longer_listed() =
        runTest {
            ServerStore.connect(server.url, newMember(), null, null).requestJoin("admin", "ab".repeat(16), "TV")
            assertEquals(listOf("TV"), member().joinRequests().map { it.name })
            member().declineJoin("ab".repeat(16))
            member().declineJoin("ab".repeat(16))
            assertTrue(member().joinRequests().isEmpty())
        }

    private fun member() = ServerStore.connect(server.url, newMember(), null, null)
}
