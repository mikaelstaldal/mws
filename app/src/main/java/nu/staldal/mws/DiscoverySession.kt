package nu.staldal.mws

import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Editor-owned document discovery. Call its methods on the main thread. The host must be an
 * attached, visible-sized window child. */
class DiscoverySession(
    private val host: FrameLayout,
    private val profiles: ProfileManager,
    private val scope: CoroutineScope,
    private val onDocument: (DiscoveryAttempt, DiscoveryDocumentResult) -> Unit,
    private val onOverallTimeout: (DiscoveryAttempt) -> Unit,
    private val onBlocked: (AttachBlock?) -> Unit,
    private val authEnabled: () -> Boolean = { false },
    private val myPass: () -> MyPassClient? = { null },
    private val onAuthenticationRequired: () -> Unit = {},
    private val onSnapshot: (DiscoveryAttempt, DiscoveryDomSnapshot) -> Unit = { _, _ -> },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val ownershipScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var profile: DisposableProfile? = null
    private var profileCreation: Deferred<DisposableProfile?>? = null
    private var bound: BoundWebView? = null
    private var progress: View? = null
    private var attempt: DiscoveryAttempt? = null
    @Volatile private var authentication: DiscoveryBasicAuthentication? = null
    fun basicAuth(id: Long): DiscoveryBasicAuth? = authentication?.takeIf { attempt?.id == id }

    var authenticationRequired = false
        private set
    private var activeUrl: String? = null
    private var generation = 0L
    private var launch: Job? = null
    private val pendingLaunches = mutableSetOf<Job>()
    private var debounce: Job? = null
    private var closed = false
    private val clock: () -> Long = SystemClock::elapsedRealtime
    private val timer = Runnable { checkDeadlines() }

    /** Invalidates the old attempt immediately, then starts after 500 ms of unchanged input. */
    fun urlChanged(text: String) {
        invalidate()
        val url = UrlPolicy.normalizeUserInput(text) ?: return
        debounce = scope.launch(Dispatchers.Main.immediate) {
            delay(500)
            start(url, DiscoveryTrigger.DEBOUNCE)
        }
    }

    fun focusLost(text: String) {
        debounce?.cancel()
        UrlPolicy.normalizeUserInput(text)?.let { url ->
            if (activeUrl != url.serialized || authenticationRequired) {
                start(url, DiscoveryTrigger.FOCUS_LOSS)
            }
        }
    }

    fun refresh(text: String) {
        debounce?.cancel()
        UrlPolicy.normalizeUserInput(text)?.let { start(it, DiscoveryTrigger.REFRESH) }
    }

    /** Save cancels loading without retiring the session profile; close() retires it. */
    fun cancel() = invalidate()

    fun pauseForMyPass(id: Long) {
        if (attempt?.id != id) return
        attempt?.pause(clock())
        handler.removeCallbacks(timer)
    }

    fun resumeFromMyPass(id: Long) {
        if (attempt?.id != id) return
        attempt?.resume(clock())
        scheduleTimer()
    }

    /** Called after the editor has finished later discovery stages. */
    fun finish(id: Long) {
        if (attempt?.id != id) return
        authentication?.close()
        authentication = null
        attempt?.finish()
        handler.removeCallbacks(timer)
        detach()
    }

    private fun start(url: UrlPolicy.HttpsUrl, trigger: DiscoveryTrigger) {
        invalidate()
        if (closed) return
        val id = generation
        activeUrl = url.serialized
        val job = scope.launch(Dispatchers.Main.immediate) {
            try {
                val selected = profileCreation ?: ownershipScope.async {
                    profiles.createDisposableProfile().also { profile = it }
                }.also { profileCreation = it }
                val selectedProfile = selected.await()
                if (selectedProfile == null) {
                    if (profileCreation === selected) profileCreation = null
                    if (id == generation && !closed) {
                        activeUrl = null
                        onBlocked(AttachBlock.STARTUP_PENDING)
                    }
                    return@launch
                }
                if (id != generation || closed) {
                    return@launch
                }
                val result = profiles.createDisposableWebView(host.context, selectedProfile)
                if (result is AttachResult.Blocked) {
                    if (id == generation && !closed) {
                        activeUrl = null
                        onBlocked(result.reason)
                    }
                    return@launch
                }
                val current = (result as AttachResult.Attached).bound
                if (id != generation || closed) { profiles.release(current); return@launch }
                bound = current
                val webView = current.webView
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    allowFileAccess = false
                    allowContentAccess = false
                }
                webView.webViewClient = client(id)
                host.addView(webView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                progress = FrameLayout(host.context).apply {
                    setBackgroundColor(Color.WHITE)
                    isClickable = true
                    addView(ProgressBar(host.context), FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER))
                }.also { host.addView(it, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
                awaitNonZeroLayout(webView)
                if (id != generation || closed) return@launch
                val state = DiscoveryAttempt(id, url, trigger)
                attempt = state
                authentication = DiscoveryBasicAuthentication(state, myPass,
                    { pauseForMyPass(id) }, { resumeFromMyPass(id) }, {
                        authenticationRequired = true
                        onAuthenticationRequired()
                    }, authEnabled).also { it.arm() }
                state.start(clock())
                scheduleTimer()
                webView.loadUrl(url.serialized)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (id == generation && !closed) {
                    if (profile == null && profileCreation?.isCompleted == true)
                        profileCreation = null
                    detach()
                    activeUrl = null
                    onBlocked(null)
                }
            }
        }
        launch = job
        pendingLaunches += job
        job.invokeOnCompletion { pendingLaunches -= job }
    }

    private fun client(id: Long) = object : WebViewClient() {
        private var mainFrameUrl: String? = null
        private val identity = DiscoveryDomIdentity()
        private val script by lazy { host.context.assets.open("discovery-dom.js").bufferedReader().use { it.readText() } }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (id != generation) return true
            if (UrlPolicy.parseHttps(request.url.toString()) != null) {
                if (request.isForMainFrame) authentication?.redirect(request.url.toString())
                return false
            }
            if (request.isForMainFrame) failDocument(id, DiscoveryDocumentFailure.HTTPS_REQUIRED)
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (id == generation) authentication?.intercepted(request.url.toString())
            val blocked = CleartextRequestBlocker.response(request.url) ?: return null
            if (request.isForMainFrame) handler.post {
                if (id == generation && bound?.webView === view)
                    failDocument(id, DiscoveryDocumentFailure.HTTPS_REQUIRED)
            }
            return blocked
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler,
            host: String?, realm: String?) {
            val auth = authentication?.takeIf { id == generation }
            if (auth == null) handler.cancel() else auth.challenge(handler.asAnswer(), host, realm)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            identity.started()
            mainFrameUrl = url
            if (id == generation) authentication?.redirect(url)
            if (id == generation && UrlPolicy.parseHttps(url) == null)
                failDocument(id, DiscoveryDocumentFailure.HTTPS_REQUIRED)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            val state = attempt?.takeIf { it.id == id } ?: return
            if (state.commit(url)) {
                authentication?.documentEnded()
                val document = identity.request()
                onDocument(state, checkNotNull(state.documentResult))
                if (document != null && state.documentResult?.finalUrl != null) extract(view, state, document)
                scheduleTimer()
            }
        }

        private fun extract(view: WebView, state: DiscoveryAttempt, document: Long) {
            if (id != generation || attempt !== state || bound?.webView !== view ||
                !identity.accepts(document) || state.remainingOverall(clock()) == null) return
            view.evaluateJavascript(script) { raw ->
                if (id != generation || attempt !== state || bound?.webView !== view ||
                    !identity.accepts(document) || state.remainingOverall(clock()) == null) return@evaluateJavascript
                if (raw == "\"mws-dom-pending\"") {
                    // DOM readiness may follow visible commit; bounded by the attempt deadline.
                    handler.postDelayed({ extract(view, state, document) }, 50)
                } else {
                    DiscoveryDom.parseEvaluation(raw)?.let { onSnapshot(state, it) }
                }
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest,
            error: WebResourceError?) {
            if (request.isForMainFrame) failDocument(id, if (error?.errorCode ==
                WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) DiscoveryDocumentFailure.TLS_ERROR
                else DiscoveryDocumentFailure.LOAD_ERROR)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError?) {
            handler.cancel()
            // This callback also covers subresources. Only the active main-frame URL can fail
            // the document; cancellation still rejects TLS errors on every other resource.
            if (error?.url == mainFrameUrl) failDocument(id, DiscoveryDocumentFailure.TLS_ERROR)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            failDocument(id)
            if (id == generation) detach()
            return true
        }
    }

    private fun failDocument(id: Long,
        failure: DiscoveryDocumentFailure = DiscoveryDocumentFailure.LOAD_ERROR) {
        val state = attempt?.takeIf { it.id == id } ?: return
        if (state.failDocument(failure)) {
            authentication?.documentEnded()
            bound?.webView?.stopLoading()
            onDocument(state, checkNotNull(state.documentResult))
            scheduleTimer()
        }
    }

    private fun checkDeadlines() {
        val state = attempt ?: return
        val now = clock()
        if ((state.remainingOverall(now) ?: Long.MAX_VALUE) <= 0) {
            if (state.documentResult == null) failDocument(state.id, DiscoveryDocumentFailure.TIMEOUT)
            bound?.webView?.stopLoading()
            authentication?.close()
            authentication = null
            state.finish()
            detach()
            onOverallTimeout(state)
        } else if ((state.remainingInitial(now) ?: Long.MAX_VALUE) <= 0) {
            failDocument(state.id, DiscoveryDocumentFailure.TIMEOUT)
        }
        scheduleTimer()
    }

    private fun scheduleTimer() {
        handler.removeCallbacks(timer)
        val state = attempt ?: return
        if (state.isPaused) return
        val now = clock()
        val delay = listOfNotNull(state.remainingInitial(now), state.remainingOverall(now))
            .minOrNull() ?: return
        handler.postDelayed(timer, delay.coerceAtLeast(0))
    }

    private fun invalidate() {
        authentication?.close()
        authentication = null
        authenticationRequired = false
        generation++
        debounce?.cancel()
        // A creation call already in progress must finish so its profile and WebView stay owned.
        // Once attached, cancellation is safe because detach() releases the bound WebView.
        if (bound != null) launch?.cancel()
        handler.removeCallbacks(timer)
        attempt?.finish()
        attempt = null
        activeUrl = null
        detach()
    }

    private fun detach() {
        removeProgress()
        bound?.let {
            it.webView.stopLoading()
            (it.webView.parent as? ViewGroup)?.removeView(it.webView)
            profiles.release(it)
        }
        bound = null
    }

    private fun removeProgress() {
        progress?.let(host::removeView)
        progress = null
    }

    /** Call while the editor is still alive; retirement is safe to retry at next startup. */
    suspend fun close() = withContext(Dispatchers.Main.immediate + NonCancellable) {
        if (closed) return@withContext
        closed = true
        invalidate()
        try {
            pendingLaunches.toList().joinAll()
            runCatching { profileCreation?.await() }
            profile?.let { profiles.retireDisposable(it) }
            profile = null
        } finally {
            ownershipScope.coroutineContext[Job]?.cancel()
        }
    }

    private suspend fun awaitNonZeroLayout(view: View) = suspendCancellableCoroutine<Unit> { cont ->
        val observer = view.viewTreeObserver
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (view.isAttachedToWindow && view.width > 0 && view.height > 0 && cont.isActive) {
                    observer.removeOnGlobalLayoutListener(this)
                    cont.resume(Unit)
                }
            }
        }
        observer.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        cont.invokeOnCancellation { observer.removeOnGlobalLayoutListener(listener) }
    }
}
