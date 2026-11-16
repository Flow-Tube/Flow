package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.tag
import io.github.aedev.flow.sync2.crypto.verify
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.canonicalEntry
import io.github.aedev.flow.sync2.store.Appended
import io.github.aedev.flow.sync2.store.ServerStore
import io.github.aedev.flow.sync2.store.StoreException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server client against a stand-in server: transport rules, sign-in, pinning, renewal, redirects and conflicts. */
class ServerStoreTest {
    private val instanceId = "0102030405060708090a0b0c0d0e0f10"
    private val groupId = "a1a2a3a4a5a6a7a8a9aaabacadaeafb0"
    private val member = MemberSecrets(ByteArray(32) { 7 }, ByteArray(32) { 9 })
    private var issued = 0
    private var server: StandInServer? = null

    /** Answers the sign-in, issuing `t1`, `t2`, … only for a valid signature, and [routes] for the rest. */
    private fun serve(
        instance: String = instanceId,
        expires: Long = Long.MAX_VALUE / 2,
        routes: suspend ApplicationCall.() -> Unit,
    ) {
        val nonce = ByteArray(32) { 5 }
        server =
            StandInServer {
                when (request.path()) {
                    "/v1/auth/challenge" -> {
                        respondJson(
                            200,
                            """{"challenge":"${SyncBytes.b64urlEncode(nonce)}","instance":"$instance","group":"$groupId","time":0}""",
                        )
                    }

                    "/v1/auth/token" -> {
                        val body = Json.parseToJsonElement(receiveText()).jsonObject
                        val message = tag("auth") + SyncBytes.fromHex(instance) + SyncBytes.fromHex(groupId) + nonce
                        val signature = SyncBytes.b64urlDecode(body.getValue("sig").jsonPrimitive.content)
                        if (verify(member.sigPublic, message, signature)) {
                            respondJson(200, """{"token":"t${++issued}","expires":$expires}""")
                        } else {
                            respondJson(401, """{"error":"unauthorized"}""")
                        }
                    }

                    else -> {
                        routes()
                    }
                }
            }
    }

    private fun store(instance: String? = null) = ServerStore.connect(checkNotNull(server).url, member, instance, null)

    @After
    fun stop() {
        server?.close()
    }

    @Test
    fun plain_http_is_allowed_only_to_private_addresses() {
        val insecure = { url: String ->
            runCatching { ServerStore.connect(url, member, null, null) }.exceptionOrNull() is StoreException.Insecure
        }
        listOf(
            "https://sync.example.org/",
            "http://192.168.1.5:8080/",
            "http://100.101.102.103/",
            "http://[::ffff:10.0.0.2]:8080/",
            "http://[fd12::1]/",
        ).forEach { assertTrue(it, !insecure(it)) }
        listOf("http://203.0.113.5/", "http://sync.example.org/", "http://[::ffff:203.0.113.5]/", "ftp://192.168.1.5/")
            .forEach { assertTrue(it, insecure(it)) }
    }

    @Test
    fun requests_carry_a_token_from_a_signed_challenge() =
        runTest {
            serve {
                if (request.headers["Authorization"] ==
                    "Bearer t1"
                ) {
                    respondJson(200, """{"entries":[]}""")
                } else {
                    respondJson(401, "{}")
                }
            }
            val store = store()
            assertTrue(store.rosterAfter(-1).isEmpty())
            assertEquals(instanceId to groupId, store.identity())
        }

    @Test
    fun the_next_store_reuses_the_token_until_it_nearly_expires() =
        runTest {
            serve { respondJson(200, """{"entries":[]}""") }
            store().rosterAfter(-1)
            store().rosterAfter(-1)
            assertEquals(1, issued)
            stop()
            serve(expires = System.currentTimeMillis() + 30_000) { respondJson(200, """{"entries":[]}""") }
            store().rosterAfter(-1)
            store().rosterAfter(-1)
            assertEquals(3, issued)
        }

    @Test
    fun a_different_instance_is_refused() {
        serve(instance = "ff".repeat(16)) { respondJson(200, """{"entries":[]}""") }
        runTest {
            val error = runCatching { store(instance = instanceId).rosterAfter(-1) }.exceptionOrNull()
            assertTrue("$error", error is StoreException.InstanceChanged)
        }
    }

    @Test
    fun a_refused_token_is_renewed_once() =
        runTest {
            serve {
                if (request.headers["Authorization"] ==
                    "Bearer t2"
                ) {
                    respondJson(200, """{"entries":[]}""")
                } else {
                    respondJson(401, "{}")
                }
            }
            assertTrue(store().rosterAfter(-1).isEmpty())
            assertEquals(2, issued)
        }

