package io.github.aedev.flow.sync2.store

import io.github.aedev.flow.sync.crypto.SyncBytes

private fun hex(
    value: Long,
    width: Int,
): String = value.toULong().toString(16).padStart(width, '0')

/** Store paths (spec/store.md, Layout). */
object StorePaths {
    fun log(
        did: String,
        seq: Long,
    ): String = "dev/$did/log/${hex(seq ushr 10, 16)}/${hex(seq, 16)}.o"

    fun blob(
        did: String,
        hash: String,
    ): String = "dev/$did/blob/$hash.b"

    fun roster(
        seq: Long,
        hash: String,
    ): String = "roster/${hex(seq, 16)}-${hash.take(16)}.r"

    fun key(
        epoch: Int,
        kc: String,
        name: String,
    ): String = "keys/${epochName(epoch)}/$kc/$name.k"

    fun pair(
        slot: String,
        name: String,
    ): String = "pair/$slot/$name"

    private val BLOB = Regex("dev/[0-9a-f]{32}/blob/[0-9a-f]{64}\\.b")

    fun isBlob(path: String): Boolean = BLOB.matches(path)

    /** A path a device may hold an object or blob under; anything else, such as `..`, is refused. */
    fun isObject(path: String): Boolean = parseLog(path) != null || isBlob(path)

    private val LOG = Regex("dev/([0-9a-f]{32})/log/[0-9a-f]{16}/([0-9a-f]{16})\\.o")

    /** The sender and sequence of a log object path, or `null` for any other path. */
    fun parseLog(path: String): Pair<String, Long>? {
        val (did, seq) = LOG.matchEntire(path)?.destructured ?: return null
        return (did to seq.toULong(16).toLong()).takeIf { log(it.first, it.second) == path }
    }
}

/** The `<kc:16>` of a key path: the first 8 bytes of an epoch's commitment. */
fun kcName(kc: ByteArray): String = SyncBytes.toHex(kc.copyOf(minOf(kc.size, 8)))

/** The name an epoch's wraps and chain box live under. */
fun epochName(epoch: Int): String = hex(epoch.toLong() and 0xffff_ffffL, 8)
