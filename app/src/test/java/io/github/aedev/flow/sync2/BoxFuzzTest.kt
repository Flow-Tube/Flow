package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.checkEpochKey
import io.github.aedev.flow.sync2.crypto.epochCommitment
import io.github.aedev.flow.sync2.crypto.open
import io.github.aedev.flow.sync2.crypto.openBlob
import io.github.aedev.flow.sync2.crypto.openChain
import io.github.aedev.flow.sync2.crypto.seal
import io.github.aedev.flow.sync2.crypto.sealBlob
import io.github.aedev.flow.sync2.crypto.sealChain
import io.github.aedev.flow.sync2.crypto.sha256
import io.github.aedev.flow.sync2.crypto.unwrapEpochKey
import io.github.aedev.flow.sync2.crypto.wrapEpochKey
import io.github.aedev.flow.sync2.roster.openDeviceMeta
import io.github.aedev.flow.sync2.roster.sealDeviceMeta
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The boxes and wraps that keys, snapshots and device names travel in (spec/crypto.md). */
class BoxFuzzTest {
    private val group = ByteArray(16) { 7 }
    private val key = ByteArray(32) { 9 }
    private val aad = "aad".toByteArray()
    private val did = ByteArray(16) { 1 }
    private val recipient = newMember()

    /** HPKE runs slower than the other boxes. */
    private val wrapCases = Fuzz.cases / 3

    @Test
    fun boxes_round_trip() =
        forAll(generate = { Triple(bytes(64), bytes(64), bytes(512)) }) { (key, aad, plaintext) ->
            assertArrayEquals(plaintext, open(key, aad, seal(key, aad, plaintext)))
        }

    @Test
    fun any_change_to_a_box_is_refused() {
        val box = seal(key, aad, ByteArray(100) { 1 })
        forAll(generate = { mutation(box) }) { refused<ProtocolException> { open(key, aad, it) } }
    }

    @Test
    fun arbitrary_keys_aad_and_boxes_are_refused() =
        forAll(generate = { Triple(bytes(64), bytes(64), bytes(512)) }) { (key, aad, box) ->
            refused<ProtocolException> { open(key, aad, box) }
        }

    @Test
    fun wraps_round_trip() =
        forAll(wrapCases, generate = { Triple(bytes(32), nextInt(), nextBytes(32)) }) { (group, epoch, epochKey) ->
            val wrap = wrapEpochKey(recipient.kemPublic, group, epoch, recipient.did, epochKey)
            assertArrayEquals(epochKey, unwrapEpochKey(recipient.kemSecret, group, epoch, recipient.did, wrap))
        }

    @Test
    fun any_change_to_a_wrap_is_refused() {
        val wrap = wrapEpochKey(recipient.kemPublic, group, 3, recipient.did, key)
        forAll(wrapCases, generate = { mutation(wrap) }) {
            refused<ProtocolException> { unwrapEpochKey(recipient.kemSecret, group, 3, recipient.did, it) }
        }
    }

    /** A wrap names its recipient's public key, which another member wrote into the roster; all zeros has small order. */
    @Test
    fun arbitrary_wraps_and_keys_are_refused() =
        forAll(wrapCases, generate = { Triple(bytes(64), if (nextBoolean()) ByteArray(32) else bytes(64), bytes(90, min = 70)) }) {
            val (secret, public, wrap) = it
            refused<ProtocolException> { unwrapEpochKey(secret, group, 3, did, wrap) }
            refusedCleanly<ProtocolException> { wrapEpochKey(public, group, 3, did, key) }
        }

    @Test
    fun chain_boxes_round_trip() =
        forAll(generate = { nextInt() to bytes(64) }) { (epoch, previous) ->
            assertArrayEquals(previous, openChain(key, group, epoch, sealChain(key, group, epoch, previous)))
        }

    @Test
    fun any_change_to_a_chain_box_is_refused() {
        val chain = sealChain(key, group, 3, ByteArray(32) { 1 })
        forAll(generate = { mutation(chain) }) { refused<ProtocolException> { openChain(key, group, 3, it) } }
    }

    @Test
    fun a_wrong_epoch_key_never_matches_the_commitment() {
        val commitment = epochCommitment(key, group, 2)
        forAll(generate = { nextBytes(32) }) { refused<ProtocolException> { checkEpochKey(it, group, 2, commitment) } }
    }

    @Test
    fun blobs_round_trip() =
        forAll(generate = { Triple(nextInt(), bytes(16), bytes(512)) }) { (epoch, did, payload) ->
            val blob = sealBlob(key, group, epoch, did, payload)
            assertArrayEquals(payload, openBlob(key, group, epoch, did, blob, sha256(blob)))
        }

    @Test
    fun any_change_to_a_blob_is_refused_even_with_a_matching_name() {
        val blob = sealBlob(key, group, 3, did, ByteArray(300) { 2 })
        forAll(generate = { mutation(blob) }) { changed ->
            refused<ProtocolException> { openBlob(key, group, 3, did, changed, sha256(blob)) }
            refused<ProtocolException> { openBlob(key, group, 3, did, changed, sha256(changed)) }
        }
    }

    @Test
    fun device_names_round_trip() =
        forAll(generate = { Triple(nextInt(), nextLong(), text(64).toByteArray()) }) { (epoch, seq, content) ->
            val sealed = sealDeviceMeta(key, group, epoch, seq, did, content)
            assertEquals(content.decodeToString(), openDeviceMeta(key, group, epoch, seq, did, sealed))
        }

    @Test
    fun any_change_to_a_device_name_is_refused() {
        val sealed = sealDeviceMeta(key, group, 0, 1, did, "{}".toByteArray())
        forAll(generate = { mutation(sealed) }) { refused<ProtocolException> { openDeviceMeta(key, group, 0, 1, did, it) } }
    }
}
