package io.github.aedev.flow.utils.cipher

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * This is based and ported from Metrolist,
 * see https://github.com/MetrolistGroup/Metrolist for the original code and license.
 *
 * Main cipher deobfuscation orchestrator for YouTube stream URLs.
 *
 * Handles both signature deobfuscation (for signatureCipher streams) and
 * n-parameter transformation (for throttle avoidance / 403 fix).
 */
object CipherDeobfuscator {
    private const val TAG = "Flow_CipherDeobfusc"

    lateinit var appContext: Context
        private set

    fun initialize(context: Context) {
        Log.d(TAG, "CipherDeobfuscator initializing...")
        appContext = context.applicationContext
        Log.d(TAG, "CipherDeobfuscator initialized")
    }

    private var cipherWebView: CipherWebView? = null
    private var currentPlayerHash: String? = null

    // Signature and n callers can arrive together (a song resolving during a video load); without
    // this each would build its own WebView around the same 3 MB script.
    private val webViewMutex = Mutex()

    @Volatile
    private var cachedSignatureTimestamp: Int? = null

    @Volatile
    private var lastAnalyzedHash: String? = null

    @Volatile
    private var refetchedForHash: String? = null

    /**
     * The player script whose signature and n-function the patterns in [FunctionNameExtractor]
     * could not find, once a fresh copy has been tried. Non-null means YouTube shipped a player
     * shape this build does not know — the single most useful thing a bug report can carry, and
     * not something the logs otherwise name.
     */
    @Volatile
    var unparseablePlayerHash: String? = null
        private set

