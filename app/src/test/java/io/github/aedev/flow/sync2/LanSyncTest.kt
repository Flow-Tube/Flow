package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync.transport.WsClient
import io.github.aedev.flow.sync.transport.WsServer
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.data.LAN_STORE
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.toEntries
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.lan.LanLink
import io.github.aedev.flow.sync2.lan.LanPeer
import io.github.aedev.flow.sync2.lan.SESSION_PATH
import io.github.aedev.flow.sync2.lan.acceptLink
import io.github.aedev.flow.sync2.lan.connectLink
import io.github.aedev.flow.sync2.lan.dialLink
import io.github.aedev.flow.sync2.lan.portFree
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.newEpoch
import io.github.aedev.flow.sync2.linking.renameDevice
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.roster.forkWinner
import io.github.aedev.flow.sync2.roster.memberSubject
import io.github.aedev.flow.sync2.store.Appended
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.LocalStore
import io.github.aedev.flow.sync2.store.MAX_SEQ
import io.github.aedev.flow.sync2.store.StorePaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/** LAN sessions over loopback (spec/live.md, Session and Requests): the hello, then each member pulls from the other. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LanSyncTest {
    private class Member(
        val keys: MemberSecrets,
        val db: LinkedDatabase,
        val local: LocalStore,
    ) {
        suspend fun session() = Session.open(db, GroupStore(local, null), keys)

        suspend fun lanSession() = Session.openForLan(db, GroupStore(local, null), keys)
    }

    /** A member of [g] that holds the roster and its own wrap, as a LAN group member would. */
    private suspend fun TestScope.member(
        g: LinkedGroup,
        keys: MemberSecrets,
    ): Member {
        val db = g.deviceDb()
        db.groups().saveGroup(checkNotNull(db.groups().group()).copy(storeUrl = LAN_STORE))
        val local = heldStore(db)
        local.addEntries(g.store.roster)
        val did = SyncBytes.toHex(keys.did)
        g.store.holdKeys(local, 0, did)
        return Member(keys, db, local)
    }

    /** One session from [client] to [server], both pulling, as LanSync runs it; [envelope] goes along as a live message, which the server keeps in [heard]. */
    private suspend fun meet(
        client: Member,
        server: Member,
        envelope: ByteArray? = null,
        heard: MutableList<ByteArray> = ArrayList(),
    ) = withContext(Dispatchers.Default) {
        coroutineScope {
            val accepted = CompletableDeferred<SyncConnection>()
            val listener = WsServer(SESSION_PATH) { accepted.complete(it) }
            val port = listener.start()
            val connection = WsClient(OkHttpClient()).connect("ws://127.0.0.1:$port$SESSION_PATH")
            val serverSide = async { pullBoth(acceptLink(accepted.await(), server.session()), server, heard = heard) }
            pullBoth(connectLink(connection, client.session()), client, envelope)
            serverSide.await()
            listener.stop()
        }
    }

    /** One session from [client] to [server] as LanSync dials it: [server] closes what it can't answer. */
    private suspend fun dialed(
        client: Member,
        server: Member,
    ) = withContext(Dispatchers.Default) {
        val answered = CompletableDeferred<Unit>()
        val listener =
            WsServer(SESSION_PATH) { connection ->
                val link =
                    try {
                        acceptLink(connection, server.lanSession())
                    } catch (_: ProtocolException) {
                        connection.close()
                        return@WsServer
                    }
                // Closing the link cancels this handler, which runs inside the socket's scope.
                try {
                    pullBoth(link, server)
                } finally {
                    answered.complete(Unit)
                }
            }
        val port = listener.start()
        val link = dialLink(client.lanSession(), null) { WsClient(OkHttpClient()).connect("ws://127.0.0.1:$port$SESSION_PATH") }
        pullBoth(checkNotNull(link), client)
        answered.await()
        listener.stop()
    }

    /** [member], alone on the LAN, starts the next epoch in what it holds. */
    private suspend fun rotateAlone(
        g: LinkedGroup,
        member: Member,
    ): Entry {
        val session = member.lanSession()
        val state = session.state
        val epoch = newEpoch(g.group, state.epoch + 1, state.members.mapValues { it.value.kem }, session.currentKey())
        val entry = buildEntry(g.group, state, draft(Op.ROTATE, member.keys).copy(epoch = epoch.field), listOf(member.keys))
        assertEquals(Appended.Done, member.local.appendRoster(state.head, entry, epoch.wraps, epoch.chain))
        return entry
    }

    private suspend fun Member.write(video: String) =
        session().push(mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"$video"}"""), 1, "")), LINK)

    /** [client]'s link to [server], whose peer answers until [block] returns; [block] may also have that peer ask. */
    private suspend fun linked(
        client: Member,
        server: Member,
        block: suspend (LanLink, LanPeer) -> Unit,
    ) = withContext(Dispatchers.Default) {
        coroutineScope {
            val accepted = CompletableDeferred<SyncConnection>()
            val listener = WsServer(SESSION_PATH) { accepted.complete(it) }
            val port = listener.start()
            try {
                val connection = WsClient(OkHttpClient()).connect("ws://127.0.0.1:$port$SESSION_PATH")
                val serverSide = async { acceptLink(accepted.await(), server.session()) }
                val link = connectLink(connection, client.session())
                val peer = LanPeer(serverSide.await(), server.session(), server.local)
                val answering = peer.start(this)
                try {
                    block(link, peer)
                } finally {
                    link.close()
                }
                answering.join()
            } finally {
                listener.stop()
            }
        }
    }

    /** Sends [header] as a request on [link] and returns the answer's header. */
    private suspend fun ask(
        link: LanLink,
        header: String,
    ): JsonObject {
        link.send(header.toByteArray())
        return Json.parseToJsonElement(checkNotNull(link.receive()).decodeToString()).jsonObject
    }

    private suspend fun pullBoth(
        link: LanLink,
        member: Member,
        envelope: ByteArray? = null,
        heard: MutableList<ByteArray> = ArrayList(),
    ) = coroutineScope {
        val peer = LanPeer(link, member.lanSession(), member.local) { heard += it }
        val reader = peer.start(this)
        envelope?.let { peer.sendLive(it) }
        peer.pull()
        peer.awaitQuiet(500)
        link.close()
        reader.join()
    }

    @Test
    fun members_pull_each_others_objects_in_one_session() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = member(g, g.a)
            val b = member(g, g.b)
            a.session().push(
                mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"aaaaaaaaaaa"}"""), 1, "")),
                LINK,
            )
            b.session().push(
                mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"bbbbbbbbbbb"}"""), 1, "")),
                LINK,
            )

            meet(client = a, server = b)

            assertTrue(SyncBytes.toHex(g.a.did) in b.session().pull(setOf(LINK)).batches)
            assertTrue(SyncBytes.toHex(g.b.did) in a.session().pull(setOf(LINK)).batches)
        }

    @Test
    fun a_pull_keeps_nothing_at_this_devices_own_paths() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (a, b) = member(g, g.a) to member(g, g.b)
            val own = StorePaths.log(SyncBytes.toHex(g.b.did), 1)
            a.local.putObject(own, ByteArray(600))

            meet(client = a, server = b)

            assertNull(b.local.getObject(own))
        }

    @Test
    fun a_pull_keeps_only_objects_that_pass_their_checks() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (a, b) = member(g, g.a) to member(g, g.b)
            val bad = StorePaths.log(SyncBytes.toHex(g.a.did), 1)
            a.local.putObject(bad, ByteArray(600))

            meet(client = a, server = b)

            assertNull(b.local.getObject(bad))
        }

    @Test
    fun a_listing_request_naming_a_seq_above_2_53_is_refused() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = member(g, g.a)
            val b = member(g, g.b)
            linked(a, b) { link, _ ->
                val list = { id: Int, seq: Long -> """{"id":$id,"op":"list","vv":{"${SyncBytes.toHex(g.a.did)}":$seq}}""" }
                assertEquals(JsonPrimitive(true), ask(link, list(0, MAX_SEQ))["ok"])
                assertEquals(JsonPrimitive(false), ask(link, list(1, MAX_SEQ + 1))["ok"])
            }
        }

    @Test
    fun a_request_or_an_answer_with_a_field_of_the_wrong_type_is_a_protocol_error() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = member(g, g.a)
            val b = member(g, g.b)

            suspend fun error(block: suspend (LanLink, LanPeer) -> Unit) = runCatching { linked(a, b, block) }.exceptionOrNull()
            for (frame in listOf("""{"id":0,"op":{}}""", """{"re":[0]}""")) {
                val refused = error { link, _ -> link.send(frame.toByteArray()) }
                assertEquals("$frame: $refused", "payload", (refused as? ProtocolException)?.error?.code)
            }
            val answered =
                error { link, peer ->
                    coroutineScope {
                        val pulling = async { peer.pull() }
                        val asked = Json.parseToJsonElement(checkNotNull(link.receive()).decodeToString()).jsonObject
                        link.send("""{"entries":{},"ok":true,"re":${asked["id"]}}""".toByteArray())
                        pulling.await()
                    }
                }
            assertEquals("$answered", "payload", (answered as? ProtocolException)?.error?.code)
        }

    @Test
    fun a_device_that_is_not_a_member_is_refused_at_the_hello() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val b = member(g, g.b)
            val outsiderGroup = LinkedGroup(this).apply { create() }
            val outsider = member(outsiderGroup, outsiderGroup.a)
            val error =
                withContext(Dispatchers.Default) {
                    runCatching { meet(client = outsider, server = b) }.exceptionOrNull()
                }
            assertTrue("$error", error is ProtocolException || error is LinkedException)
        }

    @Test
    fun members_that_appended_at_the_same_seq_both_end_on_the_fork_winner() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = member(g, g.a)
            val b = member(g, g.b)
            renameDevice(a.session(), g.a, SyncBytes.toHex(g.a.did), DeviceMeta("app-a", "Phone", "android"))
            renameDevice(b.session(), g.b, SyncBytes.toHex(g.b.did), DeviceMeta("app-b", "TV", "android"))
            val forks = listOf(a, b).map { it.local.rosterAfter(-1).last() }
            assertEquals(forks[0].seq, forks[1].seq)

            meet(client = a, server = b)

            val winner = entryHash(checkNotNull(forkWinner(forks)))
            for (member in listOf(a, b)) {
                assertEquals(winner, entryHash(member.local.rosterAfter(-1).last()))
                assertEquals(winner, member.session().state.head)
            }
        }

    @Test
    fun a_member_whose_entry_lost_a_fork_the_other_already_resolved_still_ends_on_the_winner() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = member(g, g.a)
            val b = member(g, g.b)
            renameDevice(a.session(), g.a, SyncBytes.toHex(g.a.did), DeviceMeta("app-a", "Phone", "android"))
            renameDevice(b.session(), g.b, SyncBytes.toHex(g.b.did), DeviceMeta("app-b", "TV", "android"))
            val winner = checkNotNull(forkWinner(listOf(a, b).map { it.local.rosterAfter(-1).last() }))
            val (won, lost) = if (entryHash(a.local.rosterAfter(-1).last()) == entryHash(winner)) a to b else b to a
            // The winner already pulled the loser's entry, as when both pull at the same moment.
            won.local.addEntries(listOf(lost.local.rosterAfter(-1).last()))

            meet(client = lost, server = won)

            for (member in listOf(a, b)) assertEquals(entryHash(winner), entryHash(member.local.rosterAfter(-1).last()))
        }

    @Test
    fun a_live_message_travels_in_a_lan_session() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = member(g, g.a)
            val b = member(g, g.b)
            val heard = ArrayList<ByteArray>()
            meet(client = a, server = b, envelope = byteArrayOf(7, 7), heard = heard)
            assertEquals(listOf(listOf<Byte>(7, 7)), heard.map { it.toList() })
        }

    @Test
    fun the_accepting_member_learns_where_the_dialing_one_listens() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = member(g, g.a)
            val b = member(g, g.b)
            withContext(Dispatchers.Default) {
                val accepted = CompletableDeferred<SyncConnection>()
                val listener = WsServer(SESSION_PATH) { accepted.complete(it) }
                val port = listener.start()
                val connection = WsClient(OkHttpClient()).connect("ws://127.0.0.1:$port$SESSION_PATH")
                val serverSide = async { acceptLink(accepted.await(), b.session()) }
                val client = connectLink(connection, a.session(), port = 41151)
                val server = serverSide.await()
                assertEquals(41151, server.peerPort)
                assertEquals("127.0.0.1", accepted.await().remoteHost)
                assertEquals("the side that dialed needs no port", null, client.peerPort)
                client.close()
                server.close()
                listener.stop()
            }
        }

    @Test
    fun members_that_each_rotated_meet_and_settle_on_one_chain() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (a, b) = member(g, g.a) to member(g, g.b)
            rotateAlone(g, a)
            rotateAlone(g, b)

            dialed(client = b, server = a)

            val (onA, onB) = a.session() to b.session()
            assertEquals(onA.state.head, onB.state.head)
            assertArrayEquals(onA.currentKey(), onB.currentKey())
        }

    @Test
    fun objects_sealed_under_a_rotation_that_lost_are_read_after_settling() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (a, b) = member(g, g.a) to member(g, g.b)
            rotateAlone(g, a)
            rotateAlone(g, b)
            a.write("aaaaaaaaaaa")
            b.write("bbbbbbbbbbb")

            dialed(client = b, server = a)

            assertEquals(
                setOf(SyncBytes.toHex(g.b.did)),
                a
                    .session()
                    .pull(setOf(LINK))
                    .batches.keys,
            )
            assertEquals(
                setOf(SyncBytes.toHex(g.a.did)),
                b
                    .session()
                    .pull(setOf(LINK))
                    .batches.keys,
            )
        }

    @Test
    fun an_object_sealed_under_an_entry_that_fell_stops_only_its_sender() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val c = MemberSecrets(SyncCrypto.randomBytes(32), SyncCrypto.randomBytes(32))
            val before = g.store.head()
            val add =
                buildEntry(
                    g.group,
                    before,
                    draft(Op.ADD, g.a).copy(add = listOf(memberSubject(c.sigPublic, c.kemPublic, Role.DEVICE))),
                    listOf(g.a, c),
                )
            g.store.appendRoster(before.head, add, wraps(g.group, 0, g.epochKey, listOf(c)), null)
            val (a, b, removed) = Triple(member(g, g.a), member(g, g.b), member(g, c))

            rotateAlone(g, removed)
            removed.write("ccccccccccc")
            dialed(client = b, server = removed)

            val session = a.lanSession()
            val state = session.state
            val stays = state.members.filterKeys { it != SyncBytes.toHex(c.did) }.mapValues { it.value.kem }
            val epoch = newEpoch(g.group, state.epoch + 1, stays, session.currentKey())
            val remove =
                buildEntry(
                    g.group,
                    state,
                    draft(Op.REMOVE, g.a).copy(remove = listOf(SyncBytes.toHex(c.did)), epoch = epoch.field),
                    listOf(g.a),
                )
            a.local.appendRoster(state.head, remove, epoch.wraps, epoch.chain)
            a.write("aaaaaaaaaaa")
            dialed(client = b, server = a)

            assertEquals(
                setOf(SyncBytes.toHex(g.a.did)),
                b
                    .session()
                    .pull(setOf(LINK))
                    .batches.keys,
            )
        }

    @Test
    fun a_device_missing_its_wrap_stays_on_its_chain_and_fetches_it_over_the_lan() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (a, b) = member(g, g.a) to member(g, g.b)
            val applied = b.session().state.head
            val rotated = rotateAlone(g, a)
            b.local.addEntries(listOf(rotated))

            val refused = runCatching { b.session() }.exceptionOrNull()
            assertEquals("key-commitment", (refused as? ProtocolException)?.error?.code)
            assertEquals(
                applied,
                entryHash(
                    b.db
                        .groups()
                        .roster()
                        .toEntries()
                        .last(),
                ),
            )
            assertEquals(applied, b.lanSession().state.head)

            dialed(client = b, server = a)

            assertEquals(entryHash(rotated), b.session().state.head)
        }

    @Test
    fun a_dial_tries_lower_epochs_and_stops_after_four_attempts() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val b = member(g, g.b)
            repeat(5) { rotateAlone(g, b) }
            val epochs = ArrayList<Int>()
            val link =
                dialLink(b.lanSession(), null) {
                    object : SyncConnection {
                        override suspend fun send(bytes: ByteArray) {
                            epochs += ByteBuffer.wrap(bytes, 0, 4).int
                        }

                        override suspend fun receive(): ByteArray? = null

                        override fun close() = Unit
                    }
                }
            assertNull(link)
            assertEquals(listOf(5, 4, 3, 2), epochs)
        }

    @Test
    fun a_listener_takes_its_last_port_only_while_it_is_free() =
        runTest {
            withContext(Dispatchers.Default) {
                val first = WsServer(SESSION_PATH)
                val port = first.start()
                assertFalse(portFree(port))
                first.stop()
                val second = WsServer(SESSION_PATH)
                assertEquals(port, second.start(port))
                second.stop()
            }
        }
}
