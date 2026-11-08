package io.github.aedev.flow.sync2.store

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.qr.LocalNetworkAddress
import io.github.aedev.flow.sync.transport.MessageLimit
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.sign
import io.github.aedev.flow.sync2.crypto.tag
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.MAX_ROSTER_ENTRIES
import io.github.aedev.flow.sync2.roster.entryJson
import io.github.aedev.flow.sync2.roster.parseEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Refresh a token this long before it expires. */
private const val TOKEN_MARGIN_MS = 60_000L
private const val LIVE_PING_S = 15L

/** The most a live message or a server notice may hold (spec/store.md, Untrusted input). */
internal const val MAX_LIVE_MESSAGE = 64L * 1024
private val JSON = "application/json".toMediaType()
private val BYTES = "application/octet-stream".toMediaType()

/**
 * The Flow sync server as a store (spec/api.md). Requests are signed in with this member's roster
 * key; the server never sees anything but sealed objects.
 */
class ServerStore private constructor(
    private val base: HttpUrl,
    private val client: OkHttpClient,
    private val member: MemberSecrets,
    private var instance: String?,
    private var group: String?,
) : ChangeList,
    Remote {
    private val session = Mutex()
    private val tokenKey = "$base ${SyncBytes.toHex(member.did)}"

    /** A token and the instance and group it was issued for. */
    private class Issued(
        val value: String,
        val expires: Long,
        val instance: String,
        val group: String,
    )

    companion object {
        // Shared by every store of the process, so each sync run doesn't sign in again.
        private val tokens = ConcurrentHashMap<String, Issued>()

        /**
         * Prepares a connection to [url] for [member]. HTTPS anywhere; plain HTTP only to a private
         * address or a `.local` name. [instance] and [group] are the ids pinned at pairing; a device
         * restoring from its recovery key doesn't know them yet.
         */
        fun connect(
            url: String,
            member: MemberSecrets,
            instance: String?,
            group: String?,
        ): ServerStore {
            val base = url.toHttpUrlOrNull() ?: throw StoreException.Insecure()
            return ServerStore(base, storeClient(base), member, instance, group)
        }
    }

    /** The instance and group ids, once signed in; the caller stores them to pin later sessions. */
    suspend fun identity(): Pair<String?, String?> = session.withLock { instance to group }

    private fun url(path: String): HttpUrl = base.resolve(path) ?: throw StoreException.Malformed()

    /**
     * The whole body, up to [limit] bytes, is read here, on the IO dispatcher, so callers never read the network from
     * their own thread.
     */
    private suspend fun send(
        request: Request,
        limit: Long = MAX_RESPONSE,
    ): Response =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    response.newBuilder().body(response.body.bytesUpTo(limit).toResponseBody(response.body.contentType())).build()
                }
            } catch (e: IOException) {
                throw StoreException.Network(e)
            }
        }

    private suspend fun postJson(
        path: String,
        body: JsonElement,
    ): JsonObject =
        decode(
            send(
                Request
                    .Builder()
                    .url(url(path))
                    .post(body.toString().toRequestBody(JSON))
                    .build(),
            ),
        )

    private suspend fun signIn(): String {
        val did = SyncBytes.toHex(member.did)
        val challenge = postJson("v1/auth/challenge", buildJsonObject { put("did", did) })
        val field = { name: String -> challenge[name]?.jsonPrimitive?.contentOrNull ?: throw StoreException.Malformed() }
        val (instanceId, groupId, nonce) = Triple(field("instance"), field("group"), field("challenge"))
        if (instance.let { it != null && it != instanceId } || group.let { it != null && it != groupId }) {
            throw StoreException.InstanceChanged()
        }
        val message =
            try {
                tag("auth") + SyncBytes.fromHex(instanceId) + SyncBytes.fromHex(groupId) + SyncBytes.b64urlDecode(nonce)
            } catch (_: IllegalArgumentException) {
                throw StoreException.Malformed()
            }
        val issued =
            try {
                postJson(
                    "v1/auth/token",
                    buildJsonObject {
                        put("did", did)
                        put("challenge", nonce)
                        put("sig", SyncBytes.b64urlEncode(sign(member.sigSeed, message)))
                    },
                )
            } catch (e: StoreException.Refused) {
                // The server issues tokens only to current devices: this one left or was removed.
                if (e.status == 401 || e.status == 403) throw StoreException.SignInRefused() else throw e
            }
        val value = issued["token"]?.jsonPrimitive?.contentOrNull ?: throw StoreException.Malformed()
        val expires = issued["expires"]?.jsonPrimitive?.longOrNull ?: throw StoreException.Malformed()
        instance = instanceId
        group = groupId
        tokens[tokenKey] = Issued(value, expires, instanceId, groupId)
        return value
    }

    private suspend fun token(renew: Boolean): String =
        session.withLock {
            val held =
                tokens[tokenKey]?.takeIf {
                    !renew &&
                        it.expires > System.currentTimeMillis() + TOKEN_MARGIN_MS &&
                        instance.let { pinned -> pinned == null || pinned == it.instance } &&
                        group.let { pinned -> pinned == null || pinned == it.group }
                }
            if (held == null) return@withLock signIn()
            instance = held.instance
            group = held.group
            held.value
        }

    /** Sends an authenticated request, signing in again once if the token was refused. */
    private suspend fun authorized(
        method: String,
        path: String,
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
        limit: Long = MAX_RESPONSE,
    ): Response {
        for (renew in listOf(false, true)) {
            val request =
                Request
                    .Builder()
                    .url(url(path))
                    .method(method, body)
                    .header("Authorization", "Bearer ${token(renew)}")
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()
            val response = send(request, limit)
            if (response.code != 401 || renew) return response
            response.close()
        }
        error("the loop returns on its second pass")
    }

    private suspend fun getBytes(path: String): ByteArray? =
        authorized("GET", path, limit = MAX_OBJECT_SIZE.toLong()).use { response ->
            if (response.code == 404) null else check(response).body.bytes()
        }

    /** Creates the group on an unclaimed server with the operator's admin token. */
    suspend fun claim(
        adminToken: String,
        genesis: Entry,
        wraps: Map<String, String>,
    ) {
        val body =
            buildJsonObject {
                put("entry", entryJson(genesis))
                put("wraps", wrapsJson(wraps))
            }
        send(admin(adminToken, "v1/claim").post(body.toString().toRequestBody(JSON)).build()).use {
            if (it.code == 409) throw LinkedException.ServerHasGroup() else adminChecked(it)
        }
    }

    /** Deletes every group object on the server, which becomes unclaimed (spec/api.md, `POST /v1/admin/reset`). */
    suspend fun resetServer(adminToken: String) {
        val body = buildJsonObject { put("confirm", "reset") }
        adminChecked(send(admin(adminToken, "v1/admin/reset").post(body.toString().toRequestBody(JSON)).build())).close()
    }

    /** A refused admin token, and the ban after repeated ones, said plainly (spec/api.md, Limits). */
    private fun adminChecked(response: Response): Response {
        val refusal =
            when (response.code) {
                401, 403 -> StoreException.AdminRefused()
                429 -> StoreException.AdminBlocked()
                else -> return check(response)
            }
        response.close()
        throw refusal
    }

    private fun admin(
        adminToken: String,
        path: String,
    ): Request.Builder = Request.Builder().url(url(path)).header("Authorization", "Bearer $adminToken")

    /** Asks to join with the admin token (spec/protocol.md, Joining with the admin token). */
    suspend fun requestJoin(
        adminToken: String,
        id: String,
        name: String,
    ) {
        val body =
            buildJsonObject {
                put("id", id)
                put("name", name)
            }
        adminChecked(send(admin(adminToken, "v1/join").post(body.toString().toRequestBody(JSON)).build())).close()
    }

    /** The code a member answered a join request with, or null until one does; a declined request throws. */
    suspend fun joinAnswer(
        adminToken: String,
        id: String,
    ): String? {
        val response = send(admin(adminToken, "v1/join/$id").build())
        if (response.code == 404) return null.also { response.close() }
        if (response.code == 410) throw LinkedException.JoinDeclined().also { response.close() }
        return decode(response)["code"]?.jsonPrimitive?.contentOrNull ?: throw StoreException.Malformed()
    }

    /** When the server last heard from each device, in ms, by device id. */
    suspend fun seen(): Map<String, Long> {
        val devices = decode(authorized("GET", "v1/status"))["devices"] as? JsonObject ?: throw StoreException.Malformed()
        return devices.mapValues { (_, device) ->
            ((device as? JsonObject)?.get("seen") as? JsonPrimitive)?.longOrNull
                ?: throw StoreException.Malformed()
        }
    }

    /**
     * Opens the live channel (spec/live.md, Server relay) after signing in afresh, pinging every 15
     * seconds so a dead connection fails instead of hanging.
     */
    suspend fun live(listener: WebSocketListener): WebSocket {
        val request =
            Request
                .Builder()
                .url(url("v1/live"))
                .header("Sec-WebSocket-Protocol", "flow-sync.v1, bearer.${token(renew = true)}")
                .build()
        return client
            .newBuilder()
            .pingInterval(LIVE_PING_S, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .addInterceptor(MessageLimit(MAX_LIVE_MESSAGE))
            .build()
            .newWebSocket(request, listener)
    }

    /** Join requests no member has answered yet. */
    suspend fun joinRequests(): List<JoinRequest> =
        try {
            Json.decodeFromJsonElement(
                ListSerializer(JoinRequest.serializer()),
                decode(authorized("GET", "v1/join"))["requests"] ?: throw StoreException.Malformed(),
            )
        } catch (_: SerializationException) {
            throw StoreException.Malformed()
        }

    suspend fun answerJoin(
        id: String,
        code: String,
    ) {
        check(authorized("PUT", "v1/join/$id", buildJsonObject { put("code", code) }.toString().toRequestBody(JSON))).close()
    }

    suspend fun declineJoin(id: String) {
        // 404: it expired; 409: another member answered or declined it first.
        authorized("DELETE", "v1/join/$id").use { if (it.code != 404 && it.code != 409) check(it) }
    }

    override suspend fun rosterAfter(after: Long): List<Entry> =
        decode(authorized("GET", "v1/roster?after=$after"))["entries"]
            ?.jsonArray
            ?.map(::parseEntry)
            ?: throw StoreException.Malformed()

    /** The whole roster, page by page. A page that doesn't advance, or one past a roster's limit, ends the read. */
    override suspend fun rosterView(): List<Entry> {
        val entries = ArrayList<Entry>()
        var after = -1L
        while (true) {
            val page = rosterAfter(after)
            val last = page.lastOrNull()?.seq ?: return entries
            if (last <= after) throw StoreException.Malformed()
            after = last
            entries += page
            if (entries.size > MAX_ROSTER_ENTRIES) throw StoreException.Malformed()
        }
    }

    override suspend fun appendRoster(
        head: String,
        entry: Entry,
        wraps: Map<String, String>,
        chain: String?,
    ): Appended {
        val body =
            buildJsonObject {
                put("entry", entryJson(entry))
                put("wraps", wrapsJson(wraps))
                chain?.let { put("chain", it) }
            }
        val response = authorized("POST", "v1/roster", body.toString().toRequestBody(JSON), mapOf("If-Match" to "\"$head\""))
        if (response.code == 409) {
            val current = decodeBody(response)["head"]?.jsonPrimitive?.contentOrNull ?: throw StoreException.Malformed()
            return Appended.Conflict(current)
        }
        check(response).close()
        return Appended.Done
    }

    /** The server names a wrap by epoch alone: only one entry can start an epoch there. */
    override suspend fun key(
        epoch: Int,
        kc: String,
        name: String,
    ): ByteArray? = getBytes("v1/keys/${epochName(epoch)}/$name.k")

    override suspend fun putObject(
        path: String,
        bytes: ByteArray,
    ) {
        check(authorized("PUT", "v1/objects/$path", bytes.toRequestBody(BYTES))).close()
    }

    override suspend fun getObject(path: String): ByteArray? = getBytes("v1/objects/$path")

    override suspend fun deleteObject(path: String) {
        authorized("DELETE", "v1/objects/$path").use { if (it.code != 404) check(it) }
    }

    override suspend fun changes(cursor: Long): Pair<List<ChangedObject>, Long> {
        val value = decode(authorized("GET", "v1/changes?after=$cursor"))
        val objects =
            try {
                Json.decodeFromJsonElement(ListSerializer(ChangedObject.serializer()), value["objects"] ?: throw StoreException.Malformed())
            } catch (_: SerializationException) {
                throw StoreException.Malformed()
            }
        val next = value["cursor"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: throw StoreException.Malformed()
        return objects to next
    }

    // Messages need no token: the new device has none yet, and only a holder of the QR code can derive the slot.
    override suspend fun openSlot(slot: String) {
        check(authorized("POST", "v1/pair", buildJsonObject { put("slot", slot) }.toString().toRequestBody(JSON))).close()
    }

    override suspend fun putMessage(
        slot: String,
        name: String,
        bytes: ByteArray,
    ) {
        check(
            send(
                Request
                    .Builder()
                    .url(url("v1/pair/$slot/$name"))
                    .put(bytes.toRequestBody(BYTES))
                    .build(),
            ),
        ).close()
    }

    override suspend fun getMessage(
        slot: String,
        name: String,
    ): ByteArray? =
        send(Request.Builder().url(url("v1/pair/$slot/$name")).build()).use { response ->
            if (response.code == 404) null else check(response).body.bytes()
        }

    override suspend fun closeSlot(slot: String) {
        check(authorized("DELETE", "v1/pair/$slot")).close()
    }
}

private fun check(response: Response): Response {
    if (response.isSuccessful) return response
    val code =
        response.use {
            runCatching {
                Json
                    .parseToJsonElement(it.body.string())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull()
        }
    throw StoreException.Refused(response.code, code ?: "unknown")
}

private fun decodeBody(response: Response): JsonObject =
    response.use {
        runCatching { Json.parseToJsonElement(it.body.string()).jsonObject }.getOrElse { throw StoreException.Malformed() }
    }

private fun wrapsJson(wraps: Map<String, String>): JsonObject = JsonObject(wraps.mapValues { (_, wrap) -> JsonPrimitive(wrap) })

private fun decode(response: Response): JsonObject = decodeBody(check(response))
