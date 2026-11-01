package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync.transport.WsClient
import io.github.aedev.flow.sync.transport.WsServer
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.lan.LanLink
import io.github.aedev.flow.sync2.lan.LanPeer
import io.github.aedev.flow.sync2.lan.SESSION_PATH
import io.github.aedev.flow.sync2.lan.acceptLink
import io.github.aedev.flow.sync2.lan.connectLink
import io.github.aedev.flow.sync2.lan.portFree
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.renameDevice
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.roster.forkWinner
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.LocalStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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
    }

    /** A member of [g] that holds the roster and its own wrap, as a LAN group member would. */
    private suspend fun TestScope.member(
        g: LinkedGroup,
        keys: MemberSecrets,
    ): Member {
        val db = g.deviceDb()
        val local = heldStore(db)
        local.addEntries(g.store.roster)
        val did = SyncBytes.toHex(keys.did)
        g.store.keys[0 to did]?.let { local.putKey(0, did, it) }
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

    private suspend fun pullBoth(
        link: LanLink,
        member: Member,
        envelope: ByteArray? = null,
        heard: MutableList<ByteArray> = ArrayList(),
    ) = coroutineScope {
        val peer = LanPeer(link, member.session(), member.local) { heard += it }
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
