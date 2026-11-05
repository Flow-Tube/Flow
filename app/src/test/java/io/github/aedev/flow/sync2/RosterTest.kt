package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.RosterState
import io.github.aedev.flow.sync2.roster.applyEntry
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.roster.forkWinner
import io.github.aedev.flow.sync2.roster.openDeviceMeta
import io.github.aedev.flow.sync2.roster.parseEntry
import io.github.aedev.flow.sync2.roster.replayRoster
import io.github.aedev.flow.sync2.roster.winningChain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The roster vectors from `Flow-Tube/flow-sync`: a full chain, the state after each entry, the device
 * names it carries, every rejection, and fork resolution.
 */
class RosterTest {
    private val v: JsonElement =
        Json.parseToJsonElement(
            checkNotNull(javaClass.getResourceAsStream("/sync2/roster.json")).use { it.readBytes().toString(Charsets.UTF_8) },
        )

    private operator fun JsonElement.get(key: String): JsonElement = jsonObject.getValue(key)

    private val JsonElement.text get() = jsonPrimitive.content

    private val entries: List<Entry> = v["entries"].jsonArray.map(::parseEntry)

    private fun base(count: JsonElement): RosterState = replayRoster(entries.take(count.jsonPrimitive.int))

    private fun errorCode(block: () -> Any): String? =
        try {
            block()
            null
        } catch (e: ProtocolException) {
            e.error.code
        }

    private fun view(state: RosterState): JsonElement =
        buildJsonObject {
            put("seq", state.seq)
            put("head", state.head)
            put("epoch", state.epoch)
            put("rotate", state.rotate)
            put("recoveryDue", state.recoveryDue)
            put(
                "members",
                buildJsonArray {
                    for ((did, member) in state.members) {
                        add(
                            buildJsonObject {
                                put("did", did)
                                put("role", member.role.name.lowercase())
                                put("used", member.used)
                                member.installer?.let { put("installer", it) }
                            },
                        )
                    }
                },
            )
            put(
                "senders",
                buildJsonArray {
                    for ((did, sender) in state.senders.toSortedMap()) {
                        add(
                            buildJsonObject {
                                put("did", did)
                                put("role", sender.role.name.lowercase())
                                put("since", sender.since)
                                sender.until?.let { put("until", it) }
                                sender.cut?.let { cut ->
                                    put(
                                        "cut",
                                        buildJsonObject {
                                            put("seq", cut.seq)
                                            put("hash", cut.hash)
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }

    @Test
    fun every_prefix_of_the_chain_gives_the_expected_state() {
        v["states"].jsonArray.forEachIndexed { i, expected ->
            assertEquals(expected, view(replayRoster(entries.take(i + 1))))
        }
    }

    @Test
    fun device_names_open_with_the_roster_key_of_their_epoch() {
        val group = SyncBytes.fromHex(v["group"].text)
        for (m in v["meta"].jsonArray) {
            val seq = m["seq"].jsonPrimitive.long
            val epoch = m["epoch"].jsonPrimitive.int
            val did = m["did"].text
            val sealed = checkNotNull(entries[seq.toInt()].meta).getValue(did)
            val key = SyncBytes.fromHex(v["epochKeys"].jsonArray[epoch].text)
            val content = openDeviceMeta(key, group, epoch, seq, SyncBytes.fromHex(did), SyncBytes.b64urlDecode(sealed))
            assertEquals(m["content"].text, content)
        }
    }

    @Test
    fun invalid_entries_are_rejected_for_the_named_reason() {
        for (n in v["negatives"].jsonArray) {
            val state = base(n["base"])
            assertEquals(n["name"].text, n["error"].text, errorCode { applyEntry(state, parseEntry(n["entry"])) })
        }
    }

    @Test
    fun a_genesis_must_match_its_group_id() {
        for (n in v["geneses"].jsonArray) {
            val failure = assertThrows(ProtocolException::class.java) { applyEntry(null, parseEntry(n["entry"]!!)) }
            assertEquals(n["error"].text, failure.error.code)
        }
    }

    @Test
    fun forks_resolve_to_the_same_winner() {
        for (f in v["forks"].jsonArray) {
            val state = base(f["base"])
            val candidates = f["candidates"].jsonArray.map(::parseEntry)
            candidates.forEach { applyEntry(state, it) }
            assertEquals(f["winner"].text, entryHash(checkNotNull(forkWinner(candidates))))
            assertEquals(f["winner"].text, entryHash(checkNotNull(forkWinner(candidates.reversed()))))
        }
    }

    @Test
    fun a_null_field_is_not_an_absent_one() {
        val withNull = JsonObject(v["entries"].jsonArray[1].jsonObject + ("remove" to JsonNull))
        assertEquals("roster-format", errorCode { parseEntry(withNull) })
    }

    @Test
    fun a_negative_seq_or_epoch_is_malformed_so_it_never_hides_the_chain() {
        val raw = v["entries"].jsonArray
        val seq = JsonObject(raw[1].jsonObject + ("seq" to JsonPrimitive(-1)))
        val epoch = JsonObject(raw[0].jsonObject + ("epoch" to JsonObject(raw[0]["epoch"].jsonObject + ("n" to JsonPrimitive(-1)))))
        assertEquals("roster-format", errorCode { parseEntry(seq) })
        assertEquals("roster-format", errorCode { parseEntry(epoch) })
        assertEquals(entries, winningChain((raw + seq).mapNotNull { runCatching { parseEntry(it) }.getOrNull() }))
    }

    @Test
    fun a_removal_replaces_a_recovery_key_set_by_a_device_that_is_gone() {
        for (case in v["replacements"].jsonArray) {
            val name = case["name"].text
            val count = case.jsonObject["base"]?.jsonPrimitive?.int ?: entries.size
            val steps = case["steps"].jsonArray.map(::parseEntry)
            val before = steps.dropLast(1).fold(replayRoster(entries.take(count)), ::applyEntry)
            val error = case.jsonObject["error"]
            if (error == null) {
                assertEquals(name, case["state"], view(applyEntry(before, steps.last())))
            } else {
                assertEquals(name, error.text, errorCode { applyEntry(before, steps.last()) })
            }
        }
    }
}
