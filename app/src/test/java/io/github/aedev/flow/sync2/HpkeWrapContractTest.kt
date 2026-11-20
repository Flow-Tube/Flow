package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.WRAP_SIZE
import io.github.aedev.flow.sync2.crypto.tag
import io.github.aedev.flow.sync2.crypto.u32
import io.github.aedev.flow.sync2.crypto.unwrapEpochKey
import io.github.aedev.flow.sync2.crypto.wrapEpochKey
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.conscrypt.Conscrypt
import org.conscrypt.HpkeContextRecipient
import org.conscrypt.HpkeContextSender
import org.conscrypt.XdhKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyFactory

/**
 * What the epoch-key wrap promises whichever HPKE library implements it (spec/crypto.md): RFC 9180
 * output any implementation can open, and a refusal, never a crash, for keys of the wrong size or
 * of small order.
 */
class HpkeWrapContractTest {
    private val group = ByteArray(16) { 7 }
    private val did = ByteArray(16) { 1 }
    private val epoch = 3
    private val recipient = newMember()
    private val cases = Fuzz.cases / 5

    @Test
    fun a_wrap_for_a_public_key_of_the_wrong_size_is_refused() {
        for (size in listOf(0, 1, 31, 33, 64)) {
            assertRefused(ProtocolError.X25519, "public key of $size bytes") {
                wrapEpochKey(ByteArray(size) { 5 }, group, epoch, did, ByteArray(32))
            }
        }
    }

    @Test
    fun a_wrap_for_a_small_order_public_key_is_refused() {
        for (point in SMALL_ORDER_POINTS) {
            assertRefused(ProtocolError.X25519, "public key $point") {
                wrapEpochKey(SyncBytes.fromHex(point), group, epoch, did, ByteArray(32))
            }
        }
    }

    @Test
    fun an_unwrap_with_a_secret_of_the_wrong_size_is_refused() {
        val wrap = wrapEpochKey(recipient.kemPublic, group, epoch, did, ByteArray(32) { 9 })
        for (size in listOf(0, 1, 31, 33, 64)) {
            assertRefused(ProtocolError.DECRYPT, "secret of $size bytes") {
                unwrapEpochKey(ByteArray(size) { 5 }, group, epoch, did, wrap)
            }
        }
    }

    @Test
    fun a_wrap_of_the_wrong_size_is_refused_before_any_crypto() {
        for (size in listOf(0, WRAP_SIZE - 1, WRAP_SIZE + 1)) {
            assertRefused(ProtocolError.LENGTH, "wrap of $size bytes") {
                unwrapEpochKey(recipient.kemSecret, group, epoch, did, ByteArray(size))
            }
        }
    }

    @Test
    fun wraps_open_with_independent_hpke_implementations() =
        forAll(cases, generate = { nextBytes(32) }) { epochKey ->
            val wrap = wrapEpochKey(recipient.kemPublic, group, epoch, did, epochKey)
            assertEquals(WRAP_SIZE, wrap.size)
            assertArrayEquals(epochKey, ConscryptReference.open(recipient.kemSecret, info(), wrap))
            assertArrayEquals(epochKey, BouncyCastleReference.open(recipient.kemSecret, info(), wrap))
        }

    @Test
    fun wraps_sealed_by_independent_hpke_implementations_unwrap() =
        forAll(cases, generate = { nextBytes(32) }) { epochKey ->
            for (wrap in listOf(
                ConscryptReference.seal(recipient.kemPublic, info(), epochKey),
                BouncyCastleReference.seal(recipient.kemPublic, info(), epochKey),
            )) {
                assertArrayEquals(epochKey, unwrapEpochKey(recipient.kemSecret, group, epoch, did, wrap))
            }
        }

    @Test
    fun a_wrap_names_its_group_epoch_and_device() {
        val wrap = wrapEpochKey(recipient.kemPublic, group, epoch, did, ByteArray(32) { 9 })
        assertRefused(ProtocolError.DECRYPT, "other epoch") { unwrapEpochKey(recipient.kemSecret, group, epoch + 1, did, wrap) }
        assertRefused(
            ProtocolError.DECRYPT,
            "other device",
        ) { unwrapEpochKey(recipient.kemSecret, group, epoch, ByteArray(16) { 2 }, wrap) }
        assertRefused(ProtocolError.DECRYPT, "other group") { unwrapEpochKey(recipient.kemSecret, ByteArray(16) { 8 }, epoch, did, wrap) }
        assertRefused(ProtocolError.DECRYPT, "other recipient") { unwrapEpochKey(newMember().kemSecret, group, epoch, did, wrap) }
    }

    private fun info() = tag("wrap") + group + u32(epoch) + did

    private fun assertRefused(
        error: ProtocolError,
        case: String,
        block: () -> Any,
    ) {
        try {
            block()
        } catch (e: ProtocolException) {
            assertEquals(case, error, e.error)
            return
        }
        fail("$case was accepted")
    }

    private object ConscryptReference {
        private const val SUITE = "DHKEM_X25519_HKDF_SHA256/HKDF_SHA256/AES_256_GCM"
        private val provider = Conscrypt.newProvider()

        fun seal(
            kemPublic: ByteArray,
            info: ByteArray,
            plaintext: ByteArray,
        ): ByteArray {
            val key = KeyFactory.getInstance("XDH", provider).generatePublic(XdhKeySpec(kemPublic))
            val sender = HpkeContextSender.getInstance(SUITE, provider).apply { init(key, info) }
            val sealed = sender.seal(plaintext, ByteArray(0))
            return sender.encapsulated + sealed
        }

        fun open(
            kemSecret: ByteArray,
            info: ByteArray,
            wrap: ByteArray,
        ): ByteArray {
            val key = KeyFactory.getInstance("XDH", provider).generatePrivate(XdhKeySpec(kemSecret))
            return HpkeContextRecipient
                .getInstance(SUITE, provider)
                .apply { init(wrap.copyOfRange(0, 32), key, info) }
                .open(wrap.copyOfRange(32, wrap.size), ByteArray(0))
        }
    }

    private object BouncyCastleReference {
        private fun hpke() = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)

        fun seal(
            kemPublic: ByteArray,
            info: ByteArray,
            plaintext: ByteArray,
        ): ByteArray {
            val sender = hpke().setupBaseS(X25519PublicKeyParameters(kemPublic, 0), info)
            return sender.encapsulation + sender.seal(ByteArray(0), plaintext)
        }

        fun open(
            kemSecret: ByteArray,
            info: ByteArray,
            wrap: ByteArray,
        ): ByteArray {
            val secret = X25519PrivateKeyParameters(kemSecret, 0)
            return hpke()
                .setupBaseR(wrap.copyOfRange(0, 32), AsymmetricCipherKeyPair(secret.generatePublicKey(), secret), info)
                .open(ByteArray(0), wrap.copyOfRange(32, wrap.size))
        }
    }

    private companion object {
        /** X25519 public keys whose shared secret is all zeros: small order, or non-canonical encodings of them (RFC 7748 section 6.1). */
        val SMALL_ORDER_POINTS =
            listOf(
                "0000000000000000000000000000000000000000000000000000000000000000",
                "0100000000000000000000000000000000000000000000000000000000000000",
                "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
                "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
                "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
                "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
                "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            )
    }
}
