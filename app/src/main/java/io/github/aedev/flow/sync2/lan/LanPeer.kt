package io.github.aedev.flow.sync2.lan

import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.roster.entryJson
import io.github.aedev.flow.sync2.roster.parseEntry
import io.github.aedev.flow.sync2.store.LocalStore
import io.github.aedev.flow.sync2.store.ObjectCheck
import io.github.aedev.flow.sync2.store.StorePaths
import io.github.aedev.flow.sync2.store.checkSeq
import io.github.aedev.flow.sync2.store.keyNames
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val ANSWER_TIMEOUT_MS = 30_000L
private const val NEW_LINE = '\n'.code.toByte()

/** One frame of a LAN session: a canonical JSON header line, then the raw bytes the operation carries. */
private class Message(
    val header: JsonObject,
    val bytes: ByteArray?,
)

private fun encode(
    header: Map<String, JsonElement>,
    bytes: ByteArray?,
): ByteArray {
    val line = SyncSerialization.canonicalJson(JsonObject(header)).toByteArray()
    return if (bytes == null) line else line + NEW_LINE + bytes
}

private fun decode(content: ByteArray): Message? {
    val split = content.indexOf(NEW_LINE)
    val line = if (split < 0) content else content.copyOfRange(0, split)
    val header = runCatching { Json.parseToJsonElement(line.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return null
    return Message(header, if (split < 0) null else content.copyOfRange(split + 1, content.size))
}

/** [name]'s value, null when absent; a member that sent another type broke the protocol. */
private inline fun <reified T : JsonElement> JsonObject.field(name: String): T? =
    when (val value = this[name]) {
        null -> null
        is T -> value
        else -> reject(ProtocolError.PAYLOAD)
    }

private fun JsonObject.text(name: String): String? = field<JsonPrimitive>(name)?.contentOrNull

/**
 * Both sides of a LAN session's requests (spec/live.md, Requests): this device answers from what it
 * holds, and asks the other member for what it lacks.
 */
class LanPeer(
    private val link: LanLink,
    private val session: Session,
    private val local: LocalStore,
    private val onLive: suspend (ByteArray) -> Unit = {},
) {
    private val ids = AtomicLong()
    private val answers = ConcurrentHashMap<Long, CompletableDeferred<Message>>()
    private val lastFrame = AtomicLong(System.nanoTime())

    /** Reads frames until the other side closes: answers its requests and hands answers to ours. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            try {
                while (true) {
                    val content = link.receive() ?: break
                    lastFrame.set(System.nanoTime())
                    val message = decode(content) ?: continue
                    val re = message.header.field<JsonPrimitive>("re")?.longOrNull
                    if (re != null) answers.remove(re)?.complete(message) else answer(message)
                }
            } finally {
                answers.values.forEach { it.cancel() }
            }
        }

    private suspend fun request(
        op: String,
        fields: Map<String, JsonElement> = emptyMap(),
    ): Message? {
        val id = ids.getAndIncrement()
        val answer = CompletableDeferred<Message>().also { answers[id] = it }
        link.send(encode(fields + mapOf("id" to JsonPrimitive(id), "op" to JsonPrimitive(op)), null))
        val message = withTimeout(ANSWER_TIMEOUT_MS) { answer.await() }
        return message.takeIf { it.header.field<JsonPrimitive>("ok")?.booleanOrNull == true }
    }

    /** Sends a live envelope; it has no answer (spec/live.md, Requests). */
    suspend fun sendLive(envelope: ByteArray) {
        link.send(encode(mapOf("id" to JsonPrimitive(ids.getAndIncrement()), "op" to JsonPrimitive("live")), envelope))
    }

    private suspend fun answer(request: Message) {
        val id = request.header["id"] ?: return
        val op = request.header.text("op") ?: return

        fun ok(
            fields: Map<String, JsonElement> = emptyMap(),
            bytes: ByteArray? = null,
        ) = encode(fields + mapOf("ok" to JsonPrimitive(true), "re" to id), bytes)

        fun error(code: String) = encode(mapOf("error" to JsonPrimitive(code), "ok" to JsonPrimitive(false), "re" to id), null)
        val header = request.header
        val reply =
            runCatching {
                when (op) {
                    "roster" -> {
                        val after = header["after"]?.jsonPrimitive?.long ?: -1
                        ok(mapOf("entries" to JsonArray(local.entries().filter { it.seq > after }.map(::entryJson))))
                    }

                    "key" -> {
                        val name = header.text("name").orEmpty()
                        val kc = header.text("kc").orEmpty()
                        val key =
                            if (kc.matches(KC) && (name == "chain" || name.matches(DID))) {
                                local.key(header.getValue("epoch").jsonPrimitive.int, kc, name)
                            } else {
                                null
                            }
                        key?.let { ok(bytes = it) } ?: error("not_found")
                    }

                    "list" -> {
                        val vv = header["vv"]?.jsonObject?.mapValues { (_, seq) -> checkSeq(seq.jsonPrimitive.long) }.orEmpty()
                        val (paths, blobs) = local.list(vv)
                        ok(mapOf("blobs" to JsonArray(blobs.map(::JsonPrimitive)), "paths" to JsonArray(paths.map(::JsonPrimitive))))
                    }

                    "get" -> {
                        local.getObject(header.text("path").orEmpty())?.let { ok(bytes = it) } ?: error("not_found")
                    }

                    "put" -> {
                        if (put(header.text("path").orEmpty(), request.bytes)) ok() else error("invalid")
                    }

                    "live" -> {
                        request.bytes?.let { onLive(it) }
                        null
                    }

                    else -> {
                        error("invalid")
                    }
                }
            }.getOrElse { error("invalid") }
        reply?.let { link.send(it) }
    }

    /** Accepts an object only at the path it belongs at, once steps 1 to 3 of reading an envelope pass. */
    private suspend fun put(
        path: String,
        bytes: ByteArray?,
    ): Boolean {
        val (did, seq) = StorePaths.parseLog(path) ?: return false
        if (bytes == null) return false
        return try {
            session.verified(bytes, did, seq)
            local.putObject(path, bytes)
            true
        } catch (_: ProtocolException) {
            false
        }
    }

    /**
     * Pulls what the other member holds and this device doesn't (spec/live.md, LAN groups): roster
     * entries after its head, the wraps and chain boxes it lacks, then the objects above its version vector.
     */
    suspend fun pull() {
        // From the start: an entry that lost can sit below this device's head, and its key still reads.
        request("roster", mapOf("after" to JsonPrimitive(-1)))?.let { answer ->
            local.addEntries(
                answer.header
                    .field<JsonArray>("entries")
                    .orEmpty()
                    .mapNotNull { runCatching { parseEntry(it) }.getOrNull() },
            )
        }
        val resolved = local.resolved()
        val check = ObjectCheck(session.me, resolved.chain)
        for ((epoch, kc, name) in keyNames(resolved.chain, resolved.standing, session.me)) {
            if (local.key(epoch, kc, name) != null) continue
            request(
                "key",
                mapOf("epoch" to JsonPrimitive(epoch), "kc" to JsonPrimitive(kc), "name" to JsonPrimitive(name)),
            )?.bytes?.let { local.putKey(epoch, kc, name, it) }
        }
        val vv = JsonObject(heldVersions().mapValues { (_, seq) -> JsonPrimitive(seq) })
        val listed = request("list", mapOf("vv" to vv)) ?: return
        val wanted =
            (listed.header.field<JsonArray>("paths").orEmpty() + listed.header.field<JsonArray>("blobs").orEmpty()).map {
                (it as? JsonPrimitive)?.contentOrNull ?: reject(ProtocolError.PAYLOAD)
            }
        for (path in wanted.filter { StorePaths.isObject(it) && !check.isOwn(it) }) {
            if (local.getObject(path) != null) continue
            request(
                "get",
                mapOf("path" to JsonPrimitive(path)),
            )?.bytes?.takeIf { check.admits(path, it) }?.let { local.putObject(path, it) }
        }
    }

    /** Waits until the other member has sent nothing for [quietMs]: its own pull is over. */
    suspend fun awaitQuiet(quietMs: Long) {
        while (true) {
            val quiet = (System.nanoTime() - lastFrame.get()) / 1_000_000
            if (quiet >= quietMs) return
            delay(quietMs - quiet)
        }
    }

    /** For each log, the last `seq` held without a gap after the first one held. */
    private suspend fun heldVersions(): Map<String, Long> =
        local
            .list(emptyMap())
            .first
            .mapNotNull(StorePaths::parseLog)
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, seqs) ->
                val sorted = seqs.sorted()
                sorted.zipWithNext().firstOrNull { (a, b) -> b != a + 1 }?.first ?: sorted.last()
            }

    private companion object {
        val DID = Regex("[0-9a-f]{32}")
        val KC = Regex("[0-9a-f]{16}")
    }
}