    /**
     * The signature timestamp the WEB/MWEB `/player` request has to send. Cached after the first
     * read; the player script itself is cached by [PlayerJsFetcher].
     *
     * Reads the script and runs one regex rather than standing up the cipher WebView: the timestamp
     * is a plain number in the script and needs none of the machinery a decipher does. That also
     * makes it independent of signature extraction, which the current player defeats entirely —
     * the timestamp still resolves on players whose cipher cannot be read at all.
     */
    suspend fun ensureSignatureTimestamp(): Int? {
        cachedSignatureTimestamp?.let { return it }
        return try {
            val playerJs = PlayerJsFetcher.getPlayerJs()?.first ?: return null
            FunctionNameExtractor.extractSignatureTimestamp(playerJs)?.also { cachedSignatureTimestamp = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ensureSignatureTimestamp failed: ${e.message}")
            cachedSignatureTimestamp
        }
    }

    /**
     * [signature] run through the player script's own signature function, or null when the script
     * has none this build can find or the function failed.
     */
    suspend fun deobfuscateSignature(signature: String): String? =
        try {
            val webView = webViewMutex.withLock { getOrCreateWebView(forceRefresh = false) }
            webView?.takeIf { it.sigFunctionAvailable }?.decipherSignature(signature)?.takeIf { it != signature }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Signature decipher failed: ${e.message}")
            null
        }

    /** The hash of the player script whose signature timestamp the web `/player` requests carry. */
    suspend fun playerHash(): String? = PlayerJsFetcher.getPlayerJs()?.second

    fun invalidateSignatureTimestamp() {
        Log.d(TAG, "Invalidating signature timestamp")
        cachedSignatureTimestamp = null
    }

    /**
     * Transform the 'n' parameter in a streaming URL to avoid throttling/403.
     * Returns the URL with the transformed 'n' value, or the original URL if transform fails.
     *
     * IMPORTANT: Must be called for WEB_REMIX, WEB, WEB_CREATOR, TVHTML5 clients.
     */
    suspend fun transformNParamInUrl(url: String): String {
        Log.d(TAG, "transformNParamInUrl: url length=${url.length}")
        return try {
            transformNInternal(url)
        } catch (e: Exception) {
            Log.e(TAG, "N-transform failed, returning original URL: ${e.message}", e)
            url
        }
    }

    private suspend fun transformNInternal(url: String): String {
        val nMatch = Regex("[?&]n=([^&]+)").find(url)
        if (nMatch == null) {
            Log.d(TAG, "No 'n' parameter found in URL, skipping transform")
            return url
        }

        val nValueEncoded = nMatch.groupValues[1]
        val nValue = Uri.decode(nValueEncoded)
        Log.d(TAG, "N-param: encoded=$nValueEncoded, decoded=$nValue")

        val webView =
            webViewMutex.withLock { getOrCreateWebView(forceRefresh = false) } ?: run {
                Log.e(TAG, "Failed to get CipherWebView for n-transform")
                return url
            }

        if (!webView.nFunctionAvailable) {
            Log.e(TAG, "N-transform function was not discovered at init time")
            return url
        }

        val transformedN = webView.transformN(nValue)
        Log.d(TAG, "N-transform: $nValue -> $transformedN")

        return url.replaceFirst(
            Regex("([?&])n=[^&]+"),
            "$1n=${Uri.encode(transformedN)}",
        )
    }

    private suspend fun getOrCreateWebView(forceRefresh: Boolean): CipherWebView? {
        Log.d(TAG, "getOrCreateWebView: forceRefresh=$forceRefresh, existing=${cipherWebView != null}")

        if (!forceRefresh && cipherWebView?.isDestroyed == false) {
            return cipherWebView
        }

        if (cipherWebView != null) {
            closeWebView()
        }

        buildWebView(forceRefresh)?.let { return it }

        // A player script the extractors cannot read stays cached for six hours, so without this
        // every request in that window fails identically — the device goes a working day with no
        // signature and no n-transform. Re-fetch once per player hash: if YouTube really did ship
        // a shape the patterns miss, the second attempt costs one request and then stops.
        val failedHash = lastAnalyzedHash
        if (forceRefresh || failedHash == null || refetchedForHash == failedHash) return null
        refetchedForHash = failedHash
        Log.w(TAG, "Player JS $failedHash could not be analyzed — dropping the cached copy and refetching once")
        PlayerJsFetcher.invalidateCache()
        return buildWebView(forceRefresh = true)
    }

    private suspend fun buildWebView(forceRefresh: Boolean): CipherWebView? {
        val result = PlayerJsFetcher.getPlayerJs(forceRefresh = forceRefresh)
        if (result == null) {
            Log.e(TAG, "Failed to get player JS")
            return null
        }
        val (playerJs, hash) = result
        Log.d(TAG, "Got player JS: hash=$hash, length=${playerJs.length}")

        val analysis = FunctionNameExtractor.analyzePlayerJs(playerJs, knownHash = hash)
        cachedSignatureTimestamp = analysis.signatureTimestamp
        Log.d(TAG, "Extracted signatureTimestamp: $cachedSignatureTimestamp")
        lastAnalyzedHash = hash

        // Reported on a missing signature alone, not only when the n-function is missing too: the
        // signature is the capability that is actually lost, and a player whose indices are computed
        // at runtime cannot be read by any pattern, so the hash is the useful thing to report.
        if (analysis.sigInfo == null) {
            unparseablePlayerHash = hash
            val computed = FunctionNameExtractor.hasComputedArrayIndices(playerJs)
            Log.e(TAG, "No signature function in player JS (hash=$hash, computedArrayIndices=$computed)")
        } else {
            unparseablePlayerHash = null
        }

        if (analysis.sigInfo == null && analysis.nFuncInfo == null) {
            Log.e(TAG, "Could not extract signature or n-function info from player JS (hash=$hash)")
            return null
        }

        if (analysis.sigInfo == null) {
            Log.w(TAG, "Could not extract signature function info from player JS; n-transform may still work")
        }

        if (analysis.nFuncInfo == null) {
            Log.w(TAG, "Could not extract n-function info from player JS (will try brute-force)")
        }

        Log.d(TAG, "Creating CipherWebView: sig=${analysis.sigInfo?.name}, nFunc=${analysis.nFuncInfo?.name}")
        val webView =
            CipherWebView.create(
                context = appContext,
                playerJs = playerJs,
                sigInfo = analysis.sigInfo,
                nFuncInfo = analysis.nFuncInfo,
            )

        Log.d(TAG, "CipherWebView created: nAvailable=${webView.nFunctionAvailable}, sigAvailable=${webView.sigFunctionAvailable}")
        cipherWebView = webView
        currentPlayerHash = hash
        return webView
    }

    private suspend fun closeWebView() {
        withContext(Dispatchers.Main) {
            cipherWebView?.close()
        }
        cipherWebView = null
        currentPlayerHash = null
        Log.d(TAG, "CipherWebView closed")
    }
}
