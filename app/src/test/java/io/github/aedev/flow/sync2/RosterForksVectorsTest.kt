package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.Settlement
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.roster.parseEntry
import io.github.aedev.flow.sync2.roster.settleRoster
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settling the roster (spec/protocol.md, Forks), as the shared vectors say, in any order. */
class RosterForksVectorsTest {
    private val v: JsonObject =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/sync2/forks.json")).readText()).jsonObject
    private val pool: Map<String, Entry> = v.getValue("entries").jsonObject.mapValues { parseEntry(it.value) }
    private val names: Map<String, String> = pool.entries.associate { entryHash(it.value) to it.key }

    private fun named(list: JsonElement) = list.jsonArray.map { pool.getValue(it.jsonPrimitive.content) }

    private fun text(list: JsonElement?) = list?.jsonArray?.map { it.jsonPrimitive.content }

    private fun nameOf(entries: List<Entry>) = entries.map { names.getValue(entryHash(it)) }

    private fun check(
        name: String,
        settled: Settlement,
        expect: JsonObject,
    ) {
        val outcome = expect.getValue("outcome").jsonPrimitive.content
        when (settled) {
            is Settlement.Apply -> {
                assertEquals(name, "apply", outcome)
                assertEquals(name, text(expect["chain"]), nameOf(settled.chain))
                assertEquals(name, text(expect["standing"]), nameOf(settled.standing).sorted())
                assertEquals(name, expect.getValue("extendsApplied").jsonPrimitive.boolean, settled.extendsApplied)
            }

            is Settlement.Contested -> {
                assertEquals(name, "contested", outcome)
                assertEquals(name, text(expect["contested"]), nameOf(settled.contested).sorted())
                assertEquals(name, text(expect["sides"]), nameOf(settled.sides).sorted())
            }

            Settlement.Rollback -> {
                assertEquals(name, "rollback", outcome)
            }

            Settlement.Rewritten -> {
                assertEquals(name, "rewritten", outcome)
            }
        }
    }

    @Test
    fun every_case_settles_as_the_vectors_say_in_any_order() {
        val group = v.getValue("group").jsonPrimitive.content
        for (element in v.getValue("cases") as JsonArray) {
            val case = element.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val applied = named(case.getValue("applied"))
            val view = named(case.getValue("view"))
            val pins = named(case.getValue("pins")).map(::entryHash).toSet()
            val linear = case.getValue("linear").jsonPrimitive.boolean
            val expect = case.getValue("expect").jsonObject
            check(name, settleRoster(group, applied, view, pins, linear), expect)
            check(name, settleRoster(group, applied, view.reversed(), pins, linear), expect)
        }
    }
}
