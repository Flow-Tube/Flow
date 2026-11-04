package io.github.aedev.flow.sync2.crypto

/** Every way an object, key or message can be rejected; [code] matches the shared test vectors. */
enum class ProtocolError(
    val code: String,
) {
    LENGTH("length"),
    RANGE("range"),
    MAGIC("magic"),
    SUITE("suite"),
    GROUP("group"),
    SENDER("sender"),
    SIGNATURE("signature"),
    REPLAY("replay"),
    GAP("gap"),
    PREV("prev"),
    COMMITMENT("commitment"),
    DECRYPT("decrypt"),
    PAYLOAD("payload"),
    BLOB_HASH("blob-hash"),
    KEY_COMMITMENT("key-commitment"),
    X25519("x25519"),
    ROSTER_FORMAT("roster-format"),
    ROSTER_GROUP("roster-group"),
    ROSTER_CHAIN("roster-chain"),
    ROSTER_OP("roster-op"),
    ROSTER_SIGNER("roster-signer"),
    ROSTER_SIGNATURE("roster-signature"),
    ROSTER_KEY("roster-key"),
    ROSTER_EPOCH("roster-epoch"),
    ROSTER_META("roster-meta"),
}

class ProtocolException(
    val error: ProtocolError,
) : Exception(error.code)

fun reject(error: ProtocolError): Nothing = throw ProtocolException(error)
