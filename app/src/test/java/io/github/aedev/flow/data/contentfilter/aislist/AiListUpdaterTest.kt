package io.github.aedev.flow.data.contentfilter.aislist

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.sync2.crypto.sign
import io.github.aedev.flow.sync2.crypto.signingPublicKey
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.IOException
import java.util.Base64

/** The contract's update procedure (Flow-Tube/AIlist README) against a fake list host. */
class AiListUpdaterTest {
    private val seed = ByteArray(32) { it.toByte() }
    private val source = AiListSource.from("https://lists.example.org/", Base64.getEncoder().encodeToString(signingPublicKey(seed)))
    private val base = "v1/aislist/"

    private class MemoryStorage(
        var list: StoredAiList? = null,
    ) : AiListStorage {
        var commits = 0

        override fun load() = list

        override fun commit(list: StoredAiList) {
            this.list = list
            commits++
        }
    }

    /** Serves files by path; the manifest answers 304 when its ETag is sent back. */
    private class Host : AiListFetcher {
        val files = mutableMapOf<String, ByteArray>()
        var manifestEtag = "\"m1\""
        var offline = false
        val requested = mutableListOf<String>()

        override suspend fun get(
            path: String,
            etag: String?,
        ): AiListResponse {
            if (offline) throw IOException("offline")
            requested += path
            if (path.endsWith("manifest.json") && etag == manifestEtag) return AiListResponse(304, null, manifestEtag)
            val body = files[path] ?: return AiListResponse(404, null, null)
            return AiListResponse(200, body, if (path.endsWith("manifest.json")) manifestEtag else null)
        }
    }

    private fun snapshot(
        version: Long,
        block: List<String>,
        warn: List<String> = emptyList(),
    ) = buildJsonObject {
        put("schema", 1)
        put("list", "aislist")
        put("version", version)
        put("block", entries(block))
        put("warn", entries(warn))
    }.toString().encodeToByteArray()

    private fun entries(handles: List<String>) =
        JsonObject(mapOf("handles" to JsonArray(handles.map(::JsonPrimitive)), "ids" to JsonArray(emptyList())))

    private fun delta(
        from: Long,
        add: List<String>,
        remove: List<String> = emptyList(),
    ) = buildJsonObject {
        put("schema", 1)
        put("list", "aislist")
        put("from", from)
        put("to", from + 1)
        put(
            "block",
            JsonObject(
                mapOf(
                    "handles" to
                        JsonObject(mapOf("add" to JsonArray(add.map(::JsonPrimitive)), "remove" to JsonArray(remove.map(::JsonPrimitive)))),
                ),
            ),
        )
    }.toString().encodeToByteArray()

    private fun Host.publish(
        version: Long,
        snapshot: ByteArray,
        deltas: Map<Long, ByteArray> = emptyMap(),
        schema: Int = 1,
        list: String = "aislist",
        snapshotHash: String = snapshot.sha256Hex(),
    ) {
        files["${base}snapshot/$version.json"] = snapshot
        deltas.forEach { (to, bytes) -> files["${base}delta/$to.json"] = bytes }
        val payload =
            buildJsonObject {
                put("schema", schema)
                put("list", list)
                put("version", version)
                put(
                    "snapshot",
                    JsonObject(mapOf("path" to JsonPrimitive("snapshot/$version.json"), "sha256" to JsonPrimitive(snapshotHash))),
                )
                put(
                    "deltas",
                    JsonArray(
                        deltas.map { (to, bytes) ->
                            JsonObject(
                                mapOf(
                                    "to" to JsonPrimitive(to),
                                    "path" to JsonPrimitive("delta/$to.json"),
                                    "sha256" to JsonPrimitive(bytes.sha256Hex()),
                                ),
                            )
                        },
                    ),
                )
                put("max_delta_chain", 14)
            }.toString().encodeToByteArray()
        val envelope =
            buildJsonObject {
                put("key_id", "test")
                put("payload", Base64.getEncoder().encodeToString(payload))
                put("sig", Base64.getEncoder().encodeToString(sign(seed, payload)))
            }
        files["${base}manifest.json"] = envelope.toString().encodeToByteArray()
        manifestEtag = "\"m$version\""
    }

    private fun updater(
        host: Host,
        storage: MemoryStorage,
        listSource: AiListSource = source,
    ) = AiListUpdater(host, storage, listSource, now = { 1_000L })

    @Test
    fun `the first run takes the snapshot and stores it with the manifest ETag`() =
        runTest {
            val host = Host().apply { publish(1, snapshot(1, listOf("alpha", "beta"), warn = listOf("gamma"))) }
            val storage = MemoryStorage()

            val update = updater(host, storage).run()

            assertThat(update.outcome).isEqualTo(AiListOutcome.SNAPSHOT)
            assertThat(storage.list!!.block.handles).containsExactly("alpha", "beta")
            assertThat(storage.list!!.warn.handles).containsExactly("gamma")
            assertThat(storage.list!!.etag).isEqualTo("\"m1\"")
            assertThat(storage.list!!.updatedAtMs).isEqualTo(1_000L)
        }

