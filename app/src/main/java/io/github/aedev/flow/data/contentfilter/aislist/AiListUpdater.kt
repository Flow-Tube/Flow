package io.github.aedev.flow.data.contentfilter.aislist

import java.io.IOException
import java.security.MessageDigest

internal class AiListResponse(
    val status: Int,
    val body: ByteArray?,
    val etag: String?,
)

/** One GET under the list's base URL. Throws [IOException] when the request does not complete. */
internal fun interface AiListFetcher {
    suspend fun get(
        path: String,
        etag: String?,
    ): AiListResponse
}

internal interface AiListStorage {
    fun load(): StoredAiList?

    fun commit(list: StoredAiList)
}

internal enum class AiListOutcome {
    NOT_MODIFIED,
    UP_TO_DATE,
    SNAPSHOT,
    DELTAS,
    FALLBACK_SNAPSHOT,

    /** Signature, schema, rollback or hash: retrying cannot fix it, so the next daily run tries again. */
    REFUSED,

    /** The network or the server failed; worth a retry with backoff. */
    RETRY,
}

internal class AiListUpdate(
    val outcome: AiListOutcome,
    val list: StoredAiList?,
    val reason: String? = null,
)

/**
 * One update run of the client contract (Flow-Tube/AIlist README, "Update procedure"). Every
 * failure leaves the stored list exactly as it was; the in-memory list changes only after a commit.
 */
internal class AiListUpdater(
    private val fetcher: AiListFetcher,
    private val storage: AiListStorage,
    private val source: AiListSource = AiListSource.DEFAULT,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): AiListUpdate {
        val stored = storage.load()?.takeIf { (it.source ?: AiListSource.DEFAULT.id) == source.id }
        val base = "v1/$AI_LIST_ID/"
        val response =
            try {
                fetcher.get(base + "manifest.json", stored?.etag)
            } catch (error: IOException) {
                return AiListUpdate(AiListOutcome.RETRY, stored, error.message)
            }
        if (response.status == HTTP_NOT_MODIFIED) return AiListUpdate(AiListOutcome.NOT_MODIFIED, stored)
        if (response.status != HTTP_OK || response.body == null) return failedStatus(response.status, stored)

        val payload =
            when (val opened = AiListEnvelope.open(response.body, source::keyFor)) {
                is AiListEnvelope.Opened.Refused -> return refused(stored, opened.reason)
                is AiListEnvelope.Opened.Verified -> opened.payload
            }
        val manifest =
            runCatching { aiListJson.decodeFromString(AiListManifest.serializer(), payload.decodeToString()) }
                .getOrNull() ?: return refused(stored, "manifest payload does not parse")
        if (manifest.schema > AI_LIST_SCHEMA) return refused(stored, "schema ${manifest.schema}")
        if (manifest.list != AI_LIST_ID) return refused(stored, "list ${manifest.list}")
        if (stored != null && manifest.version < stored.version) return refused(stored, "older version")
        if (stored != null && manifest.version == stored.version) return AiListUpdate(AiListOutcome.UP_TO_DATE, stored)

        var outcome = AiListOutcome.SNAPSHOT
        var next: StoredAiList? = null
        if (stored != null && usesDeltas(stored, manifest)) {
            try {
                next = applyDeltas(base, stored, manifest)
                outcome = AiListOutcome.DELTAS
            } catch (error: IOException) {
                return AiListUpdate(AiListOutcome.RETRY, stored, error.message)
            } catch (_: FileRejected) {
                outcome = AiListOutcome.FALLBACK_SNAPSHOT
            }
        }
        if (next == null) {
            next =
                try {
                    takeSnapshot(base, manifest)
                } catch (error: IOException) {
                    return AiListUpdate(AiListOutcome.RETRY, stored, error.message)
                } catch (rejected: FileRejected) {
                    return if (rejected.status >= HTTP_SERVER_ERROR) {
                        AiListUpdate(AiListOutcome.RETRY, stored, rejected.message)
                    } else {
                        refused(stored, rejected.message)
                    }
                }
        }

        val committed = next.copy(etag = response.etag, updatedAtMs = now(), source = source.id)
        storage.commit(committed)
        return AiListUpdate(outcome, committed)
    }

    private fun usesDeltas(
        stored: StoredAiList,
        manifest: AiListManifest,
    ): Boolean {
        val steps = manifest.version - stored.version
        if (steps > manifest.maxDeltaChain) return false
        val available = manifest.deltas.mapTo(HashSet()) { it.to }
        return (stored.version + 1..manifest.version).all { it in available }
    }

    private suspend fun applyDeltas(
        base: String,
        stored: StoredAiList,
        manifest: AiListManifest,
    ): StoredAiList {
        val refs = manifest.deltas.associateBy { it.to }
        var working = stored
        for (version in stored.version + 1..manifest.version) {
            val ref = refs.getValue(version)
            val delta =
                decode(fetchVerified(base, ref.path, ref.sha256)) { aiListJson.decodeFromString(AiListDeltaFile.serializer(), it) }
            if (delta.list != AI_LIST_ID || delta.from != version - 1 || delta.to != version) {
                throw FileRejected("delta $version has the wrong range")
            }
            working = working.applying(delta)
        }
        return working
    }

    private suspend fun takeSnapshot(
        base: String,
        manifest: AiListManifest,
    ): StoredAiList {
        val snapshot =
            decode(fetchVerified(base, manifest.snapshot.path, manifest.snapshot.sha256)) {
                aiListJson.decodeFromString(AiListSnapshotFile.serializer(), it)
            }
        if (snapshot.list != AI_LIST_ID || snapshot.version != manifest.version) {
            throw FileRejected("snapshot does not match the manifest")
        }
        return StoredAiList(version = snapshot.version, block = snapshot.block, warn = snapshot.warn)
    }

    private suspend fun fetchVerified(
        base: String,
        path: String,
        sha256: String,
    ): ByteArray {
        if (path.startsWith("/") || ".." in path || "://" in path) throw FileRejected("$path is not a list path")
        val file = fetcher.get(base + path, null)
        val body = file.body
        if (file.status != HTTP_OK || body == null) throw FileRejected("$path: status ${file.status}", file.status)
        if (body.sha256Hex() != sha256.lowercase()) throw FileRejected("$path: hash mismatch")
        return body
    }

    private inline fun <T> decode(
        bytes: ByteArray,
        parse: (String) -> T,
    ): T = runCatching { parse(bytes.decodeToString()) }.getOrElse { throw FileRejected("file does not parse") }

    private fun failedStatus(
        status: Int,
        stored: StoredAiList?,
    ): AiListUpdate =
        if (status >= HTTP_SERVER_ERROR || status == HTTP_TOO_MANY_REQUESTS) {
            AiListUpdate(AiListOutcome.RETRY, stored, "manifest status $status")
        } else {
            refused(stored, "manifest status $status")
        }

    private fun refused(
        stored: StoredAiList?,
        reason: String?,
    ) = AiListUpdate(AiListOutcome.REFUSED, stored, reason)

    private class FileRejected(
        message: String,
        val status: Int = 0,
    ) : Exception(message)

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_MODIFIED = 304
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}

internal fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
