package io.github.aedev.flow.sync2.crypto

import java.security.MessageDigest

enum class Purpose(
    val label: String,
) {
    ROSTER("roster"),
    CHANGES("changes"),
    SNAPSHOT("snapshot"),
    ACK("ack"),
    LIVE("live"),
    LAN("lan"),
    CHAIN("chain"),
    STORE("store"),
}

fun epochCommitment(
    epochKey: ByteArray,
    group: ByteArray,
    epoch: Int,
): ByteArray = derive(epochKey, group, "epoch", u32(epoch), 32)

fun purposeKey(
    epochKey: ByteArray,
    group: ByteArray,
    epoch: Int,
    purpose: Purpose,
): ByteArray = derive(epochKey, group, "key ${purpose.label}", u32(epoch), 32)

fun checkEpochKey(
    epochKey: ByteArray,
    group: ByteArray,
    epoch: Int,
    commitment: ByteArray,
) {
    if (!MessageDigest.isEqual(epochCommitment(epochKey, group, epoch), commitment)) reject(ProtocolError.KEY_COMMITMENT)
}

private fun chainAad(
    group: ByteArray,
    epoch: Int,
): ByteArray = tag("chain") + group + u32(epoch)

/** Seals K(e-1) under epoch e so members can read older objects. */
fun sealChain(
    epochKey: ByteArray,
    group: ByteArray,
    epoch: Int,
    previousKey: ByteArray,
): ByteArray = seal(purposeKey(epochKey, group, epoch, Purpose.CHAIN), chainAad(group, epoch), previousKey)

fun openChain(
    epochKey: ByteArray,
    group: ByteArray,
    epoch: Int,
    sealed: ByteArray,
): ByteArray = open(purposeKey(epochKey, group, epoch, Purpose.CHAIN), chainAad(group, epoch), sealed)