    @Test
    fun `a later run applies each delta, removes before adds`() =
        runTest {
            val host = Host().apply { publish(1, snapshot(1, listOf("alpha", "beta"))) }
            val storage = MemoryStorage()
            updater(host, storage).run()
            host.publish(
                3,
                snapshot(3, listOf("beta", "delta", "epsilon")),
                deltas = mapOf(2L to delta(1, add = listOf("delta"), remove = listOf("alpha")), 3L to delta(2, add = listOf("epsilon"))),
            )

            val update = updater(host, storage).run()

            assertThat(update.outcome).isEqualTo(AiListOutcome.DELTAS)
            assertThat(storage.list!!.version).isEqualTo(3)
            assertThat(storage.list!!.block.handles).containsExactly("beta", "delta", "epsilon").inOrder()
            assertThat(host.requested).doesNotContain("${base}snapshot/3.json")
        }

    @Test
    fun `an unchanged manifest answers 304 and nothing is written`() =
        runTest {
            val host = Host().apply { publish(1, snapshot(1, listOf("alpha"))) }
            val storage = MemoryStorage()
            updater(host, storage).run()

            val update = updater(host, storage).run()

            assertThat(update.outcome).isEqualTo(AiListOutcome.NOT_MODIFIED)
            assertThat(storage.commits).isEqualTo(1)
        }

    @Test
    fun `a missing delta falls back to the snapshot in the same run`() =
        runTest {
            val host = Host().apply { publish(1, snapshot(1, listOf("alpha"))) }
            val storage = MemoryStorage()
            updater(host, storage).run()
            host.publish(2, snapshot(2, listOf("alpha", "beta")), deltas = mapOf(2L to delta(1, add = listOf("beta"))))
            host.files.remove("${base}delta/2.json")

            val update = updater(host, storage).run()

            assertThat(update.outcome).isEqualTo(AiListOutcome.FALLBACK_SNAPSHOT)
            assertThat(storage.list!!.block.handles).containsExactly("alpha", "beta")
        }

    @Test
    fun `a hash mismatch leaves the stored list exactly as it was`() =
        runTest {
            val host = Host().apply { publish(1, snapshot(1, listOf("alpha"))) }
            val storage = MemoryStorage()
            updater(host, storage).run()
            val before = storage.list
            host.publish(2, snapshot(2, listOf("alpha", "beta")), snapshotHash = "0".repeat(64))

            val update = updater(host, storage).run()

            assertThat(update.outcome).isEqualTo(AiListOutcome.REFUSED)
            assertThat(storage.list).isSameInstanceAs(before)
            assertThat(storage.commits).isEqualTo(1)
        }

    @Test
    fun `a newer schema, another list or an older version is refused`() =
        runTest {
            val storage = MemoryStorage(StoredAiList(version = 5, source = source.id))
            val newerSchema = Host().apply { publish(6, snapshot(6, listOf("a")), schema = 2) }
            val otherList = Host().apply { publish(6, snapshot(6, listOf("a")), list = "other") }
            val rollback = Host().apply { publish(4, snapshot(4, listOf("a"))) }

            assertThat(updater(newerSchema, storage).run().outcome).isEqualTo(AiListOutcome.REFUSED)
            assertThat(updater(otherList, storage).run().outcome).isEqualTo(AiListOutcome.REFUSED)
            assertThat(updater(rollback, storage).run().outcome).isEqualTo(AiListOutcome.REFUSED)
            assertThat(storage.commits).isEqualTo(0)
        }

    @Test
    fun `a manifest signed with an untrusted key is refused`() =
        runTest {
            val host = Host().apply { publish(1, snapshot(1, listOf("alpha"))) }
            val storage = MemoryStorage()

            val otherKey = Base64.getEncoder().encodeToString(signingPublicKey(ByteArray(32) { 7 }))
            val update = AiListUpdater(host, storage, AiListSource.from("https://lists.example.org/", otherKey), now = { 0L }).run()

            assertThat(update.outcome).isEqualTo(AiListOutcome.REFUSED)
            assertThat(storage.list).isNull()
        }

    @Test
    fun `a list stored from another source is not built on, so the new source starts from its snapshot`() =
        runTest {
            val host =
                Host().apply {
                    publish(
                        2,
                        snapshot(2, listOf("alpha", "beta")),
                        deltas = mapOf(2L to delta(1, add = listOf("beta"))),
                    )
                }
            val storage = MemoryStorage(StoredAiList(version = 1, etag = "\"m2\"", source = AiListSource.DEFAULT.id))

            val update = updater(host, storage).run()

            assertThat(update.outcome).isEqualTo(AiListOutcome.SNAPSHOT)
            assertThat(storage.list!!.source).isEqualTo(source.id)
            assertThat(storage.list!!.block.handles).containsExactly("alpha", "beta")
        }

    @Test
    fun `an offline device retries later and keeps its list`() =
        runTest {
            val storage = MemoryStorage(StoredAiList(version = 1))
            val host = Host().apply { offline = true }

            val update = updater(host, storage).run()

            assertThat(update.outcome).isEqualTo(AiListOutcome.RETRY)
            assertThat(storage.commits).isEqualTo(0)
        }
}
