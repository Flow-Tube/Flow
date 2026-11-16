package io.github.aedev.flow.data.scrobble

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** An app's identity on a Last.fm-compatible service. */
data class AudioscrobblerKeys(
    val apiKey: String,
    val secret: String,
) {
    val isUsable: Boolean get() = apiKey.isNotBlank() && secret.isNotBlank()
}

/** How a service takes the credentials for auth.getMobileSession. */
enum class MobileSessionAuth {
    /** Last.fm: the password itself, inside the signed request. */
    PASSWORD,

    /** Libre.fm (GNU FM): only an auth token derived from the user name and password. */
    AUTH_TOKEN,
}

/**
 * The Audioscrobbler 2.0 API that Last.fm and Libre.fm both speak: a mobile session from a user
 * name and password, now playing, and scrobbles in batches of up to [MAX_BATCH].
 */
class AudioscrobblerClient(
    private val baseUrl: String,
    private val http: () -> OkHttpClient,
    private val sessionAuth: MobileSessionAuth = MobileSessionAuth.PASSWORD,
) {
    suspend fun signIn(
        userName: String,
        password: String,
        keys: AudioscrobblerKeys,
    ): Result<ScrobbleAccount> =
        withContext(Dispatchers.IO) {
            runCatching {
                val credential =
                    when (sessionAuth) {
                        MobileSessionAuth.PASSWORD -> "password" to password
                        MobileSessionAuth.AUTH_TOKEN -> "authToken" to audioscrobblerAuthToken(userName, password)
                    }
                val body = post(mapOf("method" to "auth.getMobileSession", "username" to userName, credential), keys)
                body.errorCode()?.let { throw IOException(body.errorMessage() ?: "Error $it") }
                val session = body["session"]?.jsonObject ?: throw IOException("No session")
                ScrobbleAccount(
                    userName = session["name"]?.jsonPrimitive?.content ?: userName,
                    secret = session["key"]?.jsonPrimitive?.content ?: throw IOException("No session key"),
                )
            }
        }

    suspend fun nowPlaying(
        entry: ScrobbleEntry,
        account: ScrobbleAccount,
        keys: AudioscrobblerKeys,
    ): SendOutcome {
        val params =
            buildMap {
                put("method", "track.updateNowPlaying")
                put("artist", entry.artist)
                put("track", entry.title)
                if (entry.album.isNotBlank()) put("album", entry.album)
                if (entry.durationSec > 0) put("duration", entry.durationSec.toString())
                put("sk", account.secret)
            }
        return send(params, keys)
    }

    suspend fun scrobble(
        entries: List<ScrobbleEntry>,
        account: ScrobbleAccount,
        keys: AudioscrobblerKeys,
    ): SendOutcome {
        val params =
            buildMap {
                put("method", "track.scrobble")
                put("sk", account.secret)
                entries.take(MAX_BATCH).forEachIndexed { index, entry ->
                    put("artist[$index]", entry.artist)
                    put("track[$index]", entry.title)
                    put("timestamp[$index]", entry.timestampSec.toString())
                    if (entry.album.isNotBlank()) put("album[$index]", entry.album)
                    if (entry.durationSec > 0) put("duration[$index]", entry.durationSec.toString())
                }
            }
        return send(params, keys)
    }

    suspend fun setLoved(
        love: LoveEntry,
        account: ScrobbleAccount,
        keys: AudioscrobblerKeys,
    ): SendOutcome =
        send(
            mapOf(
                "method" to if (love.loved) "track.love" else "track.unlove",
                "artist" to love.artist,
                "track" to love.title,
                "sk" to account.secret,
            ),
            keys,
        )

    /** The user's most played artists over the past year, most played first. */
    suspend fun topArtists(
        userName: String,
        keys: AudioscrobblerKeys,
        limit: Int,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url =
                    baseUrl
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("method", "user.getTopArtists")
                        .addQueryParameter("user", userName)
                        .addQueryParameter("period", "12month")
                        .addQueryParameter("limit", limit.toString())
                        .addQueryParameter("api_key", keys.apiKey)
                        .addQueryParameter("format", "json")
                        .build()
                val body =
                    read(
                        Request
                            .Builder()
                            .url(url)
                            .get()
                            .build(),
                    )
                body.errorCode()?.let { throw IOException(body.errorMessage() ?: "Error $it") }
                body["topartists"]
                    ?.jsonObject
                    ?.get("artist")
                    ?.jsonArray
                    ?.mapNotNull {
                        it.jsonObject["name"]
                            ?.jsonPrimitive
                            ?.content
                            ?.takeIf(String::isNotBlank)
                    }.orEmpty()
            }
        }

    /** Songs Last.fm listeners play alongside [artist]'s [title], closest first. */
    suspend fun similarTracks(
        artist: String,
        title: String,
        keys: AudioscrobblerKeys,
        limit: Int,
    ): Result<List<Pair<String, String>>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url =
                    baseUrl
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("method", "track.getSimilar")
                        .addQueryParameter("artist", artist)
                        .addQueryParameter("track", title)
                        .addQueryParameter("autocorrect", "1")
                        .addQueryParameter("limit", limit.toString())
                        .addQueryParameter("api_key", keys.apiKey)
                        .addQueryParameter("format", "json")
                        .build()
                val body =
                    read(
                        Request
                            .Builder()
                            .url(url)
                            .get()
                            .build(),
                    )
                body.errorCode()?.let { throw IOException(body.errorMessage() ?: "Error $it") }
                body["similartracks"]
                    ?.jsonObject
                    ?.get("track")
                    ?.jsonArray
                    ?.mapNotNull { element ->
                        val track = element.jsonObject
                        val name = track["name"]?.jsonPrimitive?.content
                        val by =
                            track["artist"]
                                ?.jsonObject
                                ?.get("name")
                                ?.jsonPrimitive
                                ?.content
                        if (name.isNullOrBlank() || by.isNullOrBlank()) null else by to name
                    }.orEmpty()
            }
        }

    private suspend fun send(
        params: Map<String, String>,
        keys: AudioscrobblerKeys,
    ): SendOutcome =
        withContext(Dispatchers.IO) {
            try {
                outcomeOf(post(params, keys).errorCode())
            } catch (e: IOException) {
                Log.w(TAG, "${params["method"]} failed: ${e.message}")
                SendOutcome.Retry
            }
        }

    private fun post(
        params: Map<String, String>,
        keys: AudioscrobblerKeys,
    ): JsonObject {
        val signed = params + ("api_key" to keys.apiKey)
        val form = FormBody.Builder()
        signed.forEach { (name, value) -> form.add(name, value) }
        form.add("api_sig", audioscrobblerSignature(signed, keys.secret))
        form.add("format", "json")
        return read(
            Request
                .Builder()
                .url(baseUrl)
                .post(form.build())
                .build(),
        )
    }

    private fun read(request: Request): JsonObject =
        http().newCall(request).execute().use { response ->
            val text = response.body.string()
            if (text.isBlank()) throw IOException("HTTP ${response.code}")
            runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException("HTTP ${response.code}") }
        }

    private fun JsonObject.errorCode(): Int? = this["error"]?.jsonPrimitive?.int

    private fun JsonObject.errorMessage(): String? = this["message"]?.jsonPrimitive?.content

    companion object {
        const val MAX_BATCH = 50
        const val LASTFM_URL = "https://ws.audioscrobbler.com/2.0/"
        const val LIBREFM_URL = "https://libre.fm/2.0/"
        private const val TAG = "Audioscrobbler"

        /** Libre.fm registers no apps and takes any key; these only have to be 32 characters. */
        val LibreFmKeys = AudioscrobblerKeys(apiKey = "f10wf10wf10wf10wf10wf10wf10wf10w", secret = "f10wf10wf10wf10wf10wf10wf10wf10w")
    }
}

/** GNU FM's token: MD5 of the lower-cased user name followed by the hex MD5 of the password. */
internal fun audioscrobblerAuthToken(
    userName: String,
    password: String,
): String = md5Hex(userName.lowercase(Locale.ROOT) + md5Hex(password))

/** The documented api_sig: every parameter but format and callback, sorted, joined, then the secret, MD5'd. */
internal fun audioscrobblerSignature(
    params: Map<String, String>,
    secret: String,
): String {
    val base =
        params
            .filterKeys { it != "format" && it != "callback" }
            .toSortedMap()
            .entries
            .joinToString("") { it.key + it.value } + secret
    return md5Hex(base)
}

private fun md5Hex(text: String): String =
    MessageDigest
        .getInstance("MD5")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Session and key errors sign the account out; outages and rate limits retry; anything else drops the batch. */
internal fun outcomeOf(errorCode: Int?): SendOutcome =
    when (errorCode) {
        null -> SendOutcome.Sent
        4, 9, 10, 26 -> SendOutcome.SignedOut
        8, 11, 16, 29 -> SendOutcome.Retry
        else -> SendOutcome.Sent
    }
