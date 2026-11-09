package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.crypto.LanRole
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.Purpose
import io.github.aedev.flow.sync2.crypto.agreementPublicKey
import io.github.aedev.flow.sync2.crypto.checkEpochKey
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.derive
import io.github.aedev.flow.sync2.crypto.epochCommitment
import io.github.aedev.flow.sync2.crypto.isValidSigningKey
import io.github.aedev.flow.sync2.crypto.labeledHash
import io.github.aedev.flow.sync2.crypto.lanFrameAad
import io.github.aedev.flow.sync2.crypto.lanHelloAad
import io.github.aedev.flow.sync2.crypto.lanSessionKeys
import io.github.aedev.flow.sync2.crypto.lanTag
import io.github.aedev.flow.sync2.crypto.lanTranscript
import io.github.aedev.flow.sync2.crypto.open
import io.github.aedev.flow.sync2.crypto.openBlob
import io.github.aedev.flow.sync2.crypto.openChain
import io.github.aedev.flow.sync2.crypto.paddedSize
import io.github.aedev.flow.sync2.crypto.pairingAad
import io.github.aedev.flow.sync2.crypto.pairingCommit
import io.github.aedev.flow.sync2.crypto.pairingKeys
import io.github.aedev.flow.sync2.crypto.pairingProof
import io.github.aedev.flow.sync2.crypto.pairingSlot
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.crypto.recoveryKeys
import io.github.aedev.flow.sync2.crypto.recoverySecret
import io.github.aedev.flow.sync2.crypto.recoveryWords
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.crypto.sealWithSalt
import io.github.aedev.flow.sync2.crypto.unwrapEpochKey
import io.github.aedev.flow.sync2.crypto.verify
import io.github.aedev.flow.sync2.crypto.wrapEpochKeyWithEphemeral
import io.github.aedev.flow.sync2.linking.scannerKey
import io.github.aedev.flow.sync2.roster.checkSender
import io.github.aedev.flow.sync2.roster.parseEntry
import io.github.aedev.flow.sync2.roster.replayRoster
import io.github.aedev.flow.sync2.store.ObjectType
import io.github.aedev.flow.sync2.store.StorePaths
import io.github.aedev.flow.sync2.store.StreamPosition
import io.github.aedev.flow.sync2.store.cutChain
import io.github.aedev.flow.sync2.store.kcName
import io.github.aedev.flow.sync2.store.nextInStream
import io.github.aedev.flow.sync2.store.nextLiveCounter
import io.github.aedev.flow.sync2.store.parseObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FLOW-SYNC/2 shared test vectors, copied unchanged from `Flow-Tube/flow-sync` into
 * `resources/sync2/`. The reference library, the server, Desktop and Android must all pass them.
 */
class VectorsTest {
    private fun load(name: String): JsonElement =
        Json
            .parseToJsonElement(
                checkNotNull(javaClass.getResourceAsStream("/sync2/$name.json")) { "missing vector $name" }
                    .use { it.readBytes().toString(Charsets.UTF_8) },
            )

    private operator fun JsonElement.get(key: String): JsonElement = jsonObject.getValue(key)

    private val JsonElement.text get() = jsonPrimitive.content
    private val JsonElement.bytes get() = SyncBytes.fromHex(text)
    private val JsonElement.items get() = jsonArray

    private fun assertRejected(
        code: String,
        block: () -> Any,
    ) {
        val error =
            try {
                block()
                null
            } catch (e: ProtocolException) {
                e.error.code
            }
        assertEquals(code, error)
    }

    /** RFC 9180 DeriveKeyPair for DHKEM(X25519, HKDF-SHA256): the vectors give `ikmE`, Conscrypt takes the key. */
    private fun deriveKemSecret(ikm: ByteArray): ByteArray {
        val prefix = "HPKE-v1".toByteArray() + "KEM".toByteArray() + byteArrayOf(0, 0x20)
        val prk = SyncCrypto.hkdfExtract(ByteArray(32), prefix + "dkp_prk".toByteArray() + ikm)
        return SyncCrypto.hkdfExpand(prk, byteArrayOf(0, 32) + prefix + "sk".toByteArray(), 32)
    }

