package io.github.aedev.flow.sync2.store

import io.github.aedev.flow.sync.protocol.SyncSerialization
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val STORE_JSON = "store.json"

/** A folder's login, as a store login object carries it (spec/store.md, Store logins). */
data class StoreLogin(
    val user: String,
    val password: String,
)

internal fun encodeStoreLogin(login: StoreLogin): ByteArray =
    SyncSerialization
        .canonicalJson(
            buildJsonObject {
                put("k", "webdav")
                put("n", login.user)
                put("w", login.password)
            },
        ).toByteArray()

/** The login in [content], or null for anything else, such as another kind of store. */
internal fun decodeStoreLogin(content: ByteArray): StoreLogin? =
    runCatching {
        val record = Json.parseToJsonElement(content.decodeToString()).jsonObject
        if (record["k"]?.jsonPrimitive?.contentOrNull != "webdav") return null
        StoreLogin(record.getValue("n").jsonPrimitive.content, record.getValue("w").jsonPrimitive.content)
    }.getOrNull()

/** Whether the folder takes the login [files] was connected with, and still holds [group]. Returns the probe's answer, or null. */
internal suspend fun checkFolder(
    files: WebDavStore,
    group: String,
): Boolean? {
    val conditional = files.probe()
    val marker = files.get(STORE_JSON) ?: return null
    val held =
        runCatching {
            Json
                .parseToJsonElement(
                    marker.decodeToString(),
                ).jsonObject["group"]
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()
    return conditional.takeIf { held == group }
}
