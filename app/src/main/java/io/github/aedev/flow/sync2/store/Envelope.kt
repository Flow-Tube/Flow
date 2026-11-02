package io.github.aedev.flow.sync2.store

import io.github.aedev.flow.sync2.crypto.BOX_OVERHEAD
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.Purpose
import io.github.aedev.flow.sync2.crypto.open
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.crypto.seal
import io.github.aedev.flow.sync2.crypto.sealWithSalt
import io.github.aedev.flow.sync2.crypto.sha256
import io.github.aedev.flow.sync2.crypto.sign
import io.github.aedev.flow.sync2.crypto.tag
import io.github.aedev.flow.sync2.crypto.u32
import io.github.aedev.flow.sync2.crypto.u64
import io.github.aedev.flow.sync2.crypto.verify
import java.nio.ByteBuffer
import java.security.MessageDigest

private val MAGIC = byteArrayOf('F'.code.toByte(), 'S'.code.toByte(), '2'.code.toByte(), 0)
const val SUITE: Byte = 0x01
const val HEADER_SIZE = 82
private const val SIGNATURE_SIZE = 64
private const val SENDER_AT = 26
private const val SEQ_AT = 42
const val MIN_OBJECT_SIZE = HEADER_SIZE + BOX_OVERHEAD + 256 + SIGNATURE_SIZE
const val MAX_OBJECT_SIZE = 4 * 1024 * 1024

/** The largest integer every implementation reads exactly. */
const val MAX_SEQ = (1L shl 53) - 1

/** [seq], or a rejection when it is above [MAX_SEQ], in a header, a manifest, an ack or a version vector alike. */
fun checkSeq(seq: Long): Long = if (seq in 0..MAX_SEQ) seq else reject(ProtocolError.RANGE)

val ZERO_HASH = ByteArray(32)

enum class ObjectType(
    val byte: Byte,
    val purpose: Purpose,
) {
    CHANGES(0x01, Purpose.CHANGES),
    SNAPSHOT(0x02, Purpose.SNAPSHOT),
    ACK(0x03, Purpose.ACK),
    LIVE(0x10, Purpose.LIVE),
}

class Header(
    val type: ObjectType,
    val group: ByteArray,
    val epoch: Int,
    val sender: ByteArray,
    val seq: Long,
    val prev: ByteArray,
) {
    fun encode(): ByteArray = MAGIC + byteArrayOf(type.byte, SUITE) + group + u32(epoch) + sender + u64(seq) + prev
}

class ParsedObject(
    val header: Header,
    private val bytes: ByteArray,
) {
    val hash: ByteArray = sha256(bytes)
    private val headerBytes get() = bytes.copyOfRange(0, HEADER_SIZE)
    private val sealed get() = bytes.copyOfRange(HEADER_SIZE, bytes.size - SIGNATURE_SIZE)

    fun verify(sigPublic: ByteArray) {
        val signature = bytes.copyOfRange(bytes.size - SIGNATURE_SIZE, bytes.size)
        if (!verify(sigPublic, signedBytes(headerBytes, sealed), signature)) reject(ProtocolError.SIGNATURE)
    }

    fun open(purposeKey: ByteArray): ByteArray = open(purposeKey, headerBytes, sealed)
}

private fun signedBytes(
    header: ByteArray,
    sealed: ByteArray,
): ByteArray = tag("object") + header + sealed

private fun finish(
    header: ByteArray,
    sealed: ByteArray,
    sigSeed: ByteArray,
): ByteArray = header + sealed + sign(sigSeed, signedBytes(header, sealed))

fun sealObject(
    header: Header,
    purposeKey: ByteArray,
    payload: ByteArray,
    sigSeed: ByteArray,
): ByteArray = header.encode().let { finish(it, seal(purposeKey, it, payload), sigSeed) }

/** Only for reproducing test vectors; production objects take a fresh random salt. */
fun sealObjectWithSalt(
    header: Header,
    purposeKey: ByteArray,
    payload: ByteArray,
    sigSeed: ByteArray,
    salt: ByteArray,
): ByteArray = header.encode().let { finish(it, sealWithSalt(purposeKey, it, payload, salt), sigSeed) }

/** Structural checks only; the caller then finds the sender, verifies, and opens the box. */
fun parseObject(
    bytes: ByteArray,
    group: ByteArray,
): ParsedObject {
    if (bytes.size !in MIN_OBJECT_SIZE..MAX_OBJECT_SIZE) reject(ProtocolError.LENGTH)
    if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC)) reject(ProtocolError.MAGIC)
    val type = ObjectType.entries.find { it.byte == bytes[4] }?.takeIf { bytes[5] == SUITE } ?: reject(ProtocolError.SUITE)
    val buffer = ByteBuffer.wrap(bytes)
    val header =
        Header(
            type = type,
            group = bytes.copyOfRange(6, 22),
            epoch = buffer.getInt(22),
            sender = bytes.copyOfRange(SENDER_AT, SEQ_AT),
            seq = buffer.getLong(SEQ_AT),
            prev = bytes.copyOfRange(50, 82),
        )
    if (!header.group.contentEquals(group)) reject(ProtocolError.GROUP)
    checkSeq(header.seq)
    return ParsedObject(header, bytes)
}

/** Whether [bytes] start as [sender]'s log object [seq]; anything else, such as an error page, is no object at all. */
fun namesLogObject(
    bytes: ByteArray,
    sender: ByteArray,
    seq: Long,
): Boolean =
    bytes.size >= HEADER_SIZE &&
        bytes.copyOfRange(SENDER_AT, SEQ_AT).contentEquals(sender) &&
        ByteBuffer.wrap(bytes).getLong(SEQ_AT) == seq

class StreamPosition(
    val seq: Long,
    val hash: ByteArray,
)

/**
 * Advances a sender's log stream; a sender never read before starts before `seq` 1, so a store
 * can't hide the beginning of a log.
 */
fun nextInStream(
    last: StreamPosition?,
    objectRead: ParsedObject,
): StreamPosition {
    val seq = objectRead.header.seq.toULong()
    val lastSeq = (last?.seq ?: 0).toULong()
    if (seq <= lastSeq) reject(ProtocolError.REPLAY)
    if (seq > lastSeq + 1u) reject(ProtocolError.GAP)
    if (!MessageDigest.isEqual(objectRead.header.prev, last?.hash ?: ZERO_HASH)) reject(ProtocolError.PREV)
    return StreamPosition(objectRead.header.seq, objectRead.hash)
}

/** Live messages carry a counter instead of a chain. */
fun nextLiveCounter(
    last: Long?,
    objectRead: ParsedObject,
): Long {
    if (!objectRead.header.prev.contentEquals(ZERO_HASH)) reject(ProtocolError.PREV)
    if (last != null && objectRead.header.seq.toULong() <= last.toULong()) reject(ProtocolError.REPLAY)
    return objectRead.header.seq
}
