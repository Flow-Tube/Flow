package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.encodePayload
import io.github.aedev.flow.sync2.crypto.paddedSize
import io.github.aedev.flow.sync2.store.Header
import io.github.aedev.flow.sync2.store.MIN_OBJECT_SIZE
import io.github.aedev.flow.sync2.store.ObjectType
import io.github.aedev.flow.sync2.store.StorePaths
import io.github.aedev.flow.sync2.store.StreamPosition
import io.github.aedev.flow.sync2.store.ZERO_HASH
import io.github.aedev.flow.sync2.store.nextInStream
import io.github.aedev.flow.sync2.store.nextLiveCounter
import io.github.aedev.flow.sync2.store.parseObject
import io.github.aedev.flow.sync2.store.sealObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** Envelopes, payloads and log paths, as a store or the LAN hands them over (spec/store.md). */
class EnvelopeFuzzTest {
    private val group = ByteArray(16) { 7 }
    private val key = ByteArray(32) { 9 }
    private val sender = newMember()

    private fun seal(
        type: ObjectType,
        epoch: Int,
        seq: Long,
        prev: ByteArray,
        content: ByteArray,
    ) = sealObject(Header(type, group, epoch, sender.did, seq, prev), key, encodePayload(content), sender.sigSeed)

    /** Steps 1, 3 and 5 of reading an envelope. */
    private fun read(bytes: ByteArray): Pair<Header, ByteArray> {
        val parsed = parseObject(bytes, group)
        parsed.verify(sender.sigPublic)
        return parsed.header to decodePayload(parsed.open(key))
    }

    private fun Random.seqNear(base: Long): Long = if (nextBoolean()) (base + nextLong(-2, 3)).coerceIn(0, MAX_PROTOCOL_SEQ) else seq()

    /** Past the length, magic, suite and group checks, so the signature is what refuses them. */
    private fun Random.plausible(): ByteArray =
        byteArrayOf('F'.code.toByte(), 'S'.code.toByte(), '2'.code.toByte(), 0, nextInt().toByte(), 1) + group +
            bytes(MIN_OBJECT_SIZE + 256, min = MIN_OBJECT_SIZE - 22)

    @Test
    fun objects_round_trip() =
        forAll(
            generate = { Header(ObjectType.entries.random(this), group, nextInt(), sender.did, seq(), nextBytes(32)) to bytes(512) },
        ) { (written, content) ->
            val (header, opened) = read(seal(written.type, written.epoch, written.seq, written.prev, content))
            assertArrayEquals(written.encode(), header.encode())
            assertArrayEquals(content, opened)
        }

    @Test
    fun any_change_to_a_sealed_object_is_refused() {
        val valid = seal(ObjectType.CHANGES, 0, 1, ZERO_HASH, ByteArray(64) { 1 })
        forAll(generate = { mutation(valid) }) { refused<ProtocolException> { read(it) } }
    }

    @Test
    fun arbitrary_bytes_are_refused() =
        forAll(generate = {
            if (nextBoolean()) bytes(1024) else plausible()
        }) { refused<ProtocolException> { read(it) } }

    @Test
    fun a_log_object_must_follow_the_last_one_read() =
        forAll(generate = {
            val last = if (nextBoolean()) null else StreamPosition(seq(), nextBytes(32))
            Triple(last, seqNear(last?.seq ?: 0), nextBoolean())
        }) { (last, seq, chained) ->
            val lastSeq = (last?.seq ?: 0).toULong()
            val lastHash = last?.hash ?: ZERO_HASH
            val parsed = parseObject(seal(ObjectType.CHANGES, 0, seq, if (chained) lastHash else ByteArray(32) { 3 }, ByteArray(0)), group)
            val expected =
                when {
                    seq.toULong() <= lastSeq -> ProtocolError.REPLAY
                    seq.toULong() != lastSeq + 1u -> ProtocolError.GAP
                    !chained && !lastHash.contentEquals(ByteArray(32) { 3 }) -> ProtocolError.PREV
                    else -> null
                }
            val read =
                try {
                    nextInStream(last, parsed).also { assertArrayEquals(parsed.hash, it.hash) }
                    null
                } catch (e: ProtocolException) {
                    e.error
                }
            assertEquals(expected, read)
        }

    @Test
    fun a_live_counter_only_goes_up() =
        forAll(generate = {
            val last = if (nextBoolean()) null else seq()
            Triple(last, seqNear(last ?: 0), if (nextBoolean()) ZERO_HASH else nextBytes(32))
        }) { (last, seq, prev) ->
            val parsed = parseObject(seal(ObjectType.LIVE, 0, seq, prev, ByteArray(0)), group)
            val expected =
                when {
                    !prev.contentEquals(ZERO_HASH) -> ProtocolError.PREV
                    last != null && seq.toULong() <= last.toULong() -> ProtocolError.REPLAY
                    else -> null
                }
            val read =
                try {
                    assertEquals(seq, nextLiveCounter(last, parsed))
                    null
                } catch (e: ProtocolException) {
                    e.error
                }
            assertEquals(expected, read)
        }

    @Test
    fun payloads_round_trip() =
        forAll(generate = { bytes(4096) }) { content ->
            val payload = encodePayload(content)
            assertEquals(paddedSize(payload.size), payload.size)
            assertArrayEquals(content, decodePayload(payload))
        }

    /** The box authenticates a payload, so a changed one may still read; it is just never read with another error. */
    @Test
    fun changed_or_arbitrary_payloads_are_read_cleanly() {
        val valid = encodePayload(ByteArray(300) { 1 })
        forAll(
            generate = { if (nextBoolean()) mutation(valid) else bytes(1024) },
        ) { refusedCleanly<ProtocolException> { decodePayload(it) } }
    }

    @Test
    fun log_paths_round_trip() =
        forAll(generate = { SyncBytes.toHex(nextBytes(16)) to nextLong() }) { (did, seq) ->
            assertEquals(did to seq, StorePaths.parseLog(StorePaths.log(did, seq)))
        }

    @Test
    fun only_the_path_of_a_log_object_is_read() {
        val valid = StorePaths.log("ab".repeat(16), 1025).toByteArray()
        forAll(generate = { if (nextBoolean()) text(80) else mutation(valid).decodeToString() }) { path ->
            StorePaths.parseLog(path)?.let { (did, seq) -> assertEquals(path, StorePaths.log(did, seq)) }
        }
    }
}
