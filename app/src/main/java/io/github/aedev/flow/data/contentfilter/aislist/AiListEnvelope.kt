package io.github.aedev.flow.data.contentfilter.aislist

import io.github.aedev.flow.sync2.crypto.verify
import java.util.Base64

/**
 * The public keys the list may be signed with, by `key_id`. A new key ships here in an app release
 * before the pipeline signs with it; it is never fetched from the host it protects.
 */
internal object AiListKeys {
    val trusted: Map<String, ByteArray> =
        mapOf("2026-11" to Base64.getDecoder().decode("xak9y4JaDG+UjKfl7cDwiaJs+7FjrOM+urMW1YfSuc0="))
}

/** Verifies a manifest envelope and only then hands back the payload bytes to parse. */
internal object AiListEnvelope {
    private const val SIGNATURE_SIZE = 64

    sealed interface Opened {
        class Verified(
            val payload: ByteArray,
        ) : Opened

        data class Refused(
            val reason: String,
        ) : Opened
    }

    fun open(
        envelopeBytes: ByteArray,
        keyFor: (keyId: String) -> ByteArray?,
    ): Opened {
        val envelope =
            runCatching { aiListJson.decodeFromString(AiListEnvelopeJson.serializer(), envelopeBytes.decodeToString()) }
                .getOrNull() ?: return Opened.Refused("manifest is not an envelope")
        val keyId = envelope.keyId ?: return Opened.Refused("no key_id")
        val key = keyFor(keyId) ?: return Opened.Refused("unknown key_id $keyId")
        val payload = envelope.payload?.decodeCanonicalBase64() ?: return Opened.Refused("payload is not standard base64")
        val signature = envelope.sig?.decodeCanonicalBase64() ?: return Opened.Refused("sig is not standard base64")
        if (signature.size != SIGNATURE_SIZE) return Opened.Refused("sig is not $SIGNATURE_SIZE bytes")
        if (!verify(key, payload, signature)) return Opened.Refused("signature does not verify")
        return Opened.Verified(payload)
    }

    // The basic decoder also takes unpadded input; the publisher only ever writes padded base64.
    private fun String.decodeCanonicalBase64(): ByteArray? {
        val bytes = runCatching { Base64.getDecoder().decode(this) }.getOrNull() ?: return null
        return bytes.takeIf { Base64.getEncoder().encodeToString(it) == this }
    }
}
