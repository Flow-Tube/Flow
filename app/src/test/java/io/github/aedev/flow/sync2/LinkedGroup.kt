package io.github.aedev.flow.sync2

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.wrapEpochKey
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.addEntries
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.restore
import io.github.aedev.flow.sync2.roster.Draft
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.RosterState
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.epochDraft
import io.github.aedev.flow.sync2.roster.memberSubject
import io.github.aedev.flow.sync2.roster.replayRoster
import io.github.aedev.flow.sync2.store.Appended
import io.github.aedev.flow.sync2.store.ChangeList
import io.github.aedev.flow.sync2.store.ChangedObject
import io.github.aedev.flow.sync2.store.LocalStore
import io.github.aedev.flow.sync2.store.Remote
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.io.path.createTempDirectory

const val LINK = "default-link"

/** The profile a joining device links. */
const val JOINER = "joining-profile"

fun newMember() = MemberSecrets(SyncCrypto.randomBytes(32), SyncCrypto.randomBytes(32))

/** The server's rules in memory: linear roster appends, immutable objects, one change cursor, a pairing mailbox. */
class MemoryStore :
    ChangeList,
    Remote {
    private val slots = HashMap<String, HashMap<String, ByteArray>>()

    val roster = ArrayList<Entry>()
    val keys = HashMap<Pair<Int, String>, ByteArray>()
    val objects = LinkedHashMap<String, Pair<Long, ByteArray>>()
    private var cursor = 0L

    fun head(): RosterState = replayRoster(roster)

    fun take(path: String): ByteArray = checkNotNull(objects.remove(path)).second

    fun put(
        path: String,
        bytes: ByteArray,
    ) {
        objects[path] = ++cursor to bytes
    }

    override suspend fun rosterAfter(after: Long) = roster.filter { it.seq > after }

    override suspend fun appendRoster(
        head: String,
        entry: Entry,
        wraps: Map<String, String>,
        chain: String?,
    ): Appended {
        val current = head()
        if (current.head != head) return Appended.Conflict(current.head)
        val epoch = entry.epoch?.n ?: current.epoch
        wraps.forEach { (did, wrap) -> keys[epoch to did] = SyncBytes.b64urlDecode(wrap) }
        chain?.let { keys[epoch to "chain"] = SyncBytes.b64urlDecode(it) }
        roster += entry
        return Appended.Done
    }

    override suspend fun key(
        epoch: Int,
        name: String,
    ) = keys[epoch to name]

    override suspend fun putObject(
        path: String,
        bytes: ByteArray,
    ) = put(path, bytes)

    override suspend fun getObject(path: String) = objects[path]?.second

    override suspend fun deleteObject(path: String) {
        objects.remove(path)
    }

    override suspend fun openSlot(slot: String) {
        slots[slot] = HashMap()
    }

    override suspend fun putMessage(
        slot: String,
        name: String,
        bytes: ByteArray,
    ) {
        checkNotNull(slots[slot])[name] = bytes
    }

    override suspend fun getMessage(
        slot: String,
        name: String,
    ) = slots[slot]?.get(name)

    override suspend fun closeSlot(slot: String) {
        slots.remove(slot)
    }

    override suspend fun changes(cursor: Long): Pair<List<ChangedObject>, Long> {
        val fresh = objects.entries.filter { it.value.first > cursor }.sortedBy { it.value.first }
        return fresh.map {
            ChangedObject(
                it.key,
                it.value.second.size
                    .toLong(),
            )
        } to (fresh.lastOrNull()?.value?.first ?: cursor)
    }
}

fun wraps(
    group: ByteArray,
    epoch: Int,
    key: ByteArray,
    members: List<MemberSecrets>,
): Map<String, String> =
    members.associate { SyncBytes.toHex(it.did) to SyncBytes.b64urlEncode(wrapEpochKey(it.kemPublic, group, epoch, it.did, key)) }

fun draft(
    op: Op,
    signer: MemberSecrets,
) = Draft(op, SyncBytes.toHex(signer.did))

/** A group of devices A and B, as A's genesis and A adding B. */
class LinkedGroup(
    private val scope: TestScope,
) {
    val store = MemoryStore()
    val group: ByteArray = SyncCrypto.randomBytes(16)
    val epochKey: ByteArray = SyncCrypto.randomBytes(32)
    val a = newMember()
    val b = newMember()
    val recovery = newMember()

    suspend fun create() {
        val genesis =
            draft(Op.GENESIS, a).copy(
                add =
                    listOf(
                        memberSubject(a.sigPublic, a.kemPublic, Role.DEVICE),
                        memberSubject(recovery.sigPublic, recovery.kemPublic, Role.RECOVERY),
                    ),
                epoch = epochDraft(epochKey, group, 0, listOf(a, recovery).map { SyncBytes.toHex(it.did) }),
            )
        store.roster += buildEntry(group, null, genesis, listOf(a, recovery))
        wraps(group, 0, epochKey, listOf(a, recovery)).forEach { (did, wrap) -> store.keys[0 to did] = SyncBytes.b64urlDecode(wrap) }
        val state = store.head()
        val add =
            buildEntry(
                group,
                state,
                draft(Op.ADD, a).copy(add = listOf(memberSubject(b.sigPublic, b.kemPublic, Role.DEVICE))),
                listOf(a, b),
            )
        check(store.appendRoster(state.head, add, wraps(group, 0, epochKey, listOf(b)), null) == Appended.Done)
    }

    /** A device database that has joined this group with the roster as far as it is now. */
    suspend fun deviceDb(): LinkedDatabase =
        scope.emptyDb().also { db ->
            db.groups().saveGroup(GroupRecord(SyncBytes.toHex(group), "memory", null, LINK))
            db.groups().addEntries(store.roster)
        }
}

val RESTORED = DeviceMeta("app-restored", "Restored", "android")

/** A device restored onto an empty database, as `restoreGroup` leaves it: no link chosen yet. */
suspend fun TestScope.restoredDevice(g: LinkedGroup): Pair<MemberSecrets, LinkedDatabase> {
    val device = newMember()
    val restored = restore(g.store, g.store, g.recovery, device, RESTORED)
    val db = emptyDb()
    db.groups().addEntries(restored.entries)
    db.groups().saveGroup(GroupRecord(SyncBytes.toHex(g.group), "memory", null, link = ""))
    return device to db
}

fun heldStore(db: LinkedDatabase): LocalStore = LocalStore(createTempDirectory("held").toFile(), db.held())

// Queries run on the test's scheduler, so its virtual clock never races real database work.
fun TestScope.emptyDb(): LinkedDatabase =
    Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LinkedDatabase::class.java)
        .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
        .build()
