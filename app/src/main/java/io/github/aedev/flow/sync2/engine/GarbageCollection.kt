package io.github.aedev.flow.sync2.engine

import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.data.AckRow
import io.github.aedev.flow.sync2.data.CollectedRow
import io.github.aedev.flow.sync2.data.LinkRow
import io.github.aedev.flow.sync2.data.ManifestRow
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.store.StoreException
import io.github.aedev.flow.sync2.store.StorePaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Deletes at most this many objects per sync, so one sync never turns into a flood of requests. */
private const val BUDGET = 500

typealias VersionVector = Map<String, Long>

@Serializable
private class Ack(
    val vv: VersionVector,
)

/** A snapshot manifest and where it sits: object [seq] of [did]'s log. */
data class Logged(
    val did: String,
    val seq: Long,
    val manifest: Manifest,
)

internal fun parseAck(content: ByteArray): VersionVector =
    try {
        Json.decodeFromString(Ack.serializer(), content.toString(Charsets.UTF_8)).vv
    } catch (_: SerializationException) {
        reject(ProtocolError.PAYLOAD)
    }

private val vvSerializer = MapSerializer(String.serializer(), Long.serializer())
private val linkSerializer = String.serializer().nullable

private fun canonical(manifest: Manifest) = SyncSerialization.canonicalJson(Json.encodeToJsonElement(Manifest.serializer(), manifest))

internal suspend fun Session.recordManifests(logged: List<Logged>) {
    db.collection().addManifests(logged.map { ManifestRow(it.did, it.seq, canonical(it.manifest)) })
}

/** Keeps what garbage collection needs from a read: each device's latest ack, manifests, and the links named. */
internal suspend fun Session.recordCollection(pulled: Pulled) {
    val dao = db.collection()
    dao.saveAcks(pulled.acks.map { (did, vv) -> AckRow(did, Json.encodeToString(vvSerializer, vv)) })
    recordManifests(pulled.manifests)
    dao.addLinks(pulled.links.map { LinkRow(Json.encodeToString(linkSerializer, it)) })
}

/** What may be deleted: a range of each collectable log, and superseded manifests. */
data class Plan(
    val logs: Map<String, LongRange> = emptyMap(),
    val manifests: List<Logged> = emptyList(),
)

/** What garbage collection decides on (spec/protocol.md, Garbage collection). */
class Facts(
    private val current: Set<String>,
    private val collectable: Set<String>,
    private val links: Set<String?>,
    private val acks: Map<String, VersionVector>,
    private val manifests: List<Logged>,
    private val collected: Map<String, Long>,
) {
    /** How far every current device other than [did] has acknowledged [did]'s log. */
    private fun acked(did: String): Long = current.filter { it != did }.minOfOrNull { acks[it]?.get(did) ?: 0 } ?: Long.MAX_VALUE

    private fun acknowledged(link: String?): List<Logged> = manifests.filter { it.manifest.link == link && acked(it.did) >= it.seq }

    /** A newer acknowledged snapshot covers [old], and every device read past [old] itself, so its removal leaves no gap. */
    private fun superseded(old: Logged): Boolean =
        acked(old.did) >= old.seq &&
            acknowledged(old.manifest.link).any { newer ->
                newer != old && old.manifest.vv.all { (did, tip) -> (newer.manifest.vv[did]?.seq ?: -1) >= tip.seq }
            }

    /** How far every link's newest acknowledged snapshot covers [did]'s log. */
    private fun covered(did: String): Long =
        links.minOfOrNull { link -> acknowledged(link).maxOfOrNull { it.manifest.vv[did]?.seq ?: 0 } ?: 0 } ?: 0

    fun plan(): Plan =
        Plan(
            logs =
                collectable
                    .associateWith { did -> (collected[did]?.plus(1) ?: 1)..minOf(acked(did), covered(did)) }
                    .filterValues { !it.isEmpty() },
            manifests = manifests.filter { it.did in collectable && superseded(it) },
        )
}

/**
 * Deletes this device's objects, and a removed device's, once every current device acknowledged
 * them and a snapshot covers them, and only those this device holds. The newest manifests stay; older ones go when a newer one covers them.
 * [linked] are the links this device writes, `null` for `profiles`; a deleted link no longer needs a snapshot.
 */
suspend fun Session.collectGarbage(linked: Set<String?>) {
    val dao = db.collection()
    val current = state.members.filterValues { it.role == Role.DEVICE }.keys
    val collectable =
        entries
            .flatMap {
                it.add.orEmpty()
            }.filter { it.role == Role.DEVICE && (it.did == me || it.did !in current) }
            .map { it.did }
            .toSet()
    val manifests = dao.manifests().map { Logged(it.did, it.seq, parseManifest(it.manifest.toByteArray())) }
    val deleted =
        db
            .profiles()
            .names()
            .filter { it.deleted }
            .map { it.link }
            .toSet()
    val links = dao.links().map { Json.decodeFromString(linkSerializer, it.link) }.toSet() + linked - deleted
    val plan =
        Facts(
            current = current,
            collectable = collectable,
            links = links,
            acks = dao.acks().associate { it.did to Json.decodeFromString(vvSerializer, it.vv) },
            manifests = manifests,
            collected = dao.collected().associate { it.did to it.through },
        ).plan()
    val held = heldPaths()
    var budget = BUDGET
    try {
        for (old in plan.manifests) {
            // Only what this device holds: another device may have deleted the rest already.
            val paths = old.manifest.parts.map { StorePaths.blob(old.did, it.blob) } + StorePaths.log(old.did, old.seq)
            val present = paths.filter { it in held }
            if (budget < present.size) continue
            present.forEach { store.deleteObject(it) }
            dao.deleteManifest(old.did, old.seq)
            budget -= present.size
        }
        for ((did, range) in plan.logs) {
            val kept = manifests.filter { it.did == did }.map { it.seq }.toSet()
            var done = range.first - 1
            for (seq in range) {
                val path = StorePaths.log(did, seq)
                if (seq !in kept && path in held) {
                    if (budget == 0) break
                    store.deleteObject(path)
                    budget--
                }
                done = seq
            }
            dao.setCollected(CollectedRow(did, done))
        }
    } catch (e: StoreException.Refused) {
        // The server is rate limiting this device: stop, so syncs and pairing get their share; the rest goes next time.
        if (!e.rateLimited) throw e
    }
}

/** Every object path this device holds. */
private suspend fun Session.heldPaths(): Set<String> {
    val paths = HashSet<String>()
    var cursor = 0L
    while (true) {
        val (changed, next) = store.changes(cursor)
        if (changed.isEmpty()) return paths
        changed.mapTo(paths) { it.path }
        cursor = next
    }
}
