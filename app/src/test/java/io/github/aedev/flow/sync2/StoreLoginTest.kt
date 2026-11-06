package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.Purpose
import io.github.aedev.flow.sync2.crypto.encodePayload
import io.github.aedev.flow.sync2.crypto.epochCommitment
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.crypto.sealChain
import io.github.aedev.flow.sync2.crypto.sha256
import io.github.aedev.flow.sync2.crypto.wrapEpochKey
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.WEBDAV_STORE
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.engine.loginWaiting
import io.github.aedev.flow.sync2.linking.leaveGroup
import io.github.aedev.flow.sync2.linking.removeDevices
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.canonicalEntry
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.roster.epochDraft
import io.github.aedev.flow.sync2.roster.memberSubject
import io.github.aedev.flow.sync2.store.FileLayout
import io.github.aedev.flow.sync2.store.FileStore
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.Header
import io.github.aedev.flow.sync2.store.ObjectType
import io.github.aedev.flow.sync2.store.StoreException
import io.github.aedev.flow.sync2.store.StoreLogin
import io.github.aedev.flow.sync2.store.StorePaths
import io.github.aedev.flow.sync2.store.ZERO_HASH
import io.github.aedev.flow.sync2.store.checkFolder
import io.github.aedev.flow.sync2.store.decodeStoreLogin
import io.github.aedev.flow.sync2.store.kcName
import io.github.aedev.flow.sync2.store.sealObject
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Replacing a WebDAV folder's login from a device (spec/protocol.md, Replacing a store's login). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class StoreLoginTest {
    private val dav = WebDavStandIn()
    private val newLogin = StoreLogin(WebDavStandIn.USER, "the new one")

    @After
    fun stop() = dav.close()

    private suspend fun publish(g: LinkedGroup) {
        val files = dav.store()
        g.store.roster.forEach { files.put(StorePaths.roster(it.seq, entryHash(it)), canonicalEntry(it).toByteArray()) }
        g.store.keys.forEach { (key, wrap) -> files.put(StorePaths.key(key.epoch, key.kc, key.name), wrap) }
        files.put("store.json", """{"format":"FLOW-SYNC/2","group":"${SyncBytes.toHex(g.group)}"}""".toByteArray())
    }

    private inner class Device(
        val keys: MemberSecrets,
        val db: LinkedDatabase,
        val store: GroupStore,
    ) {
        val me = SyncBytes.toHex(keys.did)

        suspend fun session(): Session {
            store.download(me, 0, objects = true)
            return Session.open(db, store, keys)
        }

        suspend fun upload() = store.upload(me, 0)
    }

    private suspend fun TestScope.device(
        g: LinkedGroup,
        keys: MemberSecrets,
    ): Device {
        val db = g.deviceDb()
        db.groups().saveGroup(checkNotNull(db.groups().group()).copy(storeUrl = WEBDAV_STORE + dav.url))
        val me = SyncBytes.toHex(keys.did)
        return Device(keys, db, GroupStore(heldStore(db), FileLayout(dav.store(), me)))
    }

    private suspend fun LinkedGroup.withC(): MemberSecrets {
        val c = newMember()
        val before = store.head()
        val add =
            buildEntry(
                group,
                before,
                draft(Op.ADD, a).copy(add = listOf(memberSubject(c.sigPublic, c.kemPublic, Role.DEVICE))),
                listOf(a, c),
            )
        store.appendRoster(before.head, add, wraps(group, 0, epochKey, listOf(c)), null)
        return c
    }

    /** Puts a login object on the folder at the next `seq` of [signer]'s log and returns the position after it. */
    private suspend fun plant(
        g: LinkedGroup,
        signer: MemberSecrets,
        epoch: Int,
        key: ByteArray,
        position: Pair<Long, ByteArray>,
    ): Pair<Long, ByteArray> {
        val (seq, prev) = position
        val sealed =
            sealObject(
                Header(ObjectType.LOGIN, g.group, epoch, signer.did, seq, prev),
                purposeKey(key, g.group, epoch, Purpose.STORE),
                encodePayload("""{"k":"webdav","n":"u","w":"planted"}""".toByteArray()),
                signer.sigSeed,
            )
        dav.store().put(StorePaths.log(SyncBytes.toHex(signer.did), seq), sealed)
        return seq + 1 to sha256(sealed)
    }

    @Test
    fun a_new_login_reaches_a_device_and_the_folder_takes_it_once_the_old_one_is_refused() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            publish(g)
            val (a, b) = device(g, g.a) to device(g, g.b)

            a.session().writeLogin(newLogin)
            a.upload()
            assertEquals(listOf(b.me), loginWaiting(a.db, a.me))

            val session = b.session()
            val pulled = session.pull(setOf(LINK))
            assertEquals(newLogin, pulled.login)
            session.finish(pulled)
            b.upload()

            val after = a.session()
            after.finish(after.pull(setOf(LINK)))
            assertEquals(emptyList<String>(), loginWaiting(a.db, a.me))

            dav.password = newLogin.password
            val group = SyncBytes.toHex(g.group)
            assertTrue(runCatching { checkFolder(dav.store(), group) }.exceptionOrNull() is StoreException.LoginRefused)
            assertTrue(checkFolder(dav.store(newLogin.password), group) != null)
        }

    @Test
    fun a_folder_that_holds_another_group_is_not_taken() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            publish(g)
            assertNull(checkFolder(dav.store(), "00".repeat(16)))
        }

    @Test
    fun a_login_names_no_address_and_only_a_webdav_one_is_read() {
        val read = decodeStoreLogin("""{"k":"webdav","n":"u","w":"p","u":"https://elsewhere.example/"}""".toByteArray())
        assertEquals(StoreLogin("u", "p"), read)
        assertNull(decodeStoreLogin("""{"a":"id","k":"s3","s":"secret"}""".toByteArray()))
    }

    @Test
    fun a_login_on_a_group_with_a_flow_server_is_ignored() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            publish(g)
            plant(g, g.a, 0, g.epochKey, 1L to ZERO_HASH)
            val db = g.deviceDb()
            val me = SyncBytes.toHex(g.b.did)
            val store = GroupStore(heldStore(db), FileLayout(dav.store(), me))
            store.download(me, 0, objects = true)
            assertNull(Session.open(db, store, g.b).pull(setOf(LINK)).login)
        }

    /** A `remove` wins over a `rotate` that lost and stands, so the rotate's key isn't the chain's. */
    @Test
    fun a_login_outside_the_chains_current_key_is_ignored_and_the_log_goes_on() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val c = g.withC()
            publish(g)
            val before = g.store.head()
            val b = device(g, g.b)
            b.session()

            val files: FileStore = dav.store()
            val losingKey = SyncCrypto.randomBytes(32)
            val everyone = listOf(g.a, g.b, c, g.recovery)
            val rotate =
                buildEntry(
                    g.group,
                    before,
                    draft(Op.ROTATE, g.b).copy(epoch = epochDraft(losingKey, g.group, 1, everyone.map(::hex))),
                    listOf(g.b),
                )
            val losingKc = kcName(epochCommitment(losingKey, g.group, 1))
            files.put(StorePaths.key(1, losingKc, hex(g.b)), wrapEpochKey(g.b.kemPublic, g.group, 1, g.b.did, losingKey))
            put(files, rotate)

            val removeKey = SyncCrypto.randomBytes(32)
            val stays = listOf(g.a, g.b, g.recovery)
            val remove =
                buildEntry(
                    g.group,
                    before,
                    draft(Op.REMOVE, g.a).copy(remove = listOf(hex(c)), epoch = epochDraft(removeKey, g.group, 1, stays.map(::hex))),
                    listOf(g.a),
                )
            val kc = kcName(epochCommitment(removeKey, g.group, 1))
            for (member in stays) {
                files.put(
                    StorePaths.key(1, kc, hex(member)),
                    wrapEpochKey(member.kemPublic, g.group, 1, member.did, removeKey),
                )
            }
            files.put(StorePaths.key(1, kc, "chain"), sealChain(removeKey, g.group, 1, g.epochKey))
            put(files, remove)

            plant(g, c, 1, losingKey, plant(g, c, 0, g.epochKey, 1L to ZERO_HASH))
            val fromA = plant(g, g.a, 1, losingKey, plant(g, g.a, 0, g.epochKey, 1L to ZERO_HASH))
            plant(g, g.a, 1, removeKey, fromA)

            val session = b.session()
            assertEquals(entryHash(remove), session.state.head)
            val pulled = session.pull(setOf(LINK))
            assertEquals("only the last one, reached by reading past the others, counts", StoreLogin("u", "planted"), pulled.login)
            session.finish(pulled)
            assertEquals(
                3L,
                b.db
                    .groups()
                    .streams()
                    .first { it.did == hex(g.a) }
                    .seq,
            )
        }

    private suspend fun put(
        files: FileStore,
        entry: Entry,
    ) = files.put(StorePaths.roster(entry.seq, entryHash(entry)), canonicalEntry(entry).toByteArray())

    private fun hex(member: MemberSecrets) = SyncBytes.toHex(member.did)

    @Test
    fun the_notice_follows_a_removal_and_clears_with_a_login() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val c = g.withC()
            publish(g)
            val (a, b) = device(g, g.a) to device(g, g.b)
            val session = a.session()
            assertNull(
                a.db
                    .groups()
                    .group()
                    ?.loginDue,
            )

            removeDevices(session, g.a, listOf(hex(c)))
            a.upload()
            assertEquals(
                1,
                a.session().let {
                    a.db
                        .groups()
                        .group()
                        ?.loginDue
                },
            )
            val onB = b.session()
            assertEquals(
                1,
                b.db
                    .groups()
                    .group()
                    ?.loginDue,
            )

            a.session().writeLogin(newLogin)
            a.upload()
            assertNull(
                a.db
                    .groups()
                    .group()
                    ?.loginDue,
            )
            val later = b.session()
            later.finish(later.pull(setOf(LINK)))
            assertNull(
                b.db
                    .groups()
                    .group()
                    ?.loginDue,
            )
            assertFalse(onB.state.members.containsKey(hex(c)))
        }

    @Test
    fun a_leave_calls_for_no_new_login() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            publish(g)
            val (a, b) = device(g, g.a) to device(g, g.b)
            leaveGroup(a.session(), g.a)
            a.upload()
            b.session()
            assertNull(
                b.db
                    .groups()
                    .group()
                    ?.loginDue,
            )
        }
}
