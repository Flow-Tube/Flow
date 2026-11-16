package io.github.aedev.flow.utils.cipher

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * This is based and ported from Metrolist,
 * see https://github.com/MetrolistGroup/Metrolist for the original code and license.
 *
 * WebView-based cipher executor for YouTube stream URL deobfuscation.
 * Executes signature decipher and n-transform functions extracted from player.js.
 */
class CipherWebView private constructor(
    context: Context,
    private val playerJs: String,
    private val sigInfo: FunctionNameExtractor.SigFunctionInfo?,
    private val nFuncInfo: FunctionNameExtractor.NFunctionInfo?,
    private val initContinuation: Continuation<CipherWebView>,
) {
    private val webView = WebView(context)

    // The bridge answers through one continuation, so calls run one at a time; the id keeps an
    // answer that arrives after its caller timed out from resuming the next caller.
    private val callMutex = Mutex()
    private var nextCallId = 0

    @Volatile private var pendingCall: Pair<Int, CancellableContinuation<String>>? = null

    private val initFinished = AtomicBoolean(false)

    @Volatile var isDestroyed: Boolean = false
        private set

    @Volatile var nFunctionAvailable: Boolean = false
        private set

    @Volatile var sigFunctionAvailable: Boolean = false
        private set

    @Volatile var discoveredNFuncName: String? = null
        private set

    @Volatile var usingHardcodedMode: Boolean = false
        private set

    init {
        Log.d(TAG, "Initializing CipherWebView: sig=${sigInfo?.name}, nFunc=${nFuncInfo?.name}[${nFuncInfo?.arrayIndex}]")
        val settings = webView.settings
        @Suppress("SetJavaScriptEnabled")
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        settings.blockNetworkLoads = true
        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    val msg = m.message()
                    when (m.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> {
                            if (!msg.contains("is not defined")) Log.e(TAG, "JS ERROR: $msg at ${m.sourceId()}:${m.lineNumber()}")
                        }

                        ConsoleMessage.MessageLevel.WARNING -> {
                            Log.w(TAG, "JS WARN: $msg")
                        }

                        else -> {
                            Log.v(TAG, "JS LOG: $msg")
                        }
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
                    Log.w(TAG, "Cipher renderer gone (crashed=${detail.didCrash()}), dropping this WebView")
                    release(rendererAlive = false)
                    val error = CipherException("Cipher WebView renderer gone")
                    finishInit(Result.failure(error))
                    pendingCall?.let { (id, _) -> takePendingCall(id)?.resumeWithException(error) }
                    return true
                }
            }
    }

    private fun finishInit(result: Result<CipherWebView>) {
        if (initFinished.compareAndSet(false, true)) initContinuation.resumeWith(result)
    }

    private fun loadPlayerJsFromFile() {
        val sigFuncName = sigInfo?.name
        val nFuncName = nFuncInfo?.name
        val nArrayIdx = nFuncInfo?.arrayIndex
        val isHardcoded = sigInfo?.isHardcoded == true || nFuncInfo?.isHardcoded == true

        Log.d(TAG, "Loading player.js into WebView: sig=$sigFuncName, nFunc=$nFuncName, hardcoded=$isHardcoded")
        usingHardcodedMode = isHardcoded

        val exports =
            buildList {
                sigInfo?.let { sig ->
                    val sigConstArgs = sig.constantArgs
                    val preprocessFunc = sig.preprocessFunc
                    val preprocessArgs = sig.preprocessArgs
                    if (!sigConstArgs.isNullOrEmpty() && preprocessFunc != null && !preprocessArgs.isNullOrEmpty()) {
                        val mainArgsStr = sigConstArgs.joinToString(", ")
                        val prepArgsStr = preprocessArgs.joinToString(", ")
                        add(
                            "window._cipherSigFunc = function(sig) { " +
                                "return $sigFuncName($mainArgsStr, $preprocessFunc($prepArgsStr, sig)); };",
                        )
                    } else if (!sigConstArgs.isNullOrEmpty()) {
                        val argsStr = sigConstArgs.joinToString(", ")
                        add("window._cipherSigFunc = function(sig) { return $sigFuncName($argsStr, sig); };")
                    } else if (isHardcoded) {
                        add("window._cipherSigFunc = typeof $sigFuncName !== 'undefined' ? $sigFuncName : null;")
                    } else {
                        add("window._cipherSigFunc = typeof $sigFuncName !== 'undefined' ? $sigFuncName : null;")
                    }
                }
                nFuncInfo?.let { nFunc ->
                    val nConstArgs = nFunc.constantArgs
                    if (nFunc.acceptsUrl) {
                        add("window._nUrlTransformFunc = typeof $nFuncName !== 'undefined' ? $nFuncName : null;")
                    } else if (!nConstArgs.isNullOrEmpty()) {
                        val argsStr = nConstArgs.joinToString(", ")
                        add("window._nTransformFunc = function(n) { return $nFuncName($argsStr, n); };")
                    } else {
                        val nExpr = if (nArrayIdx != null) "$nFuncName[$nArrayIdx]" else nFuncName
                        add("window._nTransformFunc = typeof $nFuncName !== 'undefined' ? $nExpr : null;")
                    }
                }
            }

        val modifiedJs =
            if (exports.isNotEmpty()) {
                val exportCode = "; " + exports.joinToString(" ")
                val modified = playerJs.replace("})(_yt_player);", "$exportCode })(_yt_player);")
                if (modified == playerJs) {
                    Log.w(TAG, "Export injection point not found, appending exports")
                    playerJs + "\n" + exportCode
                } else {
                    modified
                }
            } else {
                playerJs
            }

        val cacheDir = File(webView.context.cacheDir, "cipher")
        cacheDir.mkdirs()
        val playerJsFile = File(cacheDir, "player.js")
        playerJsFile.writeText(modifiedJs)
        Log.d(TAG, "player.js written to cache: ${modifiedJs.length} chars")

        val html = buildDiscoveryHtml()
        webView.loadDataWithBaseURL(
            "file://${cacheDir.absolutePath}/",
            html,
            "text/html",
            "utf-8",
            null,
        )
    }

    private fun buildDiscoveryHtml(): String =
        """<!DOCTYPE html>
<html><head><script>
function transformN(id, nValue) {
    try {
        var func = window._nTransformFunc;
        var result = null;
        if (typeof func === 'function') {
            result = func(nValue);
        } else if (typeof window._nUrlTransformFunc === 'function') {
            result = runNUrlTransform(nValue);
        } else {
            CipherBridge.onCallError(id, "N-transform func not available (n type: " + typeof func + ", url type: " + typeof window._nUrlTransformFunc + ")");
            return;
        }
        if (result === undefined || result === null) {
            CipherBridge.onCallError(id, "N-transform returned null/undefined");
            return;
        }
        CipherBridge.onCallResult(id, String(result));
    } catch (error) {
        CipherBridge.onCallError(id, error + "\n" + (error.stack || ""));
    }
}
function decipherSignature(id, sig) {
    try {
        var result = window._cipherSigFunc(sig);
        if (typeof result !== 'string' || result.length === 0) {
            CipherBridge.onCallError(id, "Signature function returned " + typeof result);
            return;
        }
        CipherBridge.onCallResult(id, result);
    } catch (error) {
        CipherBridge.onCallError(id, error + "\n" + (error.stack || ""));
    }
}
function isSignatureTransform(input, output) {
    return typeof output === 'string' && output !== input && output.length >= input.length - 20 && output.length <= input.length;
}
function runNUrlTransform(nValue) {
    var encoded = encodeURIComponent(nValue);
    var probeUrl = "https://rr1---sn.googlevideo.com/videoplayback/n/" + encoded + "/itag/18";
    var transformedUrl = window._nUrlTransformFunc(probeUrl);
    if (typeof transformedUrl !== 'string') {
        throw "N URL transform returned " + typeof transformedUrl;
    }
    var match = transformedUrl.match(/\/n\/([^\/]+)/);
    if (!match || !match[1]) {
        throw "N URL transform did not return an /n/ segment";
    }
    return decodeURIComponent(match[1]);
}
function discoverAndInit() {
    var nFuncName = "";
    var sigFuncName = "";
    var info = "";
    if (typeof window._cipherSigFunc === 'function') {
        try {
            var sigInput = "AOq0QJ8wRgIhAKhdt2X3VbN3nk_dS3K1yh0E2D9bY6dQjR0j8XyAq4uiAiEA2n4K3p2s0mZq4f5p0wq5yY8hF0d4yQ6kq1xT6bU0aGw=";
            if (isSignatureTransform(sigInput, window._cipherSigFunc(sigInput))) {
                sigFuncName = "exported_sig_func";
            } else {
                info = "sig_bad_result;";
                window._cipherSigFunc = null;
            }
        } catch(e) {
            info = "sig_threw:" + e + ";";
            window._cipherSigFunc = null;
        }
    }
    if (typeof window._nTransformFunc === 'function') {
        try {
            var testInput = "KdrqFlzJXl9EcCwlmEy";
            var testResult = window._nTransformFunc(testInput);
            if (typeof testResult === 'string' && testResult !== testInput && testResult.length >= 5 && /^[a-zA-Z0-9_-]+${"$"}/.test(testResult)) {
                nFuncName = "exported_n_func";
                info += "export_valid";
            } else {
                info += "export_bad_result";
                window._nTransformFunc = null;
            }
        } catch(e) {
            info += "export_threw:" + e;
            window._nTransformFunc = null;
        }
    }
    if (!nFuncName && typeof window._nUrlTransformFunc === 'function') {
        try {
            var testInputUrl = "T2Xw3pWQ_Wk0xbOg";
            var testResultUrl = runNUrlTransform(testInputUrl);
            if (typeof testResultUrl === 'string' && testResultUrl !== testInputUrl && testResultUrl.length >= 5 && /^[a-zA-Z0-9_-]+${"$"}/.test(testResultUrl)) {
                nFuncName = "exported_n_url_func";
                info += "export_url_valid";
            } else {
                info += "export_url_bad_result";
                window._nUrlTransformFunc = null;
            }
        } catch(e) {
            info += "export_url_threw:" + e;
            window._nUrlTransformFunc = null;
        }
    }
    if (!nFuncName) {
        try {
            var testInput = "T2Xw3pWQ_Wk0xbOg";
            var keys = Object.getOwnPropertyNames(window);
            var tested = 0;
            for (var i = 0; i < keys.length; i++) {
                try {
                    var key = keys[i];
                    if (key.startsWith("webkit") || key.startsWith("on") || key === "CipherBridge" || key === "_cipherSigFunc" || key === "_nTransformFunc" || key === "_nUrlTransformFunc" || key === "window" || key === "self") continue;
                    var fn = window[key];
                    if (typeof fn !== 'function' || fn.length !== 1) continue;
                    tested++;
                    var result = fn(testInput);
                    if (typeof result === 'string' && result !== testInput && result.length >= 5 && /^[a-zA-Z0-9_-]+${"$"}/.test(result)) {
                        window._nTransformFunc = fn;
                        nFuncName = key;
                        break;
                    }
                } catch(e) {}
            }
            info += "brute_force:tested=" + tested;
        } catch(e) {
            info += "brute_force_error:" + e;
        }
    }
    CipherBridge.onDiscoveryDone(sigFuncName, nFuncName, info);
    CipherBridge.onPlayerJsLoaded();
}
</script>
<script src="player.js"
    onload="discoverAndInit()"
    onerror="CipherBridge.onPlayerJsError('Failed to load player.js from file')">
</script>
</head><body></body></html>"""

    @JavascriptInterface
    fun logDebug(message: String) {
        Log.v(TAG, "JS: $message")
    }

    @JavascriptInterface
    fun onDiscoveryDone(
        sigFuncName: String,
        nFuncName: String,
        info: String,
    ) {
        Log.d(TAG, "Discovery: sig=${sigFuncName.ifEmpty { "NOT FOUND" }}, n=${nFuncName.ifEmpty { "NOT FOUND" }}, info=$info")
        sigFunctionAvailable = sigFuncName.isNotEmpty()
        if (nFuncName.isNotEmpty()) {
            discoveredNFuncName = nFuncName
            nFunctionAvailable = true
        } else {
            Log.e(TAG, "N-function NOT AVAILABLE")
            nFunctionAvailable = false
        }
    }

    @JavascriptInterface
    fun onPlayerJsLoaded() {
        Log.d(TAG, "Player.js loaded: sig=$sigFunctionAvailable, n=$nFunctionAvailable, nFunc=$discoveredNFuncName")
        finishInit(Result.success(this))
    }

    @JavascriptInterface
    fun onPlayerJsError(error: String) {
        Log.e(TAG, "Player.js load FAILED: $error")
        finishInit(Result.failure(CipherException("Player JS load failed: $error")))
    }

    suspend fun transformN(nValue: String): String {
        if (!nFunctionAvailable) throw CipherException("N-transform function not discovered")
        return call("transformN", nValue)
    }

    suspend fun decipherSignature(signature: String): String {
        if (!sigFunctionAvailable) throw CipherException("Signature function not discovered")
        return call("decipherSignature", signature)
    }

    private suspend fun call(
        function: String,
        argument: String,
    ): String =
        callMutex.withLock {
            val id = ++nextCallId
            withTimeoutOrNull(CALL_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        if (isDestroyed) {
                            cont.resumeWithException(CipherException("$function called on a destroyed CipherWebView"))
                            return@suspendCancellableCoroutine
                        }
                        pendingCall = id to cont
                        webView.evaluateJavascript("$function($id, '${escapeJsString(argument)}')", null)
                    }
                }
            } ?: throw CipherException("$function timed out")
        }

    @JavascriptInterface
    fun onCallResult(
        id: Int,
        result: String,
    ) {
        takePendingCall(id)?.resume(result)
    }

    @JavascriptInterface
    fun onCallError(
        id: Int,
        error: String,
    ) {
        Log.e(TAG, "Cipher call failed: $error")
        takePendingCall(id)?.resumeWithException(CipherException(error))
    }

    private fun takePendingCall(id: Int): CancellableContinuation<String>? {
        val (pendingId, cont) = pendingCall ?: return null
        if (pendingId != id) return null
        pendingCall = null
        return cont
    }

    fun close() {
        Log.d(TAG, "Closing CipherWebView...")
        release(rendererAlive = true)
    }

    private fun release(rendererAlive: Boolean) {
        if (isDestroyed) return
        isDestroyed = true
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

    private fun escapeJsString(s: String): String =
        s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    companion object {
        private const val TAG = "Flow_CipherWebView"
        private const val JS_INTERFACE = "CipherBridge"
        private const val CALL_TIMEOUT_MS = 5_000L

        suspend fun create(
            context: Context,
            playerJs: String,
            sigInfo: FunctionNameExtractor.SigFunctionInfo?,
            nFuncInfo: FunctionNameExtractor.NFunctionInfo? = null,
        ): CipherWebView {
            Log.d(TAG, "Creating CipherWebView: playerJs=${playerJs.length}, sig=${sigInfo?.name}, n=${nFuncInfo?.name}")
            return withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val wv = CipherWebView(context, playerJs, sigInfo, nFuncInfo, cont)
                    wv.loadPlayerJsFromFile()
                }
            }
        }
    }
}

class CipherException(
    message: String,
) : Exception(message)
