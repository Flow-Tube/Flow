package io.github.aedev.flow.sync2.linking

import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.roster.Entry
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Strict, and absent fields omitted, like roster entries. */
internal val pairingJson = Json { explicitNulls = false }

internal fun <T> canonical(
    serializer: KSerializer<T>,
    value: T,
): String = SyncSerialization.canonicalJson(pairingJson.encodeToJsonElement(serializer, value))

@Serializable
data class StoreLocation(
    val k: String,
    val u: String? = null,
)

/** What the QR code carries: no long-term secret, only a one-time token and an ephemeral key. */
@Serializable
data class QrCode(
    val v: Int,
    val t: String,
    val c: String,
    val r: String,
    val d: String,
    val s: StoreLocation? = null,
    val l: List<LanEndpoint>? = null,
)

@Serializable
data class DeviceMeta(
    val app: String,
    val name: String,
    val platform: String,
)

@Serializable
data class WelcomeStore(
    val k: String,
    val u: String? = null,
    val i: String? = null,
    /** A WebDAV store's user name and password, which travel only inside the pairing boxes. */
    val n: String? = null,
    val w: String? = null,
)

@Serializable
internal sealed interface Message {
    @Serializable
    @SerialName("join")
    data class Join(
        val sig: String,
        val kem: String,
        val meta: DeviceMeta,
        /** Over the LAN, the port this device's LAN service listens on (spec/protocol.md, Messages). */
        val p: Int? = null,
    ) : Message

    @Serializable
    @SerialName("entry")
    data class EntryMessage(
        val entry: Entry,
    ) : Message

    @Serializable
    @SerialName("accept")
    data class Accept(
        val sig: String,
    ) : Message

    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val group: String,
        val head: String,
        val seq: Long,
        val link: String,
        val store: WelcomeStore,
        /** A LAN group has no store to read: the roster up to [head], and the new device's wrap of the current epoch. */
        val entries: List<Entry>? = null,
        val wrap: String? = null,
        val p: Int? = null,
    ) : Message

    @Serializable
    @SerialName("done")
    data object Done : Message
}
