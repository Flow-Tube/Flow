package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync.crypto.SyncBytes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.fail
import kotlin.random.Random

/**
 * Property tests without a library. `-Dflow.fuzz.cases=N` raises the cases per property for a longer
 * local run, and `-Dflow.fuzz.seed=S` replays the seed a failure names.
 */
object Fuzz {
    val cases: Int = System.getProperty("flow.fuzz.cases")?.toIntOrNull() ?: 100
    val seed: Long = System.getProperty("flow.fuzz.seed")?.toLongOrNull() ?: Random.nextLong()
}

fun describe(input: Any?): String =
    when (input) {
        is ByteArray -> "hex:" + SyncBytes.toHex(input)
        is Pair<*, *> -> "(${describe(input.first)}, ${describe(input.second)})"
        else -> input.toString()
    }.take(4096)

/** Checks [cases] inputs drawn from [generate]; a failure names the seed, the case and the input. */
inline fun <T> forAll(
    cases: Int = Fuzz.cases,
    generate: Random.() -> T,
    check: (T) -> Unit,
) {
    val random = Random(Fuzz.seed)
    repeat(cases) { case ->
        val input = random.generate()
        try {
            check(input)
        } catch (e: Throwable) {
            throw AssertionError("seed ${Fuzz.seed}, case $case, input ${describe(input)}", e)
        }
    }
}

/** Remote input may be refused, but only with [E]: any other exception would end a sync or a session. */
inline fun <reified E : Throwable> refusedCleanly(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        if (e !is E) throw e
    }
}

inline fun <reified E : Throwable> refused(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        if (e is E) return
        throw e
    }
    fail("accepted; expected ${E::class.simpleName}")
}

/** The largest `seq` the protocol allows (spec/store.md). */
const val MAX_PROTOCOL_SEQ = (1L shl 53) - 1

/** A `seq` the protocol allows, edges included. */
fun Random.seq(): Long = listOf(0L, 1L, MAX_PROTOCOL_SEQ, nextLong(10), nextLong(0, MAX_PROTOCOL_SEQ + 1)).random(this)

fun Random.bytes(
    max: Int,
    min: Int = 0,
): ByteArray = nextBytes(nextInt(min, max + 1))

private val alphabets =
    listOf(
        ('a'..'z').map { it.code },
        (0x20..0x7e).toList(),
        listOf(0x0, 0xa, 0x22, 0x5c, 0x7f, 0xe9, 0x627, 0x4e2d, 0xfeff, 0xfffd),
        listOf(0x1f600, 0x1d11e, 0x10ffff, 0xd800, 0xdfff),
    )

/** Text up to [max] code points, from ASCII to non-BMP characters and lone surrogates. */
fun Random.text(max: Int = 16): String {
    val alphabet = alphabets.random(this)
    return buildString { repeat(nextInt(max + 1)) { appendCodePoint(alphabet.random(this@text)) } }
}

fun Random.json(depth: Int = 3): JsonElement =
    when (nextInt(if (depth > 0) 8 else 6)) {
        0 -> JsonNull
        1 -> JsonPrimitive(nextBoolean())
        2 -> JsonPrimitive(listOf(0L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, nextLong()).random(this))
        3 -> JsonPrimitive(nextDouble(-1e9, 1e9))
        4, 5 -> JsonPrimitive(text())
        6 -> JsonArray(List(nextInt(4)) { json(depth - 1) })
        else -> JsonObject(List(nextInt(4)) { text(6) to json(depth - 1) }.toMap())
    }

/** Text, JSON, or an object with some of [fields], `type` one of [types]: input that gets past the first checks. */
fun Random.shaped(
    fields: List<String>,
    types: List<String>,
): String {
    if (nextBoolean()) return if (nextBoolean()) text(64) else json().toString()
    val type = JsonPrimitive(types.random(this))
    return JsonObject(fields.filter { nextBoolean() }.associateWith { if (it == "type") type else json() }).toString()
}

private fun flip(
    bytes: ByteArray,
    at: Int,
    bit: Int,
) {
    bytes[at] = (bytes[at].toInt() xor (1 shl bit)).toByte()
}

/** A one-bit flip, a truncation or an extension of [valid]. */
fun Random.mutation(valid: ByteArray): ByteArray =
    when (nextInt(3)) {
        0 -> valid.copyOf().also { flip(it, nextInt(valid.size), nextInt(8)) }
        1 -> valid.copyOf(nextInt(valid.size))
        else -> valid + bytes(64, min = 1)
    }
