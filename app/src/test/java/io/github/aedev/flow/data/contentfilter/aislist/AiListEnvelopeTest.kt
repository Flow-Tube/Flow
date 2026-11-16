package io.github.aedev.flow.data.contentfilter.aislist

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.util.Base64

/**
 * `node-envelope.json` was signed by the pipeline's own `signEnvelope` (Flow-Tube/AIlist
 * `src/sign.js`) with a throwaway key, so these tests check this verifier against the real signer.
 */
class AiListEnvelopeTest {
    private val fixture =
        Json.parseToJsonElement(javaClass.getResource("/aislist/node-envelope.json")!!.readText()).jsonObject
    private val envelope = fixture.getValue("envelope").jsonObject
    private val keys = mapOf("test-node" to Base64.getDecoder().decode(fixture.getValue("public_key").jsonPrimitive.content))

    private fun open(
        changed: JsonObject = envelope,
        trusted: Map<String, ByteArray> = keys,
    ) = AiListEnvelope.open(changed.toString().encodeToByteArray(), trusted::get)

    private fun JsonObject.with(
        key: String,
        value: String,
    ) = JsonObject(this + (key to JsonPrimitive(value)))

    @Test
    fun `an envelope signed by the pipeline verifies and yields its exact payload`() {
        val opened = open()

        assertThat(opened).isInstanceOf(AiListEnvelope.Opened.Verified::class.java)
        val payload = (opened as AiListEnvelope.Opened.Verified).payload.decodeToString()
        assertThat(payload).contains("\"list\":\"aislist\"")
        assertThat(payload).contains("non-ASCII é あ")
    }

    @Test
    fun `one changed payload byte fails the check`() {
        val bytes = Base64.getDecoder().decode(envelope.getValue("payload").jsonPrimitive.content)
        bytes[10] = (bytes[10].toInt() xor 1).toByte()

        val opened = open(envelope.with("payload", Base64.getEncoder().encodeToString(bytes)))

        assertThat(opened).isInstanceOf(AiListEnvelope.Opened.Refused::class.java)
    }

    @Test
    fun `one changed signature byte fails the check`() {
        val bytes = Base64.getDecoder().decode(envelope.getValue("sig").jsonPrimitive.content)
        bytes[40] = (bytes[40].toInt() xor 1).toByte()

        assertThat(open(envelope.with("sig", Base64.getEncoder().encodeToString(bytes))))
            .isInstanceOf(AiListEnvelope.Opened.Refused::class.java)
    }

    @Test
    fun `an unknown key id is refused without trying any other key`() {
        assertThat(open(trusted = mapOf("other" to keys.getValue("test-node"))))
            .isEqualTo(AiListEnvelope.Opened.Refused("unknown key_id test-node"))
    }

    @Test
    fun `base64 without its padding is refused, as the publisher never writes it`() {
        val unpadded =
            envelope
                .getValue("sig")
                .jsonPrimitive.content
                .trimEnd('=')

        assertThat(open(envelope.with("sig", unpadded))).isInstanceOf(AiListEnvelope.Opened.Refused::class.java)
    }

    @Test
    fun `the production key decodes to a 32-byte Ed25519 key`() {
        assertThat(AiListKeys.trusted.getValue("2026-11")).hasLength(32)
    }
}
