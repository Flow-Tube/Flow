package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.groupId
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.applyEntry
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.canonicalEntry
import io.github.aedev.flow.sync2.roster.entryJson
import io.github.aedev.flow.sync2.roster.epochDraft
import io.github.aedev.flow.sync2.roster.forkWinner
import io.github.aedev.flow.sync2.roster.memberSubject
import io.github.aedev.flow.sync2.roster.parseEntry
import io.github.aedev.flow.sync2.roster.replayRoster
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Roster entries as a store or a LAN peer hands them over (spec/protocol.md, Roster). */
class RosterFuzzTest {
    private val device = newMember()
    private val recovery = newMember()
    private val group = groupId(device.sigPublic to device.kemPublic, recovery.sigPublic to recovery.kemPublic)
    private val joiner = newMember()
    private val genesis =
        buildEntry(
            group,
            null,
            draft(Op.GENESIS, device).copy(
                add =
                    listOf(
                        memberSubject(device.sigPublic, device.kemPublic, Role.DEVICE),
                        memberSubject(recovery.sigPublic, recovery.kemPublic, Role.RECOVERY),
                    ),
                epoch = epochDraft(ByteArray(32) { 1 }, group, 0, listOf(device, recovery).map { SyncBytes.toHex(it.did) }),
            ),
            listOf(device, recovery),
        )
    private val state = applyEntry(null, genesis)
    private val add =
        buildEntry(
            group,
            state,
            draft(Op.ADD, device).copy(add = listOf(memberSubject(joiner.sigPublic, joiner.kemPublic, Role.DEVICE))),
            listOf(device, joiner),
        )
    private val addJson = entryJson(add).jsonObject

    private fun canonical(value: JsonElement) = SyncSerialization.canonicalJson(value)

    /** Reads changed entry text the way a store answer is read, then applies it after genesis. */
    private fun applyText(text: String) {
        val value = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return
        if (canonical(value) == canonicalEntry(add)) return
        refused<ProtocolException> { applyEntry(state, parseEntry(value)) }
    }

    private fun Random.edited(): JsonObject {
        val field = addJson.keys.random(this)
        return JsonObject(
            when (nextInt(3)) {
                0 -> addJson + (field to json())
                1 -> addJson - field
                else -> addJson + (text(8) to json())
            },
        )
    }

    @Test
    fun canonical_json_parses_back_to_the_same_text() =
        forAll(generate = { json() }) { value ->
            val text = canonical(value)
            assertEquals(text, canonical(Json.parseToJsonElement(text)))
        }

    @Test
    fun any_change_to_the_text_of_a_signed_entry_is_refused() {
        val text = canonicalEntry(add).toByteArray()
        forAll(generate = { mutation(text).decodeToString() }) { applyText(it) }
    }

    @Test
    fun any_changed_removed_or_added_field_of_a_signed_entry_is_refused() = forAll(generate = { edited() }) { applyText(it.toString()) }

    @Test
    fun arbitrary_json_is_refused_or_checked_cleanly() =
        forAll(generate = { if (nextBoolean()) json() else edited() }) { value ->
            refusedCleanly<ProtocolException> {
                val entry = parseEntry(value)
                refusedCleanly<ProtocolException> { applyEntry(state, entry) }
                refusedCleanly<ProtocolException> { replayRoster(listOf(entry)) }
                forkWinner(listOf(entry, add))
            }
        }

    @Test
    fun an_entry_must_take_the_next_seq() =
        forAll(generate = { if (nextBoolean()) nextLong(-1, 3) else nextLong() }) { seq ->
            val entry = add.copy(seq = seq)
            if (seq == 1L) {
                applyEntry(state, entry)
            } else {
                val error = runCatching { applyEntry(state, entry) }.exceptionOrNull()
                assertEquals(ProtocolError.ROSTER_CHAIN, (error as ProtocolException).error)
            }
        }

    @Test
    fun a_rotation_must_start_the_next_epoch() =
        forAll(generate = { if (nextBoolean()) nextInt(-1, 3) else nextInt() }) { n ->
            val rotate = draft(Op.ROTATE, device).copy(epoch = epochDraft(ByteArray(32) { 2 }, group, n, state.members.keys.toList()))
            val applied = runCatching { applyEntry(state, buildEntry(group, state, rotate, listOf(device))) }
            if (n == 1) {
                assertEquals(1, applied.getOrThrow().epoch)
            } else {
                assertTrue(applied.exceptionOrNull().let { it is ProtocolException && it.error == ProtocolError.ROSTER_EPOCH })
            }
        }
}