    @Test
    fun canonical_json() {
        for (case in load("canonical")["cases"].items) {
            assertEquals(case["canonical"].text, SyncSerialization.canonicalJson(case["input"]))
        }
    }

    @Test
    fun labelled_derivation_and_hash() {
        val v = load("derive")
        for (c in v["cases"].items) {
            val okm = derive(c["ikm"].bytes, c["salt"].bytes, c["label"].text, c["context"].bytes, c["length"].jsonPrimitive.int)
            assertArrayEquals(c["okm"].bytes, okm)
        }
        for (h in v["hashes"].items) {
            assertArrayEquals(h["hash"].bytes, labeledHash(h["label"].text, h["data"].bytes))
        }
    }

    @Test
    fun ed25519_strict_verification() {
        val v = load("ed25519")
        for (c in v["cases"].items) {
            val valid = verify(c["publicKey"].bytes, c["message"].bytes, c["signature"].bytes)
            assertEquals(c["name"].text, c["valid"].jsonPrimitive.boolean, valid)
        }
        for (key in v["weakKeys"].items + v["nonCanonicalKeys"].items + v["mixedOrderKeys"].items) {
            assertFalse(key.text, isValidSigningKey(key.bytes))
        }
    }

    @Test
    fun boxes() {
        val v = load("box")
        for (c in v["cases"].items) {
            assertArrayEquals(c["box"].bytes, sealWithSalt(c["key"].bytes, c["aad"].bytes, c["plaintext"].bytes, c["salt"].bytes))
            assertArrayEquals(c["plaintext"].bytes, open(c["key"].bytes, c["aad"].bytes, c["box"].bytes))
        }
        for (n in v["negatives"].items) {
            assertRejected(n["error"].text) { open(n["key"].bytes, n["aad"].bytes, n["box"].bytes) }
        }
    }

    @Test
    fun payloads() {
        val v = load("payload")
        for (s in v["sizes"].items) {
            assertEquals(s["padded"].jsonPrimitive.int, paddedSize(s["length"].jsonPrimitive.int))
        }
        for (c in v["cases"].items) {
            assertArrayEquals(c["content"].bytes, decodePayload(c["payload"].bytes))
        }
        for (n in v["negatives"].items) {
            assertRejected(n["error"].text) { decodePayload(n["payload"].bytes) }
        }
    }

    @Test
    fun member_ids_epoch_keys_and_wraps() {
        val v = load("keys")
        val paths = v["paths"]
        val kc = kcName(paths["commitment"].bytes)
        val pathEpoch = paths["epoch"].jsonPrimitive.int
        assertEquals(paths["wrap"].text, StorePaths.key(pathEpoch, kc, paths["did"].text))
        assertEquals(paths["chain"].text, StorePaths.key(pathEpoch, kc, "chain"))
        for (m in v["members"].items) {
            val derived = MemberSecrets(m["sigSeed"].bytes, m["kemSecret"].bytes)
            assertArrayEquals(m["sigPublic"].bytes, derived.sigPublic)
            assertArrayEquals(m["kemPublic"].bytes, derived.kemPublic)
            assertArrayEquals(m["did"].bytes, derived.did)
        }
        val e = v["epoch"]
        val (key, group, epoch) = Triple(e["key"].bytes, e["group"].bytes, e["epoch"].jsonPrimitive.int)
        assertArrayEquals(e["commitment"].bytes, epochCommitment(key, group, epoch))
        for (purpose in Purpose.entries) {
            assertArrayEquals(e["purposes"][purpose.label].bytes, purposeKey(key, group, epoch, purpose))
        }

        val w = v["wrap"]
        val (wGroup, wEpoch, did) = Triple(w["group"].bytes, w["epoch"].jsonPrimitive.int, w["did"].bytes)
        val wrapped = wrapEpochKeyWithEphemeral(w["kemPublic"].bytes, wGroup, wEpoch, did, w["key"].bytes, deriveKemSecret(w["ikmE"].bytes))
        assertArrayEquals(w["wrap"].bytes, wrapped)
        val unwrapped = unwrapEpochKey(w["kemSecret"].bytes, wGroup, wEpoch, did, wrapped)
        assertArrayEquals(w["key"].bytes, unwrapped)
        checkEpochKey(unwrapped, wGroup, wEpoch, e["commitment"].bytes)
        assertRejected(ProtocolError.KEY_COMMITMENT.code) { checkEpochKey(unwrapped, wGroup, 2, e["commitment"].bytes) }
        for (n in w["negatives"].items) {
            assertRejected(n["error"].text) {
                unwrapEpochKey(w["kemSecret"].bytes, wGroup, n["epoch"].jsonPrimitive.int, n["did"].bytes, wrapped)
            }
        }

        val c = v["chain"]
        val previous = openChain(c["key"].bytes, c["group"].bytes, c["epoch"].jsonPrimitive.int, c["box"].bytes)
        assertArrayEquals(c["previousKey"].bytes, previous)
    }

