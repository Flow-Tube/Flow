package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.changes.batchContent
import io.github.aedev.flow.sync2.changes.parseBatch
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.engine.Manifest
import io.github.aedev.flow.sync2.engine.Part
import io.github.aedev.flow.sync2.engine.Tip
import io.github.aedev.flow.sync2.engine.parseAck
import io.github.aedev.flow.sync2.engine.parseManifest
import io.github.aedev.flow.sync2.live.LiveMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** What sealed objects carry once opened: change batches, snapshot manifests, acks and live messages. */
class ContentFuzzTest {
    /** [text] as it reads back from UTF-8, where a lone surrogate turns into `?`. */
    private fun Random.utf8(max: Int = 16) = text(max).toByteArray().decodeToString()

    private fun Random.manifest() =
        Manifest(
            head = utf8(),
            link = if (nextBoolean()) utf8() else null,
            parts = List(nextInt(3)) { Part(utf8(), utf8(), seq()) },
            vv = List(nextInt(3)) { utf8(4) to Tip(utf8(), seq()) }.toMap(),
        )

    private val fields = listOf("head", "link", "parts", "vv", "type", "video", "queue", "index", "to", "pos", "cmd", "ref", "ok", "can")
    private val types = listOf("presence", "now", "play", "cmd", "result")

    @Test
    fun batches_round_trip() =
        forAll(generate = {
            Triple(utf8(), utf8(), utf8().takeIf { nextBoolean() }) to
                List(nextInt(8)) { JsonObject(mapOf(utf8(6) to JsonPrimitive(utf8()))).toString() }
        }) {
            val (header, records) = it
            val batch = parseBatch(batchContent(header.first, header.second, header.third, records))
            assertEquals(header, Triple(batch.collection, batch.head, batch.link))
            assertEquals(records.filter(String::isNotEmpty), batch.lines)
        }

    @Test
    fun manifests_round_trip() =
        forAll(generate = { manifest() }) { manifest ->
            val content = SyncSerialization.canonicalJson(Json.encodeToJsonElement(Manifest.serializer(), manifest))
            assertEquals(manifest, parseManifest(content.toByteArray()))
        }

    @Test
    fun decrypted_json_is_read_cleanly() =
        forAll(generate = { shaped(fields, types).toByteArray() }) { content ->
            refusedCleanly<ProtocolException> { parseManifest(content) }
            refusedCleanly<ProtocolException> { parseAck(content) }
            // An envelope that opens but doesn't read is dropped (`openLive`), so any parse error will do.
            refusedCleanly<IllegalArgumentException> { LiveMessage.decode(content) }
        }

    @Test
    fun live_messages_round_trip() =
        forAll(generate = {
            when (nextInt(3)) {
                0 -> LiveMessage.Presence(List(nextInt(3)) { utf8() }, utf8())
                1 -> LiveMessage.Cmd(utf8(), utf8(), nextLong().takeIf { nextBoolean() }, nextInt().takeIf { nextBoolean() })
                else -> LiveMessage.Now(utf8().takeIf { nextBoolean() }, utf8(), pos = nextLong(), queue = List(nextInt(4)) { utf8() })
            }
        }) { assertEquals(it, LiveMessage.decode(LiveMessage.encode(it))) }
}
