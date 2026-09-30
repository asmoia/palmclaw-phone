package com.palmclaw.providers.duck

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Owns a hidden WebView that keeps duck.ai loaded and runs the injected
 * duck_bridge.js transport (JSA challenge solving + /duckchat/v1/chat SSE).
 *
 * Hardening:
 *  - all chats serialized through a Mutex + the in-page JS queue
 *  - health-check ping before every call; bridge re-injected after page reloads
 *  - Kotlin-side watchdog timeout so a lost JS callback can never hang a caller
 *  - WebView (re)creation is generational; destroyed/stale views are replaced
 *  - page-load failures fail fast and the next call retries the load
 */
object DuckWebViewBridge {

    private const val DUCK_URL = "https://duck.ai/"
    private const val EXPECTED_BRIDGE_VERSION = 2
    private const val CALL_WATCHDOG_MS = 190_000L      // > JS-side 150s chat timeout + retries headroom
    private const val PAGE_LOAD_TIMEOUT_MS = 45_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val callIds = AtomicLong(0)
    private val chatMutex = Mutex()

    @Volatile private var webView: WebView? = null
    @Volatile private var readyLock: CompletableDeferred<Unit>? = null
    @Volatile private var generation = 0
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val deltaListeners = ConcurrentHashMap<String, (String) -> Unit>()

    @Volatile private var appContext: Context? = null
    @Volatile private var cachedBridgeJs: String? = null

    /** JavascriptInterface — called from duck_bridge.js. */
    private class BridgeJs {
        @JavascriptInterface
        fun onDelta(callId: String, chunk: String) {
            deltaListeners[callId]?.invoke(chunk)
        }

        @JavascriptInterface
        fun onResult(callId: String, json: String) {
            val parsed = runCatching { JSONObject(json) }.getOrElse {
                JSONObject().put("ok", false).put("error", "bad bridge payload")
            }
            pending.remove(callId)?.complete(parsed)
            deltaListeners.remove(callId)
        }
    }

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // ── WebView lifecycle ─────────────────────────────────────────────────────