    @Test
    fun recovery() {
        val v = load("recovery")
        for (c in v["cases"].items) {
            val secret = c["secret"].bytes
            assertEquals(c["words"].text, recoveryWords(secret))
            assertArrayEquals(secret, recoverySecret(c["words"].text))
            assertArrayEquals(c["did"].bytes, recoveryKeys(secret).did)
        }
        for (words in v["invalidWords"].items) {
            assertNull(recoverySecret(words.text))
        }
    }

    /** The reading order from store.md, as the app implements it. */
    private fun read(
        v: JsonElement,
        objectBytes: ByteArray,
        last: JsonElement?,
    ): ByteArray {
        val group = v["group"].bytes
        val parsed = parseObject(objectBytes, group)
        val sender =
            v["members"].jsonObject[SyncBytes.toHex(parsed.header.sender)] ?: reject(ProtocolError.SENDER)
        parsed.verify(sender.bytes)
        if (parsed.header.type == ObjectType.LIVE) {
            nextLiveCounter(null, parsed)
        } else {
            nextInStream(last?.let { StreamPosition(it["seq"].jsonPrimitive.long, it["hash"].bytes) }, parsed)
        }
        val epoch = parsed.header.epoch
        val key = purposeKey(v["epochKeys"][epoch.toString()].bytes, group, epoch, parsed.header.type.purpose)
        return decodePayload(parsed.open(key))
    }

    @Test
    fun envelopes_open_in_stream_order() {
        val v = load("envelope")
        for (c in v["cases"].items) {
            assertArrayEquals(c["content"].bytes, read(v, c["object"].bytes, c.jsonObject["last"]))
        }
        for (n in v["negatives"].items) {
            assertRejected(n["error"].text) { read(v, n["object"].bytes, n.jsonObject["last"]) }
        }
    }

    @Test
    fun snapshot_blobs() {
        val v = load("envelope")
        val b = v["blob"]
        val group = v["group"].bytes
        val key = purposeKey(v["epochKeys"]["0"].bytes, group, 0, Purpose.SNAPSHOT)
        val openWith = { blob: JsonElement -> openBlob(key, group, 0, b["sender"].bytes, blob.bytes, b["hash"].bytes) }
        assertArrayEquals(b["content"].bytes, decodePayload(openWith(b["blob"])))
        for (n in b["negatives"].items) {
            assertRejected(n["error"].text) { openWith(n["blob"]) }
        }
    }

