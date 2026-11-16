package io.github.aedev.flow.utils.potoken

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.network.AppProxyManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * This is based and ported from Metrolist,
 * see https://github.com/MetrolistGroup/Metrolist for the original code and license.
 */

class PoTokenWebView private constructor(
    context: Context,
) {
    private val webView = WebView(context)
    private val scope = MainScope()
    private val poTokenContinuations =
        Collections.synchronizedMap(HashMap<String, Continuation<String>>())
    private val exceptionHandler =
        CoroutineExceptionHandler { _, t ->
            onAttestationError(t)
        }
    private lateinit var expirationInstant: Instant

    // Resumed exactly once per [attest] run. Errors arriving with no attestation in flight only
    // fail pending mints, and a console error after a successful attestation must not re-resume it.
    private val attestationContinuation = AtomicReference<Continuation<Unit>?>(null)

    // Reloading the page cannot cancel an already-dispatched BotGuard HTTP request, so each
    // attestation is stamped and late responses from a superseded run are dropped rather than
    // evaluated against the page that replaced them.
    private val attestationGeneration = AtomicInteger(0)

    @Volatile
    private var broken = false

    @Volatile
    var isDestroyed = false
        private set

    //region Initialization
    init {
        val webViewSettings = webView.settings
        webViewSettings.javaScriptEnabled = true
        webViewSettings.userAgentString = USER_AGENT
        webViewSettings.blockNetworkLoads = true

        webView.addJavascriptInterface(this, JS_INTERFACE)

        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    val msg = m.message()
                    // Log all console messages for debugging
                    when (m.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, "JS: $msg")
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "JS: $msg")
                        else -> Log.d(TAG, "JS: $msg")
                    }

                    if (msg.contains("Uncaught")) {
                        val fmt = "\"$msg\", source: ${m.sourceId()} (${m.lineNumber()})"
                        val exception = BadWebViewException(fmt)
                        Log.e(TAG, "Uncaught JS error in BotGuard WebView: $fmt")

                        broken = true
                        // No-ops when nothing is attesting, so a post-attestation error only fails
                        // in-flight mints and leaves isExpired to trigger the next re-attestation.
                        failAttestation(exception)
                        popAllPoTokenContinuations().forEach { (_, cont) -> cont.resumeWithException(exception) }
                    }
                    return super.onConsoleMessage(m)
                }
            }

        // Unhandled, a renderer crash or the system reclaiming it for memory kills the whole app.
        webView.webViewClient =
            object : WebViewClient() {
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    Log.w(TAG, "BotGuard renderer gone (crashed=${detail.didCrash()}), dropping this WebView")
                    release(rendererAlive = false)
                    val error = PoTokenException("BotGuard WebView renderer gone")
                    failAttestation(error)
                    popAllPoTokenContinuations().forEach { (_, cont) -> cont.resumeWithException(error) }
                    return true
                }
            }
    }

    /**
     * Runs a full BotGuard attestation — load the page, run BotGuard, obtain an `integrityToken`,
     * create the minter — and suspends until it completes.
     *
     * Safe to call repeatedly on the same instance. Reloading the page through
     * [loadHtmlAndObtainBotguard] replaces the JS context wholesale, so a re-attestation starts as
     * clean as a brand-new instance would, without paying for WebView construction and teardown on
     * the main thread. That matters because attestation is re-run often: a low-trust token is never
     * pinned, and every expiry, session change or 403 recovery needs a fresh one.
     */
    suspend fun attest() {
        check(!isDestroyed) { "attest() called on a destroyed PoTokenWebView" }
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                attestationGeneration.incrementAndGet()
                broken = false
                attestationContinuation.set(cont)
                cont.invokeOnCancellation { attestationContinuation.compareAndSet(cont, null) }
                loadHtmlAndObtainBotguard()
            }
        }
    }

    private fun resumeAttestation() {
        attestationContinuation.getAndSet(null)?.resume(Unit)
    }

    private fun failAttestation(error: Throwable) {
        attestationContinuation.getAndSet(null)?.resumeWithException(error)
    }

    /**
     * Asynchronously goes through all the steps needed to load BotGuard, run it, and obtain an
     * `integrityToken`. Driven by [attest], which is what callers should use.
     */
    private fun loadHtmlAndObtainBotguard() {
        Log.d(TAG, "loadHtmlAndObtainBotguard() called")

        scope.launch(exceptionHandler) {
            val html =
                withContext(Dispatchers.IO) {
                    webView.context.assets
                        .open("po_token.html")
                        .bufferedReader()
                        .use { it.readText() }
                }

            // calls downloadAndRunBotguard() when the page has finished loading
            val data = html.replaceFirst("</script>", "\n$JS_INTERFACE.downloadAndRunBotguard()</script>")
            webView.loadDataWithBaseURL("https://www.youtube.com", data, "text/html", "utf-8", null)
        }
    }

    /**
     * Called during initialization by the JavaScript snippet appended to the HTML page content in
     * [loadHtmlAndObtainBotguard] after the WebView content has been loaded.
     */
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        Log.d(TAG, "downloadAndRunBotguard() called")

        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/Create",
            "[ \"$REQUEST_KEY\" ]",
        ) { responseBody ->
            val parsedChallengeData = parseChallengeData(responseBody)
            webView.evaluateJavascript(
                """try {
                    data = $parsedChallengeData
                    runBotGuard(data).then(function (result) {
                        this.webPoSignalOutput = result.webPoSignalOutput
                        $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                    }, function (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    })
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                }""",
                null,
            )
        }
    }

    /**
     * Called during initialization by the JavaScript snippets from either
     * [downloadAndRunBotguard] or [onRunBotguardResult].
     */
    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "Initialization error from JavaScript: $error")
        }
        onAttestationError(buildExceptionForJsError(error))
    }

    /**
     * Called during initialization by the JavaScript snippet from [downloadAndRunBotguard] after
     * obtaining the BotGuard execution output [botguardResponse].
     */
    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        Log.d(TAG, "botguardResponse: $botguardResponse")
        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/GenerateIT",
            "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
        ) { responseBody ->
            Log.d(TAG, "GenerateIT response: $responseBody")
            try {
                val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(responseBody)
                Log.d(TAG, "Parsed integrityToken (${integrityToken.take(50)}...), expires in $expirationTimeInSeconds sec")

                // leave 10 minutes of margin just to be sure
                expirationInstant = Instant.now().plusSeconds(expirationTimeInSeconds).minus(10, ChronoUnit.MINUTES)

                // Store integrityToken and create the minter callback ONCE
                Log.d(TAG, "Evaluating createPoTokenMinter JavaScript...")
                webView.evaluateJavascript(
                    """try {
                        console.log('[JS] Setting integrityToken and calling createPoTokenMinter...');
                        this.integrityToken = $integrityToken
                        console.log('[JS] integrityToken set, now calling createPoTokenMinter...');
                        createPoTokenMinter(webPoSignalOutput, integrityToken).then(function() {
                            console.log('[JS] createPoTokenMinter .then() resolved!');
                            $JS_INTERFACE.onMinterCreated()
                        }).catch(function(error) {
                            console.log('[JS] createPoTokenMinter .catch() error: ' + error);
                            $JS_INTERFACE.onJsInitializationError(error + "\n" + (error.stack || ''))
                        })
                    } catch (error) {
                        console.log('[JS] createPoTokenMinter SYNC error: ' + error);
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    }""",
                    null,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse integrity token data: ${e.message}", e)
                onAttestationError(PoTokenException("parseIntegrityTokenData failed: ${e.message}"))
            }
        }
    }

    /**
     * Called during initialization after the poToken minter has been created successfully.
     */
    @JavascriptInterface
    fun onMinterCreated() {
        Log.d(TAG, "poToken minter created successfully, attestation complete")
        resumeAttestation()
    }

    //region Obtaining poTokens
    suspend fun generatePoToken(identifier: String): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                Log.d(TAG, "generatePoToken() called with identifier $identifier")
                if (isDestroyed) {
                    cont.resumeWithException(PoTokenException("generatePoToken() called on a destroyed PoTokenWebView"))
                    return@suspendCancellableCoroutine
                }
                addPoTokenEmitter(identifier, cont)
                cont.invokeOnCancellation { popPoTokenContinuation(identifier) }
                webView.evaluateJavascript(
                    """try {
                        identifier = "$identifier"
                        u8Identifier = ${stringToU8(identifier)}
                        obtainPoToken(u8Identifier).then(function(poTokenU8) {
                            poTokenU8String = poTokenU8.join(",")
                            $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                        }).catch(function(error) {
                            $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + (error.stack || ''))
                        })
                    } catch (error) {
                        $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                    }""",
                    null,
                )
            }
        }

    /**
     * Called by the JavaScript snippet from [generatePoToken] when an error occurs in calling the
     * JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenError(
        identifier: String,
        error: String,
    ) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "obtainPoToken error from JavaScript: $error")
        }
        popPoTokenContinuation(identifier)?.resumeWithException(buildExceptionForJsError(error))
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] with the original identifier and the
     * result of the JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenResult(
        identifier: String,
        poTokenU8: String,
    ) {
        Log.d(TAG, "Generated poToken (before decoding): identifier=$identifier poTokenU8=$poTokenU8")
        val poToken =
            try {
                u8ToBase64(poTokenU8)
            } catch (t: Throwable) {
                popPoTokenContinuation(identifier)?.resumeWithException(t)
                return
            }

        Log.d(TAG, "Generated poToken: identifier=$identifier poToken=$poToken")
        popPoTokenContinuation(identifier)?.resume(poToken)
    }

    val isExpired: Boolean
        get() =
            broken ||
                !::expirationInstant.isInitialized ||
                Instant.now().isAfter(expirationInstant)

    //region Handling multiple emitters
    private fun addPoTokenEmitter(
        identifier: String,
        continuation: Continuation<String>,
    ) {
        poTokenContinuations[identifier] = continuation
    }

    private fun popPoTokenContinuation(identifier: String): Continuation<String>? = poTokenContinuations.remove(identifier)

    private fun popAllPoTokenContinuations(): Map<String, Continuation<String>> {
        val result = poTokenContinuations.toMap()
        poTokenContinuations.clear()
        return result
    }

    //region Utils
    private fun makeBotguardServiceRequest(
        url: String,
        data: String,
        handleResponseBody: (String) -> Unit,
    ) {
        val generation = attestationGeneration.get()
        scope.launch(exceptionHandler) {
            val requestBuilder =
                okhttp3.Request
                    .Builder()
                    .post(data.toRequestBody())
                    .headers(
                        mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "application/json",
                            "Content-Type" to "application/json+protobuf",
                            "x-goog-api-key" to GOOGLE_API_KEY,
                            "x-user-agent" to "grpc-web-javascript/0.1",
                        ).toHeaders(),
                    ).url(url)
            val response =
                withContext(Dispatchers.IO) {
                    httpClient.newCall(requestBuilder.build()).execute()
                }
            val httpCode = response.code
            if (httpCode != 200) {
                onAttestationError(PoTokenException("Invalid response code: $httpCode"))
            } else {
                val body =
                    withContext(Dispatchers.IO) {
                        response.body!!.string()
                    }
                if (generation != attestationGeneration.get()) {
                    Log.d(TAG, "Dropping BotGuard response from a superseded attestation")
                    return@launch
                }
                handleResponseBody(body)
            }
        }
    }

    /**
     * Marks the attestation failed without tearing the WebView down, so the instance stays
     * reusable and [attest] can simply be run again on it. Only [close] destroys the WebView.
     */
    private fun onAttestationError(error: Throwable) {
        broken = true
        failAttestation(error)
    }

    @MainThread
    fun close() = release(rendererAlive = true)

    @MainThread
    private fun release(rendererAlive: Boolean) {
        if (isDestroyed) return
        isDestroyed = true
        broken = true
        scope.cancel()

        // Once its renderer is gone a WebView may only be destroyed, never loaded or cleared.
        if (rendererAlive) {
            webView.clearHistory()
            webView.clearCache(true)
            webView.loadUrl("about:blank")
            webView.onPause()
        }
        webView.removeAllViews()
        webView.destroy()
    }

    companion object {
        private const val TAG = "PoTokenWebView"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"

        private val httpClient: OkHttpClient
            get() = AppProxyManager.applyTo(OkHttpClient.Builder()).build()

        /**
         * Builds a WebView and runs its first attestation. Callers should hold on to the result
         * and [attest] it again rather than building another one — see [attest].
         */
        suspend fun create(context: Context): PoTokenWebView {
            val instance = withContext(Dispatchers.Main) { PoTokenWebView(context) }
            try {
                instance.attest()
            } catch (t: Throwable) {
                withContext(NonCancellable + Dispatchers.Main) { instance.close() }
                throw t
            }
            return instance
        }
    }
}
