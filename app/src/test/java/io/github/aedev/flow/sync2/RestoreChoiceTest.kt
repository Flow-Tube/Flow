package io.github.aedev.flow.sync2

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.changes.batchContent
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.crypto.recoveryKeys
import io.github.aedev.flow.sync2.crypto.recoverySecret
import io.github.aedev.flow.sync2.crypto.sha256
import io.github.aedev.flow.sync2.crypto.unwrapEpochKey
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.addEntries
import io.github.aedev.flow.sync2.data.keptDevices
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.device.StoreCredentials
import io.github.aedev.flow.sync2.device.WebDavLogin
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.engine.writeSnapshot
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.createWebDavGroup
import io.github.aedev.flow.sync2.linking.finishRestore
import io.github.aedev.flow.sync2.linking.previewRestore
import io.github.aedev.flow.sync2.linking.previewWebDavRestore
import io.github.aedev.flow.sync2.linking.removeDevices
import io.github.aedev.flow.sync2.linking.renameDevice
import io.github.aedev.flow.sync2.linking.replaceRecovery
import io.github.aedev.flow.sync2.linking.restore
import io.github.aedev.flow.sync2.linking.restoreWebDavGroup
import io.github.aedev.flow.sync2.linking.rotateEpoch
import io.github.aedev.flow.sync2.live.LiveChannel
import io.github.aedev.flow.sync2.live.LiveMessage
import io.github.aedev.flow.sync2.live.sealLive
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.memberSubject
import io.github.aedev.flow.sync2.service.LinkedError
import io.github.aedev.flow.sync2.service.LinkedProfiles
import io.github.aedev.flow.sync2.service.LinkedReset
import io.github.aedev.flow.sync2.service.LinkedSync
import io.github.aedev.flow.sync2.service.SyncIndicator
import io.github.aedev.flow.sync2.store.ChangeList
import io.github.aedev.flow.sync2.store.ChangedObject
import io.github.aedev.flow.sync2.store.FileLayout
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.KeyName
import io.github.aedev.flow.sync2.store.LocalStore
import io.github.aedev.flow.sync2.store.MAX_OBJECT_SIZE
import io.github.aedev.flow.sync2.store.Mailbox
import io.github.aedev.flow.sync2.store.ObjectType
import io.github.aedev.flow.sync2.store.ReadBudget
import io.github.aedev.flow.sync2.store.Remote
import io.github.aedev.flow.sync2.store.StoreException
import io.github.aedev.flow.sync2.store.StoreLogin
import io.github.aedev.flow.sync2.store.StorePaths
import io.github.aedev.flow.sync2.store.kcName
import io.github.aedev.flow.sync2.store.parseObject
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/** A restore keeps only the devices the user chooses, and seals nothing before the others are signed out (spec/protocol.md, Recovery). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RestoreChoiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dav = WebDavStandIn()
    private val export = mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"aaaaaaaaaaa"}"""), 1, ""))
    private val now = LiveMessage.Now(video = "aaaaaaaaaaa", title = "t", state = LiveMessage.PLAYING)

    @After
    fun stop() = dav.close()

    private fun id(member: MemberSecrets) = SyncBytes.toHex(member.did)

    /** A device restored on [store], as `restoreGroup` leaves it: the choice saved, the others not signed out yet. */
    private suspend fun TestScope.restoredKeeping(
        g: LinkedGroup,
        store: MemoryStore = g.store,
        keep: Set<String> = emptySet(),
    ): Pair<MemberSecrets, LinkedDatabase> {
        val device = newMember()
        val restored = restore(store, store, g.recovery, device, RESTORED)
        val db = emptyDb()
        db.groups().addEntries(restored.entries)
        db.groups().saveGroup(
            GroupRecord(SyncBytes.toHex(g.group), "memory", null, link = "", restoreKeep = keep.sorted().joinToString(",")),
        )
        return device to db
    }

    private suspend fun finish(
        store: MemoryStore,
        db: LinkedDatabase,
        device: MemberSecrets,
        holdWords: (String) -> Unit = {},
    ) = finishRestore(Session.open(db, store, device), device, holdWords) { Session.open(db, store, device) }

    private fun MemoryStore.objectsOf(device: MemberSecrets) = objects.keys.filter { it.startsWith("dev/${id(device)}/") }

    @Test
    fun the_devices_are_listed_by_name_and_nothing_is_written() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val dbA = g.deviceDb()
            renameDevice(Session.open(dbA, g.store, g.a), g.a, id(g.a), DeviceMeta("app-a", "Phone", "android"))
            // The name is sealed under an older epoch, so reading it walks the history chain.
            rotateEpoch(Session.open(dbA, g.store, g.a), g.a)
            val entries = g.store.roster.size
            val keys = g.store.keys.size

            val devices = previewRestore(g.store, g.recovery)

            assertEquals(mapOf(id(g.a) to "Phone", id(g.b) to ""), devices.associate { it.did to it.name })
            assertEquals(entries, g.store.roster.size)
            assertEquals(keys, g.store.keys.size)
        }

    @Test
    fun used_words_are_refused_before_the_question() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            restore(g.store, g.store, g.recovery, newMember(), RESTORED)
            val error = runCatching { previewRestore(g.store, g.recovery) }.exceptionOrNull()
            assertTrue("$error", error is LinkedException.RecoveryUsed)
        }

    @Test
    fun a_restore_signs_out_every_device_not_chosen_and_keeps_what_they_wrote() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            Session.open(g.deviceDb(), g.store, g.a).push(export, LINK)
            val lastOfA =
                g.store.objects
                    .getValue(StorePaths.log(id(g.a), 1))
                    .second
            val (device, db) = restoredKeeping(g)

            val finished = finish(g.store, db, device)

            val last = g.store.roster.last()
            assertEquals(
                listOf(Op.RESTORE, Op.RECOVERY, Op.REMOVE),
                g.store.roster
                    .takeLast(3)
                    .map { it.op },
            )
            assertEquals(listOf(id(g.a), id(g.b)).sorted(), last.remove)
            val cut = last.cut.orEmpty()
            assertEquals(setOf(id(g.a)), cut.keys)
            assertEquals(1L, cut.getValue(id(g.a)).seq)
            assertEquals(SyncBytes.toHex(sha256(lastOfA)), cut.getValue(id(g.a)).hash)
            val devices =
                g.store
                    .head()
                    .members
                    .filterValues { it.role == Role.DEVICE }
                    .keys
            assertEquals(setOf(id(device)), devices)
            assertFalse(finished.removalPending)
            assertNull(db.groups().group()?.keptDevices)
            assertTrue(id(g.a) in finished.pull(setOf(LINK)).batches)
        }

    @Test
    fun a_device_that_was_chosen_stays_and_is_told_about_the_restore() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val dbA = g.deviceDb()
            val (device, db) = restoredKeeping(g, keep = setOf(id(g.a)))

            finish(g.store, db, device)

            assertEquals(
                listOf(id(g.b)),
                g.store.roster
                    .last()
                    .remove,
            )
            assertEquals(3, Session.open(dbA, g.store, g.a).state.epoch)
            assertNotNull(dbA.groups().group()?.recoveryNoticeDevice)
        }

    @Test
    fun with_every_device_chosen_nothing_is_removed() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (device, db) = restoredKeeping(g, keep = setOf(id(g.a), id(g.b)))

            val finished = finish(g.store, db, device)

            assertEquals(
                Op.RECOVERY,
                g.store.roster
                    .last()
                    .op,
            )
            assertFalse(finished.removalPending)
        }

    @Test
    fun a_device_that_appears_after_the_question_is_signed_out_too() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val keep = previewRestore(g.store, g.recovery).first { it.did == id(g.a) }.did
            val late = newMember()
            val state = g.store.head()
            val add =
                buildEntry(
                    g.group,
                    state,
                    draft(Op.ADD, g.a).copy(add = listOf(memberSubject(late.sigPublic, late.kemPublic, Role.DEVICE))),
                    listOf(g.a, late),
                )
            g.store.appendRoster(state.head, add, wraps(g.group, 0, g.epochKey, listOf(late)), null)
            val (device, db) = restoredKeeping(g, keep = setOf(keep))

            finish(g.store, db, device)

            assertEquals(
                listOf(id(g.b), id(late)).sorted(),
                g.store.roster
                    .last()
                    .remove,
            )
        }

    @Test
    fun nothing_is_sealed_while_the_removal_is_pending() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (device, db) = restoredKeeping(g)
            val session = Session.open(db, g.store, device)
            assertTrue(session.removalPending)

            suspend fun refused(
                path: String,
                write: suspend () -> Unit,
            ) {
                val error = runCatching { write() }.exceptionOrNull()
                assertTrue("$path: $error", error is LinkedException.RestoreUnfinished)
                assertEquals(path, emptyList<String>(), g.store.objectsOf(device))
            }
            refused("a batch") { session.push(export, LINK) }
            refused("a snapshot") { session.writeSnapshot(export, LINK) }
            refused("a store login") { session.writeLogin(StoreLogin("user", "password")) }
            refused("a live message") { session.sealLive(now, 1) }
            Session.open(g.deviceDb(), g.store, g.a).push(export, LINK)
            refused("an ack") { session.finish(session.pull(setOf(LINK))) }
        }

    @Test
    fun the_live_channel_sends_nothing_while_the_removal_is_pending() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (device, db) = restoredKeeping(g)
            val local = heldStore(db)
            local.addEntries(g.store.roster)
            g.store.keys.forEach { (key, wrap) -> local.putKey(key.epoch, key.kc, key.name, wrap) }
            val channel = LiveChannel(context, db, mockk<DeviceKeys> { coEvery { load() } returns device }, local)

            assertNull(channel.send(now))

            db.groups().forgetRestoreKeep()
            assertNotNull(channel.send(now))
        }

    @Test
    fun a_restore_that_stopped_before_the_removal_resumes_there() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (device, db) = restoredKeeping(g)
            assertEquals(
                Op.RECOVERY,
                g.store.roster
                    .last()
                    .op,
            )

            // The next run starts from what was saved, with a new session.
            finish(g.store, db, device)

            assertEquals(
                Op.REMOVE,
                g.store.roster
                    .last()
                    .op,
            )
        }

    @Test
    fun a_restore_that_stopped_after_the_removal_does_not_remove_again() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (device, db) = restoredKeeping(g)
            val stopped =
                runCatching {
                    finishRestore(Session.open(db, g.store, device), device, {}) { throw IOException("stopped") }
                }.exceptionOrNull()
            assertTrue("$stopped", stopped is IOException)
            val entries = g.store.roster.size
            assertNotNull(db.groups().group()?.keptDevices)

            val finished = finish(g.store, db, device)

            assertEquals(entries, g.store.roster.size)
            assertFalse(finished.removalPending)
        }

    @Test
    fun a_removal_that_has_to_replace_the_words_hands_them_on() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val dbA = g.deviceDb()
            val (device, db) = restoredKeeping(g)
            replaceRecovery(Session.open(dbA, g.store, g.a), g.a)

            var words: String? = null
            finish(g.store, db, device) { words = it }

            assertNotNull(words)
            assertEquals(false, db.groups().group()?.recoverySaved)
            assertFalse(g.store.head().recoveryDue)
        }

    @Test
    fun new_words_are_held_and_marked_unsaved_when_the_next_read_of_the_roster_fails() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (device, db) = restoredKeeping(g)
            db.groups().setRecoverySaved(true)
            replaceRecovery(Session.open(g.deviceDb(), g.store, g.a), g.a)

            var words: String? = null
            val stopped =
                runCatching {
                    finishRestore(Session.open(db, g.store, device), device, { words = it }) { throw IOException("stopped") }
                }.exceptionOrNull()

            assertTrue("$stopped", stopped is IOException)
            assertEquals(
                Op.REMOVE,
                g.store.roster
                    .last()
                    .op,
            )
            val next = recoveryKeys(checkNotNull(recoverySecret(checkNotNull(words))))
            assertEquals(
                Role.RECOVERY,
                g.store
                    .head()
                    .members[id(next)]
                    ?.role,
            )
            assertEquals(false, db.groups().group()?.recoverySaved)
        }

    @Test
    fun a_device_an_older_view_still_shows_cannot_open_what_is_written_next() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val older = MemoryStore()
            older.roster += g.store.roster
            older.keys += g.store.keys
            removeDevices(Session.open(g.deviceDb(), g.store, g.a), g.a, listOf(id(g.b)))
            assertFalse(id(g.b) in g.store.head().members)
            assertTrue(id(g.b) in older.head().members)

            val (device, db) = restoredKeeping(g, store = older)
            val session = finish(older, db, device)
            session.push(export, LINK)

            val epoch = session.state.epoch
            assertFalse(id(g.b) in older.head().members)
            assertFalse(older.holdsKey(epoch, id(g.b)))
            val parsed = parseObject(older.objects.getValue(StorePaths.log(id(device), 1)).second, g.group)
            assertEquals(epoch, parsed.header.epoch)
            // The key before it was wrapped to B, as rule 5 asks, and it seals nothing.
            val commitment = SyncBytes.b64urlDecode(checkNotNull(older.roster.first { it.epoch?.n == epoch - 1 }.epoch).kc)
            val wrap = older.keys.getValue(KeyName(epoch - 1, kcName(commitment), id(g.b)))
            val lastHeld = unwrapEpochKey(g.b.kemSecret, g.group, epoch - 1, g.b.did, wrap)
            val error = runCatching { parsed.open(purposeKey(lastHeld, g.group, epoch, ObjectType.CHANGES.purpose)) }.exceptionOrNull()
            assertTrue("$error", error is ProtocolException)
        }

    private fun sync(
        db: LinkedDatabase,
        device: MemberSecrets,
        local: LocalStore = heldStore(db),
    ) = LinkedSync(
        context,
        db,
        mockk<DeviceKeys> { coEvery { load() } returns device },
        mockk(),
        mockk(),
        local,
        SyncIndicator(context),
        mockk<LinkedReset> { every { leaveFailed } returns MutableStateFlow(null) },
        mockk<StoreCredentials> { coEvery { webDav() } returns WebDavLogin(WebDavStandIn.USER, WebDavStandIn.PASSWORD) },
        mockk(),
        mockk<LinkedProfiles> { coEvery { links(any()) } returns null },
        mockk(),
    )

    @Test
    fun the_first_sync_of_a_restored_device_signs_out_the_others() =
        runTest {
            val first = newMember()
            val (words, _) = createWebDavGroup(emptyDb(), dav.store(), first, RESTORED, LINK)
            val devices = previewWebDavRestore(dav.store(), words)
            assertEquals(listOf(RESTORED.name), devices.map { it.name })
            val device = newMember()
            val db = emptyDb()
            restoreWebDavGroup(db, dav.store(), words, device, RESTORED, keep = emptySet())
            assertEquals(emptySet<String>(), db.groups().group()?.keptDevices)

            val result = sync(db, device).syncNow()

            assertNull(result.error)
            val roster = FileLayout(dav.store(), id(device)).rosterView()
            assertEquals(Op.REMOVE, roster.maxBy { it.seq }.op)
            assertEquals(listOf(id(first)), roster.maxBy { it.seq }.remove)
            assertNull(db.groups().group()?.keptDevices)
        }

    @Test
    fun the_sync_hands_on_words_its_removal_had_to_make() =
        runTest {
            val first = newMember()
            val dbFirst = emptyDb()
            val (words, _) = createWebDavGroup(dbFirst, dav.store(), first, RESTORED, LINK)
            val device = newMember()
            val db = emptyDb()
            restoreWebDavGroup(db, dav.store(), words, device, RESTORED, keep = emptySet())
            val storeFirst = GroupStore(heldStore(dbFirst), FileLayout(dav.store(), id(first)))
            storeFirst.download(id(first), 0, objects = false)
            replaceRecovery(Session.open(dbFirst, storeFirst, first), first)

            val sync = sync(db, device)
            assertNull(sync.syncNow().error)

            assertNotNull(sync.replacedWords.value)
        }

    @Test
    fun a_restored_device_that_cannot_reach_the_store_stays_pending() =
        runTest {
            val (words, _) = createWebDavGroup(emptyDb(), dav.store(), newMember(), RESTORED, LINK)
            val device = newMember()
            val db = emptyDb()
            restoreWebDavGroup(db, dav.store(), words, device, RESTORED, keep = emptySet())
            val local = heldStore(db)
            GroupStore(local, FileLayout(dav.store(), id(device))).download(id(device), 0, objects = false)
            dav.close()

            val result = sync(db, device, local).syncNow()

            assertNotNull(result.error)
            assertEquals(emptySet<String>(), db.groups().group()?.keptDevices)
            assertTrue(local.rosterAfter(-1).none { it.op == Op.REMOVE })
        }

    /** The store one object to a page, so a read can stop between objects. Asking for a path in [failing] throws what it names. */
    private class OnePerPage(
        private val store: MemoryStore,
    ) : Remote,
        ChangeList by store,
        Mailbox by store {
        val asked = ArrayList<String>()
        val failing = HashMap<String, StoreException>()

        override suspend fun changes(cursor: Long): Pair<List<ChangedObject>, Long> {
            val page = store.changes(cursor).first.take(1)
            return page to (page.firstOrNull()?.let { store.objects.getValue(it.path).first } ?: cursor)
        }

        override suspend fun getObject(path: String): ByteArray? {
            failing[path]?.let { throw it }
            asked += path
            return store.getObject(path)
        }
    }

    /** The store with its chain boxes failing as [stop] says, until it's cleared. */
    private class ChainBoxFails(
        private val store: MemoryStore,
    ) : Remote,
        ChangeList by store,
        Mailbox by store {
        var stop: StoreException? = null

        override suspend fun key(
            epoch: Int,
            kc: String,
            name: String,
        ): ByteArray? {
            if (name == "chain") stop?.let { throw it }
            return store.key(epoch, kc, name)
        }
    }

    /** A store whose cursor always moves on: each page names one more object, which it doesn't have. */
    private class EndlessPages(
        store: MemoryStore,
    ) : Remote,
        ChangeList by store,
        Mailbox by store {
        private var pages = 0L

        override suspend fun changes(cursor: Long): Pair<List<ChangedObject>, Long> {
            check(++pages < 10_000) { "the pages never ended" }
            return listOf(ChangedObject(StorePaths.log("00".repeat(16), pages), 1)) to pages
        }
    }

    private fun syncOn(
        db: LinkedDatabase,
        device: MemberSecrets,
        server: Remote,
        budget: () -> ReadBudget = ::ReadBudget,
    ): LinkedSync {
        val local = heldStore(db)
        return spyk(sync(db, device, local)) { coEvery { groupStore(any(), any()) } coAnswers { GroupStore(local, server, budget) } }
    }

    private suspend fun Session.wrote(count: Int): List<String> {
        repeat(
            count,
        ) { write(ObjectType.CHANGES, batchContent(SyncCollection.WATCH_HISTORY, "00".repeat(32), LINK, export.values.single().lines)) }
        return (1..count).map { StorePaths.log(me, it.toLong()) }
    }

    private suspend fun TestScope.wrote(
        g: LinkedGroup,
        member: MemberSecrets,
        count: Int,
    ) = Session.open(g.deviceDb(), g.store, member).wrote(count)

    private fun copyOf(store: MemoryStore) =
        MemoryStore().also {
            it.roster += store.roster
            it.keys += store.keys
            it.objects += store.objects
        }

    private suspend fun assertStillPending(
        store: MemoryStore,
        db: LinkedDatabase,
        device: MemberSecrets,
        entries: Int,
    ) {
        assertEquals("nothing was added to the roster", entries, store.roster.size)
        assertEquals("nothing was sealed", emptyList<String>(), store.objectsOf(device))
        assertNotNull("the choice is still saved", db.groups().group()?.keptDevices)
        assertEquals("no place was saved in the logs", 0, db.groups().streams().size)
    }

    @Test
    fun a_read_the_connection_stopped_goes_on_at_the_next_sync_and_cuts_as_one_read_would() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val paths = wrote(g, g.a, 3)
            wrote(g, g.b, 2)
            val whole = copyOf(g.store)
            val (device, db) = restoredKeeping(g)
            val entries = g.store.roster.size
            val server = OnePerPage(g.store).apply { failing[paths[1]] = StoreException.Network(IOException("dropped")) }
            val sync = syncOn(db, device, server)

            assertEquals(LinkedError.UNREACHABLE, sync.syncNow().error)
            assertStillPending(g.store, db, device, entries)
            val asked = server.asked.toList()

            server.failing.clear()
            assertNull(sync.syncNow().error)

            assertEquals("what it held isn't asked for again", emptyList<String>(), server.asked.drop(asked.size).filter { it in asked })
            val (other, otherDb) = restoredKeeping(g, store = whole)
            assertNull(syncOn(otherDb, other, whole).syncNow().error)
            assertEquals(
                whole.roster.last().cut,
                g.store.roster
                    .last()
                    .cut,
            )
            assertEquals(
                setOf(id(g.a), id(g.b)),
                g.store.roster
                    .last()
                    .cut
                    ?.keys,
            )
            assertEquals(
                3L,
                g.store.roster
                    .last()
                    .cut
                    ?.getValue(id(g.a))
                    ?.seq,
            )
        }

    @Test
    fun a_log_that_stops_at_an_object_the_store_lacks_ends_there_and_the_removal_goes_ahead() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val paths = wrote(g, g.a, 3)
            val first =
                g.store.objects
                    .getValue(paths[0])
                    .second
            g.store.take(paths[1])
            val (device, db) = restoredKeeping(g)

            assertNull(syncOn(db, device, g.store).syncNow().error)

            val last = g.store.roster.last()
            assertEquals(Op.REMOVE, last.op)
            assertEquals(1L, last.cut?.getValue(id(g.a))?.seq)
            assertEquals(SyncBytes.toHex(sha256(first)), last.cut?.getValue(id(g.a))?.hash)
            assertNull(db.groups().group()?.keptDevices)
        }

    @Test
    fun a_log_that_stops_at_a_bad_copy_ends_there_and_the_removal_goes_ahead() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val paths = wrote(g, g.a, 3)
            g.store.take(paths[1])
            g.store.put(paths[1], ByteArray(512))
            val (device, db) = restoredKeeping(g)

            assertNull(syncOn(db, device, g.store).syncNow().error)

            assertEquals(
                Op.REMOVE,
                g.store.roster
                    .last()
                    .op,
            )
            assertEquals(
                1L,
                g.store.roster
                    .last()
                    .cut
                    ?.getValue(id(g.a))
                    ?.seq,
            )
        }

    @Test
    fun a_log_that_stops_at_an_object_a_server_answers_over_the_size_limit_ends_there() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val paths = wrote(g, g.a, 3)
            val first =
                g.store.objects
                    .getValue(paths[0])
                    .second
            val (device, db) = restoredKeeping(g)
            val server = OnePerPage(g.store).apply { failing[paths[1]] = StoreException.TooLarge() }

            assertNull(syncOn(db, device, server).syncNow().error)

            val last = g.store.roster.last()
            assertEquals(Op.REMOVE, last.op)
            assertEquals(1L, last.cut?.getValue(id(g.a))?.seq)
            assertEquals(SyncBytes.toHex(sha256(first)), last.cut?.getValue(id(g.a))?.hash)
            assertNull(db.groups().group()?.keptDevices)
        }

    @Test
    fun a_server_error_on_an_object_holds_the_restore_until_the_store_recovers() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val paths = wrote(g, g.a, 3)
            val last =
                g.store.objects
                    .getValue(paths[2])
                    .second
            val (device, db) = restoredKeeping(g)
            val entries = g.store.roster.size
            val server = OnePerPage(g.store).apply { failing[paths[1]] = StoreException.Refused(503, "busy") }
            val sync = syncOn(db, device, server)

            assertNotNull(sync.syncNow().error)
            assertStillPending(g.store, db, device, entries)

            server.failing.clear()
            assertNull(sync.syncNow().error)

            val removal = g.store.roster.last()
            assertEquals(Op.REMOVE, removal.op)
            assertEquals(3L, removal.cut?.getValue(id(g.a))?.seq)
            assertEquals(SyncBytes.toHex(sha256(last)), removal.cut?.getValue(id(g.a))?.hash)
        }

    @Test
    fun a_server_error_on_a_chain_box_holds_the_restore_until_the_store_recovers() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            wrote(g, g.a, 3)
            val (device, db) = restoredKeeping(g)
            val entries = g.store.roster.size
            val server = ChainBoxFails(g.store).apply { stop = StoreException.Refused(503, "busy") }
            val sync = syncOn(db, device, server)

            assertNotNull(sync.syncNow().error)
            assertStillPending(g.store, db, device, entries)

            server.stop = null
            assertNull(sync.syncNow().error)

            assertEquals(
                Op.REMOVE,
                g.store.roster
                    .last()
                    .op,
            )
            assertEquals(
                3L,
                g.store.roster
                    .last()
                    .cut
                    ?.getValue(id(g.a))
                    ?.seq,
            )
        }

    @Test
    fun a_refused_object_ends_its_log_there() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val paths = wrote(g, g.a, 3)
            val (device, db) = restoredKeeping(g)
            val server = OnePerPage(g.store).apply { failing[paths[1]] = StoreException.Refused(403, "refused") }

            assertNull(syncOn(db, device, server).syncNow().error)

            assertEquals(
                Op.REMOVE,
                g.store.roster
                    .last()
                    .op,
            )
            assertEquals(
                1L,
                g.store.roster
                    .last()
                    .cut
                    ?.getValue(id(g.a))
                    ?.seq,
            )
        }

    @Test
    fun a_refused_chain_box_or_one_over_the_limit_drops_the_older_log_with_no_cut() =
        runTest {
            for (stop in listOf(StoreException.Refused(403, "refused"), StoreException.TooLarge())) {
                val g = LinkedGroup(this).apply { create() }
                wrote(g, g.a, 3)
                val (device, db) = restoredKeeping(g)

                assertNull(syncOn(db, device, ChainBoxFails(g.store).apply { this.stop = stop }).syncNow().error)

                assertEquals(
                    Op.REMOVE,
                    g.store.roster
                        .last()
                        .op,
                )
                assertNull(
                    g.store.roster
                        .last()
                        .cut,
                )
            }
        }

    @Test
    fun a_snapshot_blob_over_the_limit_with_its_log_collected_drops_the_log_with_no_cut() =
        runTest {
            val first = newMember()
            val dbFirst = emptyDb()
            val (words, _) = createWebDavGroup(dbFirst, dav.store(), first, RESTORED, LINK)
            val storeFirst = GroupStore(heldStore(dbFirst), FileLayout(dav.store(), id(first)))
            storeFirst.download(id(first), 0, objects = false)
            val session = Session.open(dbFirst, storeFirst, first)
            session.wrote(2)
            session.writeSnapshot(export, LINK)
            storeFirst.upload(id(first), 0)
            dav.files.remove("flow/${StorePaths.log(id(first), 1)}")
            val blob = dav.files.keys.first { it.endsWith(".b") }
            dav.files[blob] = ByteArray(MAX_OBJECT_SIZE + 1)
            val device = newMember()
            val db = emptyDb()
            restoreWebDavGroup(db, dav.store(), words, device, RESTORED, keep = emptySet())

            assertNull(sync(db, device).syncNow().error)

            val removal = FileLayout(dav.store(), id(device)).rosterView().maxBy { it.seq }
            assertEquals(Op.REMOVE, removal.op)
            assertNull(removal.cut)
        }

    @Test
    fun a_log_that_stops_at_an_object_a_file_store_holds_over_the_size_limit_ends_there() =
        runTest {
            val first = newMember()
            val dbFirst = emptyDb()
            val (words, _) = createWebDavGroup(dbFirst, dav.store(), first, RESTORED, LINK)
            val storeFirst = GroupStore(heldStore(dbFirst), FileLayout(dav.store(), id(first)))
            storeFirst.download(id(first), 0, objects = false)
            val paths = Session.open(dbFirst, storeFirst, first).wrote(3)
            storeFirst.upload(id(first), 0)
            val firstObject = checkNotNull(dav.files["flow/${paths[0]}"])
            dav.files["flow/${paths[1]}"] = ByteArray(MAX_OBJECT_SIZE + 1)
            val device = newMember()
            val db = emptyDb()
            restoreWebDavGroup(db, dav.store(), words, device, RESTORED, keep = emptySet())

            assertNull(sync(db, device).syncNow().error)

            val removal = FileLayout(dav.store(), id(device)).rosterView().maxBy { it.seq }
            assertEquals(Op.REMOVE, removal.op)
            assertEquals(1L, removal.cut?.getValue(id(first))?.seq)
            assertEquals(SyncBytes.toHex(sha256(firstObject)), removal.cut?.getValue(id(first))?.hash)
            assertNull(db.groups().group()?.keptDevices)
        }

    @Test
    fun a_store_whose_pages_never_end_holds_the_restore_but_each_sync_ends() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (device, db) = restoredKeeping(g)
            val entries = g.store.roster.size
            val sync = syncOn(db, device, EndlessPages(g.store))

            repeat(2) {
                assertEquals(LinkedError.RESTORE_UNFINISHED, sync.syncNow().error)
                assertStillPending(g.store, db, device, entries)
            }
        }

    @Test
    fun a_read_stopped_on_its_limit_removes_nothing_goes_on_and_cuts_as_one_read_would() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            wrote(g, g.a, 3)
            wrote(g, g.b, 2)
            val whole = copyOf(g.store)
            val (device, db) = restoredKeeping(g)
            val entries = g.store.roster.size
            val server = OnePerPage(g.store)
            val sync = syncOn(db, device, server) { ReadBudget(requests = 2) }

            repeat(2) {
                assertEquals(LinkedError.RESTORE_UNFINISHED, sync.syncNow().error)
                assertStillPending(g.store, db, device, entries)
            }
            assertNull(sync.syncNow().error)

            assertEquals("each object was asked for once", server.asked.distinct(), server.asked)
            val (other, otherDb) = restoredKeeping(g, store = whole)
            assertNull(syncOn(otherDb, other, whole).syncNow().error)
            val cut =
                g.store.roster
                    .last()
                    .cut
            assertEquals(
                Op.REMOVE,
                g.store.roster
                    .last()
                    .op,
            )
            assertEquals(whole.roster.last().cut, cut)
            assertEquals(mapOf(id(g.a) to 3L, id(g.b) to 2L), cut?.mapValues { it.value.seq })
            assertNull(db.groups().group()?.keptDevices)
        }

    @Test
    fun a_read_of_a_file_store_stopped_on_its_limit_goes_on_at_the_next_sync() =
        runTest {
            val first = newMember()
            val dbFirst = emptyDb()
            val (words, _) = createWebDavGroup(dbFirst, dav.store(), first, RESTORED, LINK)
            val storeFirst = GroupStore(heldStore(dbFirst), FileLayout(dav.store(), id(first)))
            storeFirst.download(id(first), 0, objects = false)
            val paths = Session.open(dbFirst, storeFirst, first).wrote(3)
            storeFirst.upload(id(first), 0)
            val device = newMember()
            val db = emptyDb()
            restoreWebDavGroup(db, dav.store(), words, device, RESTORED, keep = emptySet())
            val roster = FileLayout(dav.store(), id(device))
            val sync = syncOn(db, device, roster) { ReadBudget(requests = 2) }

            assertEquals(LinkedError.RESTORE_UNFINISHED, sync.syncNow().error)
            assertTrue(roster.rosterView().none { it.op == Op.REMOVE })
            assertEquals(emptySet<String>(), db.groups().group()?.keptDevices)
            assertTrue(dav.files.keys.none { it.startsWith("flow/dev/${id(device)}/") })

            assertNull(sync.syncNow().error)

            val removal = roster.rosterView().maxBy { it.seq }
            assertEquals(Op.REMOVE, removal.op)
            assertEquals(3L, removal.cut?.getValue(id(first))?.seq)
            assertEquals(SyncBytes.toHex(sha256(checkNotNull(dav.files["flow/${paths[2]}"]))), removal.cut?.getValue(id(first))?.hash)
        }

    @Test
    fun a_sync_with_no_restore_under_way_just_goes_on_from_where_its_read_stopped() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val paths = wrote(g, g.a, 3)
            val db = g.deviceDb()
            val sync = syncOn(db, g.b, OnePerPage(g.store)) { ReadBudget(requests = 2) }

            assertNull(sync.syncNow().error)
            assertEquals(
                g.store.objects
                    .getValue(paths[1])
                    .first,
                db.groups().group()?.serverCursor,
            )

            assertNull(sync.syncNow().error)
            assertEquals(
                g.store.objects
                    .getValue(paths[2])
                    .first,
                db.groups().group()?.serverCursor,
            )
        }
}