    @Test
    fun pairing() {
        val v = load("pairing")
        val token = v["token"].bytes
        val (shower, scanner) = v["showerPublic"].bytes to v["scannerPublic"].bytes
        val a = pairingKeys(v["showerSecret"].bytes, scanner, token, shower, scanner)
        val b = pairingKeys(v["scannerSecret"].bytes, shower, token, shower, scanner)
        assertArrayEquals(v["showerKey"].bytes, a.showerKey)
        assertArrayEquals(v["scannerKey"].bytes, a.scannerKey)
        assertEquals(v["sas"].text, a.sas)
        assertArrayEquals(a.showerKey, b.showerKey)
        assertArrayEquals(a.scannerKey, b.scannerKey)
        assertEquals(a.sas, b.sas)
        assertArrayEquals(shower, agreementPublicKey(v["showerSecret"].bytes))
        assertArrayEquals(v["slot"].bytes, pairingSlot(token))
        assertArrayEquals(v["commit"].bytes, pairingCommit(shower))
        assertArrayEquals(v["proof"].bytes, pairingProof(token, scanner))
        assertArrayEquals(v["scannerFirst"].bytes, scanner + v["proof"].bytes)
        assertArrayEquals(scanner, scannerKey(v["scannerFirst"].bytes, token))
        assertFalse(v["commit"].bytes.contentEquals(pairingCommit(v["refused"]["showerPublic"].bytes)))
        assertNull(scannerKey(scanner + v["refused"]["proof"].bytes, token))
        val m = v["message"]
        assertArrayEquals(m["aad"].bytes, pairingAad(token, 0))
        val content = decodePayload(open(a.scannerKey, m["aad"].bytes, m["box"].bytes))
        assertEquals(m["content"].text, content.toString(Charsets.UTF_8))
    }

    @Test
    fun lan() {
        val v = load("lan")
        val key = v["lanKey"].bytes
        val ids = listOf("clientNonce", "serverNonce", "clientDid", "serverDid").map { v[it].bytes }
        assertArrayEquals(v["tag"]["tag"].bytes, lanTag(key, v["tag"]["unixSeconds"].jsonPrimitive.long))
        v["helloAad"].items.forEachIndexed { i, aad -> assertArrayEquals(aad.bytes, lanHelloAad(i + 1)) }
        assertArrayEquals(v["serverTranscript"].bytes, lanTranscript(LanRole.SERVER, ids[0], ids[1], ids[2], ids[3]))
        assertArrayEquals(v["clientTranscript"].bytes, lanTranscript(LanRole.CLIENT, ids[0], ids[1], ids[2], ids[3]))
        val session = lanSessionKeys(key, ids[0], ids[1], ids[2], ids[3])
        assertArrayEquals(v["clientToServer"].bytes, session.clientToServer)
        assertArrayEquals(v["serverToClient"].bytes, session.serverToClient)
        assertArrayEquals(v["frameAad"].bytes, lanFrameAad(5))
    }

    @Test
    fun who_may_write_under_which_epoch() {
        val v = load("senders")
        val state = replayRoster(v["entries"].items.map(::parseEntry))
        for (case in v["senders"].items) {
            val checked =
                runCatching {
                    checkSender(state.senders, case["did"].text, case["epoch"].jsonPrimitive.int, case["seq"].jsonPrimitive.long)
                }
            val error = case.jsonObject["error"]
            if (error == null) {
                assertTrue(case["name"].text, checked.isSuccess)
            } else {
                assertEquals(case["name"].text, error.text, (checked.exceptionOrNull() as ProtocolException).error.code)
            }
        }
    }

    @Test
    fun the_walk_down_from_a_cut() =
        runTest {
            val v = load("senders")
            val group = v["group"].bytes
            for (walk in v["walks"].items) {
                val log = walk["log"].items.map { it.text }
                val last = walk.jsonObject["last"]
                val walked =
                    runCatching {
                        cutChain(
                            walk["cut"]["seq"].jsonPrimitive.long,
                            walk["cut"]["hash"].bytes,
                            last?.get("seq")?.jsonPrimitive?.long ?: 0,
                            last?.get("hash")?.bytes ?: ByteArray(32),
                            group,
                        ) { seq -> log.getOrNull((seq - 1).toInt())?.takeIf { it.isNotEmpty() }?.let(SyncBytes::fromHex) }
                    }
                val error = walk.jsonObject["error"]
                if (error == null) {
                    val hashes =
                        walked
                            .getOrThrow()
                            .toSortedMap()
                            .values
                            .map(SyncBytes::toHex)
                    assertEquals(walk["name"].text, walk["hashes"].items.map { it.text }, hashes)
                } else {
                    assertEquals(walk["name"].text, error.text, (walked.exceptionOrNull() as ProtocolException).error.code)
                }
            }
        }
}
