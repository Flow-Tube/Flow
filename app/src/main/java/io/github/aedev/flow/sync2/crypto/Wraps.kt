package io.github.aedev.flow.sync2.crypto

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

const val WRAP_SIZE = 80
private const val ENC_SIZE = 32
private const val KEY_SIZE = 32

/** RFC 9180 base mode: X25519, HKDF-SHA256, AES-256-GCM. */
private fun hpke() = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)

private fun info(
    group: ByteArray,
    epoch: Int,
    did: ByteArray,
): ByteArray = tag("wrap") + group + u32(epoch) + did

// BouncyCastle's key parameters read the first 32 bytes of a longer array, so the size is checked here.
private fun publicKey(bytes: ByteArray): X25519PublicKeyParameters {
    require(bytes.size == KEY_SIZE)
    return X25519PublicKeyParameters(bytes, 0)
}

private fun keyPair(secret: ByteArray): AsymmetricCipherKeyPair {
    require(secret.size == KEY_SIZE)
    val private = X25519PrivateKeyParameters(secret, 0)
    return AsymmetricCipherKeyPair(private.generatePublicKey(), private)
}

private fun wrap(
    kemPublic: ByteArray,
    epochKey: ByteArray,
    setup: HPKE.(X25519PublicKeyParameters) -> HPKEContextWithEncapsulation,
): ByteArray =
    try {
        val sender = hpke().setup(publicKey(kemPublic))
        sender.encapsulation + sender.seal(ByteArray(0), epochKey)
    } catch (_: IllegalArgumentException) {
        reject(ProtocolError.X25519)
    } catch (_: IllegalStateException) {
        reject(ProtocolError.X25519)
    }

fun wrapEpochKey(
    kemPublic: ByteArray,
    group: ByteArray,
    epoch: Int,
    did: ByteArray,
    epochKey: ByteArray,
): ByteArray = wrap(kemPublic, epochKey) { setupBaseS(it, info(group, epoch, did)) }

/** Uses [ephemeralSecret] as the sender's ephemeral X25519 key, so tests can reproduce a wrap. */
fun wrapEpochKeyWithEphemeral(
    kemPublic: ByteArray,
    group: ByteArray,
    epoch: Int,
    did: ByteArray,
    epochKey: ByteArray,
    ephemeralSecret: ByteArray,
): ByteArray = wrap(kemPublic, epochKey) { setupBaseS(it, info(group, epoch, did), keyPair(ephemeralSecret)) }

fun unwrapEpochKey(
    kemSecret: ByteArray,
    group: ByteArray,
    epoch: Int,
    did: ByteArray,
    wrap: ByteArray,
): ByteArray {
    if (wrap.size != WRAP_SIZE) reject(ProtocolError.LENGTH)
    return try {
        hpke()
            .setupBaseR(wrap.copyOfRange(0, ENC_SIZE), keyPair(kemSecret), info(group, epoch, did))
            .open(ByteArray(0), wrap.copyOfRange(ENC_SIZE, WRAP_SIZE))
    } catch (_: InvalidCipherTextException) {
        reject(ProtocolError.DECRYPT)
    } catch (_: IllegalArgumentException) {
        reject(ProtocolError.DECRYPT)
    } catch (_: IllegalStateException) {
        reject(ProtocolError.DECRYPT)
    }
}
