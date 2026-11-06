package io.github.aedev.flow.sync2.linking

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync2.crypto.agreementPublicKey
import io.github.aedev.flow.sync2.crypto.pairingCommit
import io.github.aedev.flow.sync2.engine.LinkedException
import kotlinx.serialization.SerializationException
import java.util.concurrent.atomic.AtomicBoolean

private const val NAME_LIMIT = 64

internal fun key(value: String): ByteArray =
    runCatching { SyncBytes.b64urlDecode(value) }.getOrNull()?.takeIf { it.size == KEY_SIZE } ?: throw LinkedException.Unexpected()

/** A code read from another device (spec/protocol.md, QR code), checked for what this build can answer. */
sealed class Code(
    val qr: QrCode,
    val lan: List<LanEndpoint>,
) {
    /**
     * A member's code, which a new device answers: where its group lives (a Flow server or WebDAV
     * folder, null for a LAN group), and LAN addresses to try first.
     */
    class Member(
        qr: QrCode,
        val store: StoreLocation?,
        lan: List<LanEndpoint>,
    ) : Code(qr, lan) {
        val storeUrl: String? get() = store?.u
    }

    /** A new device's code, which a member answers over the LAN. */
    class New(
        qr: QrCode,
        lan: List<LanEndpoint>,
    ) : Code(qr, lan)

    internal val token: ByteArray
        get() = runCatching { SyncBytes.b64urlDecode(qr.t) }.getOrNull()?.takeIf { it.size == 16 } ?: throw LinkedException.Unexpected()

    /** The hash of the shower's key, which the shower reveals on the channel. */
    internal val commitment: ByteArray get() = key(qr.c)
}

/** Reads a FLOW-SYNC/2 code; FLOW-SYNC/1 codes and stores other than a Flow server or WebDAV are refused. */
fun readCode(text: String): Code {
    val qr =
        try {
            pairingJson.decodeFromString(QrCode.serializer(), text.trim())
        } catch (_: SerializationException) {
            throw LinkedException.Unexpected()
        } catch (_: IllegalArgumentException) {
            throw LinkedException.Unexpected()
        }
    val lan = qr.l.orEmpty()
    val store = qr.s
    return when {
        qr.v == 2 && qr.r == "m" && store?.k in setOf("server", "webdav") && store?.u != null -> Code.Member(qr, store, lan)
        qr.v == 2 && qr.r == "m" && store?.k == "lan" && lan.isNotEmpty() -> Code.Member(qr, null, lan)
        qr.v == 2 && qr.r == "n" && store == null && lan.isNotEmpty() -> Code.New(qr, lan)
        else -> throw LinkedException.Unexpected()
    }
}

/** A code this device shows, valid until it stops listening. */
class Offer internal constructor(
    val qr: String,
    internal val token: ByteArray,
    internal val secret: ByteArray,
    internal val public: ByteArray,
    internal val listening: Listening?,
) {
    private val revealed = AtomicBoolean(false)

    /** The shower's key, given out at most once per code, whichever way the scanner arrived (spec/protocol.md, Channel). */
    internal fun reveal(): ByteArray {
        if (!revealed.compareAndSet(false, true)) throw LinkedException.Unexpected()
        return public
    }
}

/** A member's code ([store] set) or a new device's ([store] null, so it must listen). */
fun newOffer(
    store: StoreLocation?,
    deviceName: String,
    listening: Listening?,
): Offer {
    val token = SyncCrypto.randomBytes(16)
    val secret = SyncCrypto.randomBytes(KEY_SIZE)
    val public = agreementPublicKey(secret)
    val code =
        QrCode(
            v = 2,
            t = SyncBytes.b64urlEncode(token),
            c = SyncBytes.b64urlEncode(pairingCommit(public)),
            r = if (store != null) "m" else "n",
            d = deviceName.take(NAME_LIMIT),
            s = store,
            l = listening?.addresses,
        )
    return Offer(canonical(QrCode.serializer(), code), token, secret, public, listening)
}
