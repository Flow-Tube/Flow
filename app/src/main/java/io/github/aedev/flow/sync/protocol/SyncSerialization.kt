package io.github.aedev.flow.sync.protocol

import io.github.aedev.flow.sync.canonical.CanonicalBrain
import io.github.aedev.flow.sync.canonical.CanonicalLike
import io.github.aedev.flow.sync.canonical.CanonicalMusicBrain
import io.github.aedev.flow.sync.canonical.CanonicalNote
import io.github.aedev.flow.sync.canonical.CanonicalPlaylist
import io.github.aedev.flow.sync.canonical.CanonicalSetting
import io.github.aedev.flow.sync.canonical.CanonicalSubscribedChannel
import io.github.aedev.flow.sync.canonical.CanonicalSubscriptionGroup
import io.github.aedev.flow.sync.canonical.CanonicalWatchHistory
import io.github.aedev.flow.sync.merge.MusicBrainMerger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.security.MessageDigest

/** A serialized collection ready for the wire: NDJSON [lines], [recordCount], and content [hash]. */
data class CollectionWire(
    val lines: List<String>,
    val recordCount: Int,
    val hash: String,
)

/**
 * NDJSON serialization + canonical content hashing for each collection. Records are emitted in
 * canonical sort order so the SHA-256 [hash] is independent of chunking/compression
 * **and byte-identical to the desktop's** for the same logical data — the integrity check, the
 * `sync_log` idempotency guard, and the shared golden vectors all depend on this stability.
 *
 * Canonical JSON = compact (no insignificant whitespace), with **object keys in ascending Unicode
 * codepoint order** ([canonicalize]). kotlinx emits keys in declaration order, so every record is
 * re-keyed through a [JsonElement] before it goes on the wire.
 */
object SyncSerialization {
    // encodeDefaults: stable canonical output (all fields present). ignoreUnknownKeys/isLenient/
    // coerceInputValues are DECODE-only (no effect on encoded bytes or the hash) and make us
    // tolerant of the desktop's JSON: unknown fields, lenient literals, and null/garbage in a
    // non-null field falling back to the schema default instead of throwing.
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /** Recursively re-key every JSON object in ascending key order. */
    private fun canonicalize(e: JsonElement): JsonElement =
        when (e) {
            is JsonObject -> {
                JsonObject(
                    e.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to canonicalize(it.value) },
                )
            }

            is JsonArray -> {
                JsonArray(e.map { canonicalize(it) })
            }

