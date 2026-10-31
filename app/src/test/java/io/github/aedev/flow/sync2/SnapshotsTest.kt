package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.changes.IncomingBatch
import io.github.aedev.flow.sync2.crypto.Purpose
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.crypto.sha256
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.engine.chunks
import io.github.aedev.flow.sync2.engine.isDue
import io.github.aedev.flow.sync2.engine.parseManifest
import io.github.aedev.flow.sync2.engine.writeSnapshot
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.memberSubject
import io.github.aedev.flow.sync2.store.StorePaths
import io.github.aedev.flow.sync2.store.parseObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SnapshotsTest {
    private fun watched(vararg ids: String) =
        mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(ids.map { """{"title":"t","videoId":"$it"}""" }, ids.size, ""))

    private fun videos(batches: Collection<List<IncomingBatch>>) =
        batches
            .flatten()
            .flatMap { it.lines }
            .map { it.substringAfter("\"videoId\":\"").substringBefore('"') }
            .sorted()

    @Test
    fun a_snapshot_holds_every_record_and_the_logs_it_covers() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            session.push(watched("aaaaaaaaaaa", "bbbbbbbbbbb"), LINK)
            val firstHash = SyncBytes.toHex(sha256(checkNotNull(g.store.objects[StorePaths.log(SyncBytes.toHex(g.a.did), 1)]).second))
            session.writeSnapshot(watched("aaaaaaaaaaa", "bbbbbbbbbbb"), LINK)
            assertEquals(2L, session.record.ownSeq)

            val bytes = checkNotNull(g.store.objects[StorePaths.log(SyncBytes.toHex(g.a.did), 2)]).second
            val parsed = parseObject(bytes, g.group)
            val manifest = parseManifest(decodePayload(parsed.open(purposeKey(g.epochKey, g.group, 0, Purpose.SNAPSHOT))))
            assertEquals(LINK, manifest.link)
            val tip = manifest.vv.getValue(SyncBytes.toHex(g.a.did))
            assertEquals(1L, tip.seq)
            assertEquals(firstHash, tip.hash)
            assertEquals(1, manifest.parts.size)
        }

    @Test
    fun a_snapshot_is_due_after_a_megabyte_a_week_or_a_removal() =
        runTest {
            val snapshots = emptyDb().snapshots()
            val day = 24 * 60 * 60 * 1000L
            assertFalse(snapshots.isDue(LINK, 0))
            snapshots.noteMerged(LINK, 1000, nowMs = 0)
            assertFalse(snapshots.isDue(LINK, 0))
            snapshots.noteMerged(LINK, 1024 * 1024, nowMs = 0)
            assertTrue(snapshots.isDue(LINK, 0))
            snapshots.written(LINK, nowMs = 0)
            assertFalse(snapshots.isDue(LINK, day))
            assertTrue(snapshots.isDue(LINK, 7 * day))
            snapshots.written(LINK, nowMs = 7 * day)
            snapshots.snapshotSoon()
            assertTrue(snapshots.isDue(LINK, 7 * day))
        }

    @Test
    fun records_are_split_below_the_blob_limit() {
        val record = "x".repeat(2 * 1024 * 1024 / 3)
        val split = chunks(List(7) { record })
        assertEquals(7, split.sumOf { it.size })
        assertTrue(split.all { it.size in 1..2 })
    }

    @Test
    fun other_devices_read_past_a_snapshot_manifest() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = Session.open(g.deviceDb(), g.store, g.a)
            a.push(watched("aaaaaaaaaaa"), LINK)
            a.writeSnapshot(watched("aaaaaaaaaaa"), LINK)
            a.push(watched("aaaaaaaaaaa", "bbbbbbbbbbb"), LINK)
            val pulled = Session.open(g.deviceDb(), g.store, g.b).pull(setOf(LINK))
            assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), videos(pulled.batches.values))
        }

    @Test
    fun a_new_device_starts_from_the_snapshot_when_old_objects_are_gone() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = Session.open(g.deviceDb(), g.store, g.a)
            a.push(watched("aaaaaaaaaaa"), LINK)
            a.writeSnapshot(watched("aaaaaaaaaaa"), LINK)
            a.push(watched("aaaaaaaaaaa", "bbbbbbbbbbb"), LINK)
            g.store.take(StorePaths.log(SyncBytes.toHex(g.a.did), 1))

            val c = newMember()
            val state = g.store.head()
            val add =
                buildEntry(
                    g.group,
                    state,
                    draft(Op.ADD, g.a).copy(add = listOf(memberSubject(c.sigPublic, c.kemPublic, Role.DEVICE))),
                    listOf(g.a, c),
                )
            g.store.appendRoster(state.head, add, wraps(g.group, 0, g.epochKey, listOf(c)), null)
            val db = g.deviceDb()
            val reader = Session.open(db, g.store, c)
            val pulled = reader.pull(setOf(LINK))
            assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), videos(pulled.batches.values))
            reader.finish(pulled)
            assertTrue(
                Session
                    .open(db, g.store, c)
                    .pull(setOf(LINK))
                    .batches
                    .isEmpty(),
            )
        }

    @Test
    fun without_a_snapshot_a_log_missing_its_start_is_not_read() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = Session.open(g.deviceDb(), g.store, g.a)
            a.push(watched("aaaaaaaaaaa"), LINK)
            a.push(watched("aaaaaaaaaaa", "bbbbbbbbbbb"), LINK)
            g.store.take(StorePaths.log(SyncBytes.toHex(g.a.did), 1))
            assertTrue(
                Session
                    .open(g.deviceDb(), g.store, g.b)
                    .pull(setOf(LINK))
                    .batches
                    .isEmpty(),
            )
        }
}