    @Test
    fun claiming_a_server_that_has_a_group_says_so() =
        runTest {
            serve { respondJson(409, """{"error":"conflict"}""") }
            val genesis = Entry(2, groupId, 0, "00".repeat(32), Op.GENESIS, "00".repeat(16), sigs = emptyMap())
            val error = runCatching { store().claim("admin", genesis, emptyMap()) }.exceptionOrNull()
            assertTrue("$error", error is LinkedException.ServerHasGroup)
        }

    @Test
    fun answers_past_their_limit_are_refused() =
        runTest {
            val object4MiB = 4 * 1024 * 1024
            serve {
                when (request.path()) {
                    "/v1/objects/fits" -> respondBytes(ByteArray(object4MiB))
                    "/v1/objects/big" -> respondBytes(ByteArray(object4MiB + 1))
                    else -> respondJson(200, """{"objects":[],"cursor":"1","pad":"${"x".repeat(16 * 1024 * 1024)}"}""")
                }
            }
            assertEquals(object4MiB, store().getObject("fits")?.size)
            assertTrue(runCatching { store().getObject("big") }.exceptionOrNull() is StoreException.TooLarge)
            assertTrue(runCatching { store().changes(0) }.exceptionOrNull() is StoreException.TooLarge)
        }

    @Test
    fun a_roster_past_its_limit_is_refused() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val entry = canonicalEntry(g.store.roster.first())
            var pages = 0
            serve {
                val page = ++pages
                val entries = (1..1000).map { entry.replaceFirst(Regex("\"seq\":\\d+"), "\"seq\":${(page - 1) * 1000 + it}") }
                respondJson(200, if (page > 10) """{"entries":[]}""" else """{"entries":$entries}""")
            }
            val error = runCatching { store().rosterView() }.exceptionOrNull()
            assertTrue("$error", error is StoreException.Malformed)
        }

    @Test
    fun the_status_says_when_each_device_was_last_seen() =
        runTest {
            serve { respondJson(200, """{"used":0,"quota":0,"objects":0,"devices":{"aa":{"seen":5},"bb":{"seen":7}}}""") }
            assertEquals(mapOf("aa" to 5L, "bb" to 7L), store().seen())
        }

    @Test
    fun a_device_the_server_no_longer_lets_sign_in_is_told_so() {
        server =
            StandInServer {
                when (request.path()) {
                    "/v1/auth/challenge" -> {
                        respondJson(
                            200,
                            """{"challenge":"${SyncBytes.b64urlEncode(
                                ByteArray(32),
                            )}","instance":"$instanceId","group":"$groupId","time":0}""",
                        )
                    }

                    else -> {
                        respondJson(401, """{"error":"unauthorized"}""")
                    }
                }
            }
        runTest {
            val error = runCatching { store().rosterAfter(-1) }.exceptionOrNull()
            assertTrue("$error", error is StoreException.SignInRefused)
        }
    }

    @Test
    fun a_reset_confirms_with_the_admin_token_and_a_wrong_token_or_a_ban_is_said_plainly() {
        var body = ""
        server =
            StandInServer {
                when (request.headers["Authorization"]) {
                    "Bearer admin" -> {
                        body = receiveText()
                        respond(HttpStatusCode.NoContent)
                    }

                    "Bearer banned" -> {
                        respondJson(429, """{"error":"rate_limited"}""")
                    }

                    else -> {
                        respondJson(401, """{"error":"unauthorized"}""")
                    }
                }
            }
        runTest {
            store().resetServer("admin")
            assertEquals("""{"confirm":"reset"}""", body)
            assertTrue(runCatching { store().resetServer("wrong") }.exceptionOrNull() is StoreException.AdminRefused)
            assertTrue(runCatching { store().resetServer("banned") }.exceptionOrNull() is StoreException.AdminBlocked)
        }
    }

    @Test
    fun redirects_are_not_followed() {
        serve {
            response.header("Location", "https://elsewhere.example/")
            respond(HttpStatusCode.Found)
        }
        runTest {
            val error = runCatching { store().getObject("dev/x") }.exceptionOrNull()
            assertEquals(302, (error as StoreException.Refused).status)
        }
    }

    @Test
    fun a_lost_append_reports_the_current_head() =
        runTest {
            serve {
                if (request.headers["If-Match"] ==
                    "\"stale\""
                ) {
                    respondJson(409, """{"error":"conflict","head":"newer"}""")
                } else {
                    respondJson(500, "{}")
                }
            }
            val entry =
                Entry(
                    v = 2,
                    group = groupId,
                    seq = 1,
                    prev = "00".repeat(32),
                    op = Op.RENAME,
                    signer = "00".repeat(16),
                    meta = mapOf("00".repeat(16) to "x"),
                    sigs = emptyMap(),
                )
            assertEquals(Appended.Conflict("newer"), store().appendRoster("stale", entry, emptyMap(), null))
        }
}
