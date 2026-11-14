package io.github.aedev.flow.data.contentfilter.aislist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The list id the app asks for; it is inside the signed payload, so another list cannot stand in for it. */
internal const val AI_LIST_ID = "aislist"

/** The highest manifest schema this client understands. */
internal const val AI_LIST_SCHEMA = 1

internal val aiListJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

@Serializable
internal data class AiListEnvelopeJson(
    @SerialName("key_id") val keyId: String? = null,
    val payload: String? = null,
    val sig: String? = null,
)

@Serializable
internal data class AiListManifest(
    val schema: Int,
    val list: String,
    val version: Long,
    val snapshot: AiListFileRef,
    val deltas: List<AiListDeltaRef> = emptyList(),
    @SerialName("max_delta_chain") val maxDeltaChain: Int = 0,
)

@Serializable
internal data class AiListFileRef(
    val path: String,
    val sha256: String,
)

@Serializable
internal data class AiListDeltaRef(
    val to: Long,
    val path: String,
    val sha256: String,
)

@Serializable
internal data class AiListEntries(
    val handles: List<String> = emptyList(),
    val ids: List<String> = emptyList(),
)

@Serializable
internal data class AiListSnapshotFile(
    val list: String,
    val version: Long,
    val block: AiListEntries = AiListEntries(),
    val warn: AiListEntries = AiListEntries(),
)

@Serializable
internal data class AiListChanges(
    val add: List<String> = emptyList(),
    val remove: List<String> = emptyList(),
)

@Serializable
internal data class AiListDeltaSide(
    val handles: AiListChanges = AiListChanges(),
    val ids: AiListChanges = AiListChanges(),
)

@Serializable
internal data class AiListDeltaFile(
    val list: String,
    val from: Long,
    val to: Long,
    val block: AiListDeltaSide = AiListDeltaSide(),
    val warn: AiListDeltaSide = AiListDeltaSide(),
)

/** What the device keeps: the last good list, its manifest ETag, and when it was taken. */
@Serializable
internal data class StoredAiList(
    val version: Long,
    val etag: String? = null,
    val updatedAtMs: Long = 0L,
    val source: String? = null,
    val block: AiListEntries = AiListEntries(),
    val warn: AiListEntries = AiListEntries(),
) {
    val channelCount: Int
        get() = block.handles.size + block.ids.size + warn.handles.size + warn.ids.size
}

/** Removes first, then adds, as the contract orders; applying a delta twice changes nothing. */
internal fun StoredAiList.applying(delta: AiListDeltaFile): StoredAiList =
    copy(
        version = delta.to,
        block = block.applying(delta.block),
        warn = warn.applying(delta.warn),
    )

private fun AiListEntries.applying(side: AiListDeltaSide): AiListEntries =
    AiListEntries(
        handles = handles.applying(side.handles),
        ids = ids.applying(side.ids),
    )

private fun List<String>.applying(changes: AiListChanges): List<String> {
    if (changes.add.isEmpty() && changes.remove.isEmpty()) return this
    val result = LinkedHashSet(this)
    result.removeAll(changes.remove.toSet())
    result.addAll(changes.add)
    return result.sorted()
}
