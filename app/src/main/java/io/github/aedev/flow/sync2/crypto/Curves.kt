package io.github.aedev.flow.sync2.crypto

import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519

private const val KEY_SIZE = 32
private const val SIGNATURE_SIZE = 64

/** A canonical encoding of a point of order exactly L (spec/crypto.md, Ed25519 checks). */
private fun startsWithPointOfOrderL(bytes: ByteArray): Boolean = Ed25519.validatePublicKeyFull(bytes, 0)

fun isValidSigningKey(publicKey: ByteArray): Boolean = publicKey.size == KEY_SIZE && startsWithPointOfOrderL(publicKey)

fun signingPublicKey(seed: ByteArray): ByteArray = ByteArray(KEY_SIZE).also { Ed25519.generatePublicKey(seed, 0, it, 0) }

fun sign(
    seed: ByteArray,
    message: ByteArray,
): ByteArray = ByteArray(SIGNATURE_SIZE).also { Ed25519.sign(seed, 0, message, 0, message.size, it, 0) }

fun verify(
    publicKey: ByteArray,
    message: ByteArray,
    signature: ByteArray,
): Boolean =
    signature.size == SIGNATURE_SIZE &&
        isValidSigningKey(publicKey) &&
        startsWithPointOfOrderL(signature) &&
        Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.size)

fun agreementPublicKey(secret: ByteArray): ByteArray = ByteArray(KEY_SIZE).also { X25519.scalarMultBase(secret, 0, it, 0) }

/** X25519, refusing the all-zero output of a small-order peer key. */
fun agree(
    secret: ByteArray,
    publicKey: ByteArray,
): ByteArray {
    if (publicKey.size != KEY_SIZE) reject(ProtocolError.X25519)
    val shared = ByteArray(KEY_SIZE)
    if (!X25519.calculateAgreement(secret, 0, publicKey, 0, shared, 0)) reject(ProtocolError.X25519)
    return shared
}
