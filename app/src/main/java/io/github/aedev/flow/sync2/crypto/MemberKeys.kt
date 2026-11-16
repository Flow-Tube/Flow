package io.github.aedev.flow.sync2.crypto

/** A member's key pairs: this device's own, or the recovery key while it is in use. */
class MemberSecrets(
    val sigSeed: ByteArray,
    val kemSecret: ByteArray,
) {
    val sigPublic: ByteArray = signingPublicKey(sigSeed)
    val kemPublic: ByteArray = agreementPublicKey(kemSecret)
    val did: ByteArray = memberId(sigPublic, kemPublic)

    fun wipe() {
        sigSeed.fill(0)
        kemSecret.fill(0)
    }
}

/** The id of a group commits to the keys of its genesis (spec/crypto.md, Group id). */
fun groupId(
    device: Pair<ByteArray, ByteArray>,
    recovery: Pair<ByteArray, ByteArray>,
): ByteArray = labeledId("group", device.first + device.second + recovery.first + recovery.second, 16)

fun memberId(
    sigPublic: ByteArray,
    kemPublic: ByteArray,
): ByteArray = labeledId("device", sigPublic + kemPublic, 16)