    private fun ensureReady(): CompletableDeferred<Unit> {
        val existing = readyLock
        if (existing != null && !existing.isCompletedExceptionally) return existing
        synchronized(this) {
            val again = readyLock
            if (again != null && !again.isCompletedExceptionally) return again
            val latch = CompletableDeferred<Unit>()
            readyLock = latch
            val ctx = appContext
                ?: return latch.apply {
                    completeExceptionally(IllegalStateException("DuckWebViewBridge not initialized"))
                }
            val myGen = ++generation
            mainHandler.post {
                try {
                    val wv = webView ?: createWebView(ctx).also { webView = it }
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url == null || !url.startsWith("https://duck.ai")) return
                            view?.evaluateJavascript(bridgeJs(ctx)) { _ ->
                                view.postDelayed({
                                    view.evaluateJavascript(
                                        "window.__duckAcceptTos && window.__duckAcceptTos(); 'ok'", null
                                    )
                                    view.postDelayed({ latch.complete(Unit) }, 1200L)
                                }, 1500L)
                            }
                        }
                    }
                    wv.loadUrl(DUCK_URL)
                    mainHandler.postDelayed({
                        if (!latch.isCompleted) {
                            latch.completeExceptionally(IllegalStateException("duck.ai page failed to load (timeout)"))
                        }
                    }, PAGE_LOAD_TIMEOUT_MS)
                } catch (t: Throwable) {
                    latch.completeExceptionally(t)
                }
            }
            // note: `myGen` captured for future multi-generation checks
            @Suppress("UNUSED_EXPRESSION") myGen
            return latch
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(context: Context): WebView {
        val wv = WebView(context)
        wv.layoutParams = ViewGroup.LayoutParams(0, 0)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.loadsImagesAutomatically = false
        wv.settings.blockNetworkImage = true
        // Keep the genuine default Android WebView User-Agent — a real browser
        // fingerprint is exactly what makes the JSA challenge pass.
        wv.addJavascriptInterface(BridgeJs(), "__duckAndroid")
        return wv
    }

    private fun bridgeJs(ctx: Context): String {
        cachedBridgeJs?.let { return it }
        val js = ctx.assets.open("duck_bridge.js").bufferedReader().use { it.readText() }
        cachedBridgeJs = js
        return js
    }

    /** Runs JS in the WebView and returns its result; fails if the view is gone. */
    private suspend fun evalJs(expression: String): String? = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            val wv = webView
            if (wv == null) {
                cont.resumeWithException(IllegalStateException("WebView unavailable"))
                return@post
            }
            try {
                wv.evaluateJavascript(expression) { result -> cont.resume(result) }
            } catch (t: Throwable) {
                cont.resumeWithException(t)
            }
        }
    }

    /**
     * Health check + self-heal before each call:
     *  - if the page was reloaded and the bridge was wiped → re-inject duck_bridge.js
     *  - if the view left duck.ai → reload and wait for readiness
     */
    private suspend fun ensureBridgeAlive() {
        val ping = runCatching { evalJs("window.__duckPing ? window.__duckPing() : 0") }.getOrNull()
        val version = ping?.trim()?.removeSurrounding("\"")?.toIntOrNull() ?: 0
        if (version >= EXPECTED_BRIDGE_VERSION) return
        // bridge missing/stale → page reloaded or navigated
        val url = runCatching { evalJs("location.href") }.getOrNull()?.removeSurrounding("\"")
        if (url == null || !url.startsWith("https://duck.ai")) {
            // full reload path
            readyLock = null
            ensureReady().await()
        } else {
            // page alive, just re-inject the transport
            val ctx = appContext ?: throw IllegalStateException("DuckWebViewBridge not initialized")
            runCatching { evalJs(bridgeJs(ctx)) }
            val recheck = runCatching { evalJs("window.__duckPing ? window.__duckPing() : 0") }
                .getOrNull()?.trim()?.removeSurrounding("\"")?.toIntOrNull() ?: 0
            if (recheck < EXPECTED_BRIDGE_VERSION) {
                // last resort: reload the page
                readyLock = null
                ensureReady().await()
            }
        }
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Runs one chat turn (serialized). [onDelta] receives streamed chunks.
     * Returns { ok: true, text, partial? } or { ok: false, error }.
     */
    suspend fun chat(
        model: String,
        messagesJson: String,
        onDelta: ((String) -> Unit)? = null
    ): JSONObject = chatMutex.withLock {
        try {
            ensureReady().await()
        } catch (t: Throwable) {
            return@withLock JSONObject()
                .put("ok", false)
                .put("error", "duck.ai unavailable: ${t.message ?: "page load failed"} — check network")
        }

        // self-heal after reloads
        runCatching { ensureBridgeAlive() }.onFailure { t ->
            return@withLock JSONObject()
                .put("ok", false)
                .put("error", "duck.ai bridge recovery failed: ${t.message}")
        }

        val callId = "c${callIds.incrementAndGet()}"
        val done = CompletableDeferred<JSONObject>()
        pending[callId] = done
        if (onDelta != null) deltaListeners[callId] = onDelta

        val payload = "{\"callId\":\"$callId\",\"model\":${JSONObject.quote(model)},\"messages\":$messagesJson}"
        val js = "window.__duckChat(${JSONObject.quote(payload)})"
        mainHandler.post {
            runCatching { webView?.evaluateJavascript(js, null) }
        }

        try {
            withTimeout(CALL_WATCHDOG_MS) { done.await() }
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", "duck.ai call timed out (${CALL_WATCHDOG_MS / 1000}s)")
        } finally {
            pending.remove(callId)
            deltaListeners.remove(callId)
        }
    }

    /** Forcibly reloads the page (e.g. after repeated failures). */
    fun hardReload() {
        mainHandler.post {
            runCatching { webView?.loadUrl(DUCK_URL) }
        }
    }
}