            else -> {
                e
            }
        }

    /** Compact, sorted-key canonical JSON, shared by FLOW-SYNC/1 records and FLOW-SYNC/2 messages. */
    fun canonicalJson(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), canonicalize(element))

    private fun <T> enc(
        serializer: KSerializer<T>,
        value: T,
    ): String = canonicalJson(json.encodeToJsonElement(serializer, value))

    private fun <T> wire(
        records: List<T>,
        serializer: KSerializer<T>,
    ): CollectionWire {
        val lines = records.map { enc(serializer, it) }
        return CollectionWire(lines, lines.size, sha256Hex(lines.joinToString("\n")))
    }

    // --- watch history ---
    fun encodeWatchHistory(records: List<CanonicalWatchHistory>) = wire(records.sortedBy { it.videoId }, CanonicalWatchHistory.serializer())

    fun decodeWatchHistory(lines: List<String>): List<CanonicalWatchHistory> =
        lines.filter { it.isNotBlank() }.map { json.decodeFromString(CanonicalWatchHistory.serializer(), it) }

    // --- playlists ---
    fun encodePlaylists(records: List<CanonicalPlaylist>) = wire(records.sortedBy { it.syncId }, CanonicalPlaylist.serializer())

    fun decodePlaylists(lines: List<String>): List<CanonicalPlaylist> =
        lines.filter { it.isNotBlank() }.map { json.decodeFromString(CanonicalPlaylist.serializer(), it) }

    // --- likes ---
    fun encodeLikes(records: List<CanonicalLike>) = wire(records.sortedWith(compareBy({ it.kind }, { it.id })), CanonicalLike.serializer())

    fun decodeLikes(lines: List<String>): List<CanonicalLike> =
        lines.filter { it.isNotBlank() }.map { json.decodeFromString(CanonicalLike.serializer(), it) }

    // --- settings ---
    fun encodeSettings(records: List<CanonicalSetting>) = wire(records.sortedBy { it.key }, CanonicalSetting.serializer())

    fun decodeSettings(lines: List<String>): List<CanonicalSetting> =
        lines.filter { it.isNotBlank() }.map { json.decodeFromString(CanonicalSetting.serializer(), it) }

    // --- subscribed channels ---
    fun encodeSubscribedChannels(records: List<CanonicalSubscribedChannel>) =
        wire(records.sortedBy { it.channelId }, CanonicalSubscribedChannel.serializer())

    fun decodeSubscribedChannels(lines: List<String>): List<CanonicalSubscribedChannel> =
        lines.filter { it.isNotBlank() }.map { json.decodeFromString(CanonicalSubscribedChannel.serializer(), it) }

    // --- subscription groups ---
    fun encodeSubscriptions(records: List<CanonicalSubscriptionGroup>) =
        wire(records.sortedBy { it.name }, CanonicalSubscriptionGroup.serializer())

    fun decodeSubscriptions(lines: List<String>): List<CanonicalSubscriptionGroup> =
        lines.filter { it.isNotBlank() }.map { json.decodeFromString(CanonicalSubscriptionGroup.serializer(), it) }

    // --- notes ---
    fun encodeNotes(records: List<CanonicalNote>) = wire(records.sortedBy { it.id }, CanonicalNote.serializer())

    fun decodeNotes(lines: List<String>): List<CanonicalNote> =
        lines.filter { it.isNotBlank() }.map { json.decodeFromString(CanonicalNote.serializer(), it) }

    private val recordSerializers: Map<String, KSerializer<*>> =
        mapOf(
            SyncCollection.WATCH_HISTORY to CanonicalWatchHistory.serializer(),
            SyncCollection.PLAYLISTS to CanonicalPlaylist.serializer(),
            SyncCollection.LIKES to CanonicalLike.serializer(),
            SyncCollection.SETTINGS to CanonicalSetting.serializer(),
            SyncCollection.SUBSCRIBED_CHANNELS to CanonicalSubscribedChannel.serializer(),
            SyncCollection.SUBSCRIPTIONS to CanonicalSubscriptionGroup.serializer(),
            SyncCollection.NOTES to CanonicalNote.serializer(),
            SyncCollection.FLOW_NEURO_BRAIN to CanonicalBrain.serializer(),
            SyncCollection.MUSIC_BRAIN to CanonicalMusicBrain.serializer(),
        )

    /** Decodes each record of a known [collection] as the merges will, throwing on one they can't read. */
    fun checkRecords(
        collection: String,
        lines: List<String>,
    ) {
        val serializer = recordSerializers[collection] ?: return
        lines.filter { it.isNotBlank() }.forEach { json.decodeFromString(serializer, it) }
    }

    // --- brain ---

    /** One line per device snapshot, the sender's own carrying the shared CRDT state. */
    fun encodeBrain(snapshots: List<CanonicalBrain>) = wire(snapshots.sortedBy { it.deviceId }, CanonicalBrain.serializer())

    // --- music brain ---
    fun encodeMusicBrain(brain: CanonicalMusicBrain): CollectionWire {
        val line = enc(CanonicalMusicBrain.serializer(), brain)
        return CollectionWire(listOf(line), 1, sha256Hex(line))
    }

    /** Fold every record (one snapshot per device the peer knows) so a third device converges. */
    fun decodeMusicBrain(lines: List<String>): CanonicalMusicBrain? =
        lines
            .filter { it.isNotBlank() }
            .map { json.decodeFromString(CanonicalMusicBrain.serializer(), it) }
            .reduceOrNull(MusicBrainMerger::merge)

    /**
     * Every record, kept apart: the desktop ships one snapshot per device it knows about
     * (`brainmap::merged_flow_to_snapshots`), and the learned vectors are a weighted mean over those
     * devices, which a fold into one record would lose.
     */
    fun decodeBrainSnapshots(lines: List<String>): List<CanonicalBrain> =
        lines
            .filter { it.isNotBlank() }
            .map { json.decodeFromString(CanonicalBrain.serializer(), it) }
}
