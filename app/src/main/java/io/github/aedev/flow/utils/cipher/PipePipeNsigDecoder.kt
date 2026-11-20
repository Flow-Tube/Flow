package io.github.aedev.flow.utils.cipher

import android.content.Context
import android.util.Log
import io.github.aedev.flow.network.ProxyAwareClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Remote `n` (throttling) parameter and signature deobfuscation via PipePipe's public decoder API
 * (https://api.pipepipe.dev/decoder), using PipePipe's exact protocol.
 *
 * Signatures for the TV client that serves a walled visitor (#921) are solved here, against the
 * decoder's own latest player. Web signatures come here only when the local player script's
 * signature function cannot be found (as on `ecb23058`, 2026-09-22), and name the local player.
 *
 * For `n`, this is the fallback after the in-app [CipherDeobfuscator]. Every call here is a round trip to a third-party host
 * sitting on the path to first frame, so three properties are load-bearing:
 *
 *  - the player id survives process death, because a fresh process otherwise refetches it before
 *    the first video of the session can resolve a single URL;
 *  - the HTTP client is reused, so calls keep the connection alive instead of paying a fresh
 *    DNS + TCP + TLS handshake each time (a per-call `OkHttpClient` has its own empty pool);
 *  - requests suspend rather than block and are cancellable, so abandoning a video stops the work
 *    instead of letting it run on and compete with the media buffer that replaced it.
 */
object PipePipeNsigDecoder {
    private const val TAG = "PipePipeNsig"
    private const val LATEST_PLAYER_URL = "https://api.pipepipe.dev/decoder/latest-player"
    private const val DECODE_URL = "https://api.pipepipe.dev/decoder/decode"
    private const val USER_AGENT = "PipePipe/4.9.0"
    private const val PLAYER_TTL_MS = 24L * 60L * 60L * 1000L

    private const val PREFS_NAME = "flow_prefs"
    private const val KEY_PLAYER_ID = "nsig_player_id"
    private const val KEY_PLAYER_STS = "nsig_player_sts"
    private const val KEY_PLAYER_EXPIRY_MS = "nsig_player_expiry_ms"

    // Decoded n params accumulate for every stream URL of every video played, so the map is
    // bounded rather than left to grow for the life of the process.
    private const val N_CACHE_MAX_ENTRIES = 512

    private val N_PARAM_REGEX = Regex("([?&])n=([^&]+)")

    private val nCache: MutableMap<String, String> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > N_CACHE_MAX_ENTRIES
            },
        )

    private val sigCache: MutableMap<String, String> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > N_CACHE_MAX_ENTRIES
            },
        )

    private val playerIdMutex = Mutex()

    @Volatile private var appContext: Context? = null

    @Volatile private var playerId: String? = null

    @Volatile private var playerIdExpiryMs = 0L

    @Volatile private var cachedSignatureTimestamp: Int? = null

    @Volatile private var restoredFromDisk = false

    private val http = ProxyAwareClient { connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS) }

    /** Stores the application context used to persist the player id. Does no I/O. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Resolves the player id ahead of playback. Call from a background scope at startup so the
     * first video of a session does not pay this round trip on its critical path.
     */
    suspend fun warmUp() {
        runCatching { ensurePlayerId() }
            .onFailure { Log.w(TAG, "warm-up failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private suspend fun Call.await(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        continuation.resume(response)
                    }

                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isCancelled) return
                        continuation.resumeWithException(e)
                    }
                },
            )
        }

    private suspend fun get(url: String): String? {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
        return http.get().newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for ${url.substringBefore('?')}")
                null
            } else {
                response.body.string()
            }
        }
    }

    private fun restorePersistedPlayerId() {
        if (restoredFromDisk) return
        restoredFromDisk = true
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val expiry = prefs.getLong(KEY_PLAYER_EXPIRY_MS, 0L)
        if (expiry <= System.currentTimeMillis()) return
        val id = prefs.getString(KEY_PLAYER_ID, null)?.takeIf { it.isNotEmpty() } ?: return
        playerId = id
        playerIdExpiryMs = expiry
        cachedSignatureTimestamp = prefs.getInt(KEY_PLAYER_STS, 0).takeIf { it != 0 }
        Log.d(TAG, "restored player id from disk: id=$id sts=$cachedSignatureTimestamp")
    }

    private fun persistPlayerId(
        id: String,
        expiryMs: Long,
        sts: Int?,
    ) {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs
            .edit()
            .putString(KEY_PLAYER_ID, id)
            .putLong(KEY_PLAYER_EXPIRY_MS, expiryMs)
            .putInt(KEY_PLAYER_STS, sts ?: 0)
            .apply()
    }

    private suspend fun ensurePlayerId(): String? =
        withContext(Dispatchers.IO) {
            restorePersistedPlayerId()
            playerId?.let { if (System.currentTimeMillis() < playerIdExpiryMs) return@withContext it }
            playerIdMutex.withLock {
                // A concurrent caller may have refreshed it while this one waited for the lock.
                playerId?.let { if (System.currentTimeMillis() < playerIdExpiryMs) return@withLock it }
                try {
                    val body = get(LATEST_PLAYER_URL) ?: return@withLock null
                    val json = JSONObject(body)
                    val id = json.optString("player").takeIf { it.isNotEmpty() } ?: return@withLock null
                    val sts = json.optInt("signatureTimestamp").takeIf { it != 0 }
                    val expiry = System.currentTimeMillis() + PLAYER_TTL_MS
                    cachedSignatureTimestamp = sts
                    playerId = id
                    playerIdExpiryMs = expiry
                    persistPlayerId(id, expiry, sts)
                    Log.w(TAG, "latest-player ok: id=$id sts=$sts")
                    id
                } catch (e: Exception) {
                    Log.w(TAG, "latest-player fetch failed: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }
        }

    internal fun rawN(url: String): String? =
        N_PARAM_REGEX.find(url)?.groupValues?.get(2)?.let {
            try {
                URLDecoder.decode(it, "UTF-8")
            } catch (e: Exception) {
                it
            }
        }

    /** Swaps the `n` parameter for [decodedN], leaving the rest of the query untouched. */
    internal fun replaceNParam(
        url: String,
        decodedN: String,
    ): String = url.replaceFirst(N_PARAM_REGEX, "$1n=${URLEncoder.encode(decodedN, "UTF-8")}")

    /**
     * Decodes every distinct n param in [urls] in one request.
     *
     * Only worth calling once both local decoders have been shown to fail for the video in hand —
     * priming unconditionally puts a third-party round trip in front of every extraction.
     */
    suspend fun prefetch(urls: List<String>) {
        val pid = ensurePlayerId() ?: return
        val ns = urls.mapNotNull { rawN(it) }.distinct().filter { nCache["$pid:$it"] == null }
        if (ns.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                val joined = ns.joinToString(",") { URLEncoder.encode(it, "UTF-8") }
                val data = parseData(get("$DECODE_URL?player=$pid&n=$joined")) ?: return@withContext
                var ok = 0
                for (n in ns) {
                    val decoded = data.optString(n)
                    if (decoded.isNotEmpty()) {
                        nCache["$pid:$n"] = decoded
                        ok++
                    }
                }
                Log.w(TAG, "batch decoded $ok/${ns.size} n params")
            } catch (e: Exception) {
                Log.w(TAG, "batch decode failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private suspend fun decodeN(n: String): String? {
        val pid = ensurePlayerId() ?: return null
        nCache["$pid:$n"]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(n, "UTF-8")
                val data = parseData(get("$DECODE_URL?player=$pid&n=$encoded")) ?: return@withContext null
                val decoded = data.optString(n).takeIf { it.isNotEmpty() } ?: return@withContext null
                nCache["$pid:$n"] = decoded
                decoded
            } catch (e: Exception) {
                Log.w(TAG, "decode failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    private fun parseData(body: String?): JSONObject? {
        if (body.isNullOrEmpty()) return null
        return try {
            JSONObject(body).getJSONArray("responses").getJSONObject(0).getJSONObject("data")
        } catch (e: Exception) {
            Log.w(TAG, "unexpected response shape: ${e.message}")
            null
        }
    }

    /**
     * The signature timestamp of the player the decoder solves against. A client whose formats
     * are signed must request /player with this one, or its signatures will not match.
     */
    suspend fun signatureTimestamp(): Int? {
        ensurePlayerId() ?: return null
        return cachedSignatureTimestamp
    }

    /**
     * Solves every distinct signature in [signatures] in one request; unsolved ones are absent.
     *
     * @param playerId the player the signatures were issued for. Defaults to the decoder's latest,
     *   which is only right for a request that sent [signatureTimestamp].
     */
    suspend fun decodeSignatures(
        signatures: Collection<String>,
        playerId: String? = null,
    ): Map<String, String> {
        val pid = playerId ?: ensurePlayerId() ?: return emptyMap()
        val wanted = signatures.distinct()
        val missing = wanted.filter { sigCache["$pid:$it"] == null }
        if (missing.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                try {
                    val data = parseData(get(signatureRequestUrl(pid, missing))) ?: return@withContext
                    for (signature in missing) {
                        data.optString(signature).takeIf { it.isNotEmpty() }?.let { sigCache["$pid:$signature"] = it }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "signature decode failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        return wanted.mapNotNull { signature -> sigCache["$pid:$signature"]?.let { signature to it } }.toMap()
    }

    internal fun signatureRequestUrl(
        playerId: String,
        signatures: Collection<String>,
    ): String = "$DECODE_URL?player=$playerId&sig=" + signatures.joinToString(",") { URLEncoder.encode(it, "UTF-8") }

    suspend fun deobfuscateUrl(url: String): String? {
        val n = rawN(url) ?: return null
        val decoded = decodeN(n) ?: return null
        if (decoded == n) return null
        return replaceNParam(url, decoded)
    }
}
