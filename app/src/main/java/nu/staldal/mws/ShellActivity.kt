package nu.staldal.mws

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextMenu
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chromeless host for one configured app (A§9.1). It is launched only by stable app ID carried as
 * a document data URI, so each app gets its own Recents task, and a relaunch brings the existing
 * task forward through `onNewIntent` without touching the live page.
 */
class ShellActivity : AppCompatActivity(), ShellHolder.Host {
    private var holder: ShellHolder? = null
    internal lateinit var webPermissions: WebPermissions
    internal lateinit var myPass: MyPassClient
    internal lateinit var handoff: ShellHandoff
    private lateinit var container: FrameLayout
    private var taskDescriptionJob: Job? = null
    private lateinit var presentation: ShellPresentation
    private var closing = false
    private var overlay: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webPermissions = WebPermissions(this, savedInstanceState) { holder?.record }
        myPass = MyPassClient(this)
        myPass.restoreIdentity(savedInstanceState)
        handoff = ShellHandoff(this)
        val appId = appIdOf(intent)
        if (appId == null) {
            finishAndRemoveTask()
            return
        }
        container = FrameLayout(this).apply { id = R.id.shell_container }
        setContentView(container)
        presentation = ShellPresentation(this, container)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (holder?.dismissError() == true) return
                val webView = holder?.bound?.webView
                if (webView != null && webView.canGoBack()) webView.goBack() else finishAndRemoveTask()
            }
        })
        val services = ShellServices.shared(this)
        // Saved instance state is ignored: a holder survives only configuration changes, and after
        // process death a fresh holder loads the resolved launch URL (A§9.2).
        holder = ViewModelProvider(this, ShellHolder.Factory(services, appId, applicationContext))
            .get(ShellHolder::class.java)
        holder?.attach(this)
        renderOverlay()
    }

    override fun onResume() {
        super.onResume()
        holder?.record?.let(::onRecord)
    }

    override fun onDestroy() {
        taskDescriptionJob?.cancel()
        webPermissions.dispose()
        handoff.close()
        myPass.close()
        holder?.detach(this)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webPermissions.saveState(outState)
        myPass.saveIdentity(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onReady(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        container.removeAllViews()
        overlay = null
        holder?.record?.let(presentation::apply)
        if (holder?.hasVisiblePaint != true) webView.setBackgroundColor(presentation.backgroundColor)
        webView.webChromeClient = webPermissions
        registerForContextMenu(webView)
        container.addView(webView, FrameLayout.LayoutParams(-1, -1))
        renderOverlay()
    }

    override fun onPresentationChanged() = renderOverlay()

    override fun onNotice(message: Int) = handoff.notice(message)

    override fun onExternal(decision: NavigationDecision.Confirm) = handoff.confirmExternal(decision)

    override fun onCreateContextMenu(menu: ContextMenu, view: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, view, menuInfo)
        if (view is WebView && view === holder?.bound?.webView) handoff.populateContextMenu(menu, view)
    }

    private fun renderOverlay() {
        overlay?.let(container::removeView)
        overlay = null
        val owner = holder ?: return
        val error = owner.error
        val view: View = if (error != null) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.WHITE)
                contentDescription = getString(error)
                addView(TextView(this@ShellActivity).apply {
                    text = getString(error)
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setPadding(24, 24, 24, 24)
                })
                addView(Button(this@ShellActivity).apply {
                    id = R.id.shell_retry_button
                    setText(R.string.shell_retry)
                    isEnabled = !owner.recovering
                    setOnClickListener { owner.retry() }
                })
                addView(Button(this@ShellActivity).apply {
                    id = R.id.shell_close_button
                    setText(R.string.shell_close)
                    setOnClickListener { finishAndRemoveTask() }
                })
            }
        } else if (owner.loading) {
            FrameLayout(this).apply {
                contentDescription = getString(R.string.shell_loading)
                addView(ProgressBar(this@ShellActivity), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
        } else return
        overlay = view
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.announceForAccessibility(view.contentDescription)
    }

    override fun onRecord(app: ConfiguredApp) {
        presentation.apply(app)
        taskDescriptionJob?.cancel()
        taskDescriptionJob = lifecycleScope.launch {
            val size = IconPipeline.SIZES.firstOrNull {
                it >= resources.displayMetrics.density * 48
            } ?: IconPipeline.SIZES.last()
            val icon = withContext(Dispatchers.IO) {
                ShellServices.shared(this@ShellActivity).icons.load(app.iconReference, size)
            } ?: ShellServices.shared(this@ShellActivity).icons.defaultIcon().toBitmap(size, size)
            setTaskDescription(taskDescription(app, icon, presentation.taskColor))
        }
    }

    override fun onClosed(reason: CloseReason) {
        if (closing) return
        closing = true
        when (reason) {
            CloseReason.INCOMPATIBLE -> startActivity(Intent(this, ProviderErrorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CloseReason.CLEARING -> toast(R.string.clearing_explanation)
            CloseReason.UNAVAILABLE -> toast(R.string.app_unavailable)
            CloseReason.FAILED -> toast(R.string.shell_start_failed)
            CloseReason.RETIRED -> {}
        }
        finishAndRemoveTask()
    }

    private fun toast(message: Int) = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()

    companion object {
        const val SCHEME = "mws-app"
        const val ACTION_OPEN = "nu.staldal.mws.action.OPEN_APP"

        /** The only way to open a shell: the data URI identifies the app and its document task. */
        fun intent(context: Context, appId: String): Intent {
            require(ProfileNames.isOpaqueId(appId)) { "App ID is not opaque" }
            return Intent(context, ShellActivity::class.java)
                .setAction(ACTION_OPEN)
                .setData(Uri.parse("$SCHEME:$appId"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }

        /** Accepts exactly `mws-app:<opaque ID>`; anything else, including a URL, is rejected. */
        fun appIdOf(intent: Intent?): String? {
            if (intent?.action != ACTION_OPEN) return null
            val data = intent.dataString ?: return null
            if (!data.startsWith("$SCHEME:")) return null
            return data.removePrefix("$SCHEME:").takeIf(ProfileNames::isOpaqueId)
        }

        /** Recents uses the same opaque contrast-selected color as the status bar (A§9.3). */
        @Suppress("DEPRECATION")
        fun taskDescription(app: ConfiguredApp, icon: Bitmap, color: Int =
            ShellColors.bar(app.themeColor, Color.WHITE, Color.BLACK).color): ActivityManager.TaskDescription =
            ActivityManager.TaskDescription(app.name, icon,
                color)
    }
}

enum class CloseReason { INCOMPATIBLE, CLEARING, UNAVAILABLE, FAILED, RETIRED }

/**
 * Lifecycle-aware owner of the shell's WebView, per A§9.2. It survives configuration changes, so the
 * live page is kept; it never persists WebView state. It registers the app's close hook before
 * attaching the profile, so Clear, Remove and origin changes always reach it (A§5.1).
 */
class ShellHolder(
    private val services: ShellServices,
    val appId: String,
    private val application: Context,
) : ViewModel() {
    interface Host {
        fun onReady(webView: WebView)
        fun onRecord(app: ConfiguredApp)
        fun onPresentationChanged()
        /** Shows a non-fatal message over the page. */
        fun onNotice(message: Int)
        /** Asks before delegating an allowlisted external scheme. */
        fun onExternal(decision: NavigationDecision.Confirm)
        /** Finish the activity and its task. */
        fun onClosed(reason: CloseReason)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** WebView context; points at the attached activity while there is one. */
    private val context = MutableContextWrapper(application)
    private var host: Host? = null
    private var started = false
    private var unregister: (() -> Unit)? = null
    private var appliedThirdPartyCookies: Boolean? = null
    /** Set once the launch URL is loading; record changes are applied from then on. */
    private var ready = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timer: Runnable? = null
    private var timerDeadline = 0L
    private var timerRemaining = 30_000L
    private var timerPaused = false
    private var navigationIdentity = 0L
    private var initialNavigation = true
    private var failedUrl: String? = null
    private var failedGet = false
    private var currentUrl: String? = null
    private var currentGet = false
    private var requestObserved = false
    /** Whether the current initial load's document has started; before that nothing can be kept. */
    private var initialDocumentStarted = false
    private var lastSafeGet: String? = null
    private var lastCommittedUrl: String? = null
    private val knownSafeGets = mutableSetOf<String>()
    private var formFill: FormFillEngine? = null
    private var formClient: MyPassClient? = null
    private var rendererGone = false
    var recovering = false
        private set
    var loading = false
        private set
    var error: Int? = null
        private set

    var hasVisiblePaint = false
        private set

    var bound: BoundWebView? = null
        private set
    var record: ConfiguredApp? = null
        private set
    var closed: CloseReason? = null
        private set

    /** Main thread only. */
    fun attach(host: Host) {
        this.host = host
        context.baseContext = host as Context
        closed?.let {
            host.onClosed(it)
            return
        }
        record?.let(host::onRecord)
        bound?.let { host.onReady(it.webView) }
        host.onPresentationChanged()
        start()
    }

    /** Main thread only. The WebView stays alive for the next activity. */
    fun detach(host: Host) {
        if (this.host !== host) return
        (host as? ShellActivity)?.webPermissions?.close()
        bound?.webView?.webChromeClient = ShellChromeClient()
        bound?.webView?.setOnCreateContextMenuListener(null)
        formFill?.rejectDocument()
        this.host = null
        bound?.webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        context.baseContext = application
    }

    private fun start() {
        if (started) return
        started = true
        scope.launch {
            services.repository.observe(appId).catch { }.collect(::onRecordChanged)
        }
        scope.launch {
            val result = try {
                services.awaitStartup()
                // Registered before attaching, so a concurrent Clear or Remove always reaches it.
                unregister = services.shells.register(appId) { close(CloseReason.RETIRED) }
                services.profiles.createAppWebView(context, appId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                close(CloseReason.FAILED)
                return@launch
            }
            when (result) {
                is AttachResult.Attached -> onAttached(result.bound)
                is AttachResult.Blocked -> close(when (result.reason) {
                    AttachBlock.INCOMPATIBLE -> CloseReason.INCOMPATIBLE
                    AttachBlock.CLEARING -> CloseReason.CLEARING
                    AttachBlock.MISSING_APP, AttachBlock.NOT_REGISTERED -> CloseReason.UNAVAILABLE
                    AttachBlock.STARTUP_PENDING, AttachBlock.PENDING_OPERATION -> CloseReason.FAILED
                })
            }
        }
    }

    private suspend fun onAttached(attached: BoundWebView) {
        if (closed != null) {
            services.profiles.release(attached)
            return
        }
        hasVisiblePaint = false
        bound = attached
        val app = try {
            services.repository.get(appId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (closed != null) return
        if (app == null || !isCurrent(app)) return close(CloseReason.UNAVAILABLE)
        record = app
        // Records are validated on save; this guards the one navigation this task performs.
        if (!app.launchUrl.startsWith("https://")) return close(CloseReason.FAILED)
        // No suspension from here on: nothing can close the shell before the load starts, and
        // [app] is at least as new as any record observed so far.
        val webView = attached.webView
        ShellWebSettings.apply(webView, debuggable = BuildConfig.DEBUG)
        applyThirdPartyCookies(app.allowThirdPartyCookies)
        if (closed != null) return
        ready = true
        installFormFill(webView)
        installClient(webView)
        host?.onReady(webView)
        load(webView, app.launchUrl, initial = true)
    }

    private fun installFormFill(webView: WebView) {
        formFill?.close()
        formFill = FormFillEngine(webView,
            { services.compatibility.current().features.formFill && closed == null && error == null },
            { document, callback ->
                val client = (host as? ShellActivity)?.myPass
                formClient = client
                if (client == null) callback(MyPassClient.Outcome.Unavailable) else {
                    val identity = MyPassClient.Identity(appId, document.epoch, document.nonce)
                    val launched = client.fetch(identity, document.url, isCurrent = { formFill?.isCurrent(document) == true }) { outcome ->
                        try { callback(outcome) } finally { formClient = null }
                    }
                    if (!launched && formFill?.isCurrent(document) == true) {
                        formClient = null
                        callback(MyPassClient.Outcome.NoCredential)
                    }
                }
            },
            { formClient?.cancel(); formClient = null })
    }

    /** Before attachment, the attach result decides; afterwards a stale record closes the shell. */
    private fun onRecordChanged(app: ConfiguredApp?) {
        if (closed != null) return
        if (bound != null && (app == null || !isCurrent(app))) return close(CloseReason.RETIRED)
        if (app == null) return
        record = app
        if (ready) applyThirdPartyCookies(app.allowThirdPartyCookies)
        host?.onRecord(app)
    }

    private fun isCurrent(app: ConfiguredApp): Boolean = app.profileState == ProfileState.ACTIVE &&
        bound?.profileName == ProfileNames.app(app.id, app.profileGeneration)

    /** A§5.1: applies to later requests of the live page, without a reload. */
    private fun applyThirdPartyCookies(allow: Boolean) {
        val attached = bound ?: return
        if (appliedThirdPartyCookies == allow) return
        appliedThirdPartyCookies = allow
        services.profiles.applyThirdPartyCookies(attached, allow)
    }

    /** The close hook: the WebView is destroyed and the task finished before this returns. */
    private fun close(reason: CloseReason) {
        if (closed != null) return
        closed = reason
        stopTimer()
        unregister?.invoke()
        unregister = null
        release()
        host?.onClosed(reason)
    }

    private fun release() {
        val attached = bound ?: return
        (host as? ShellActivity)?.webPermissions?.close()
        formFill?.close()
        formFill = null
        bound = null
        (attached.webView.parent as? ViewGroup)?.removeView(attached.webView)
        services.profiles.release(attached)
    }

    override fun onCleared() {
        stopTimer()
        scope.cancel()
        unregister?.invoke()
        unregister = null
        release()
    }

    /** Called by the credential flow while an external MyPass request is outstanding. */
    fun pauseInitialTimer() {
        if (timer == null || timerPaused) return
        timerRemaining = (timerDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
        stopTimer()
        timerPaused = true
    }

    fun resumeInitialTimer() {
        if (!timerPaused) return
        timerPaused = false
        if (initialNavigation && error == null) startTimer(navigationIdentity)
    }

    private fun startTimer(identity: Long) {
        timerDeadline = SystemClock.uptimeMillis() + timerRemaining
        val callback = Runnable {
            timer = null
            if (identity != navigationIdentity || closed != null || error != null) return@Runnable
            bound?.webView?.stopLoading()
            fail(R.string.shell_timeout)
        }
        timer = callback
        timerHandler.postDelayed(callback, timerRemaining)
    }

    private fun stopTimer() {
        timer?.let(timerHandler::removeCallbacks)
        timer = null
    }

    private fun load(webView: WebView, url: String, initial: Boolean) {
        navigationIdentity++
        currentUrl = url
        currentGet = true
        requestObserved = true
        failedUrl = null
        failedGet = false
        error = null
        rendererGone = false
        initialNavigation = initial
        initialDocumentStarted = false
        loading = initial
        stopTimer()
        timerRemaining = 30_000L
        timerPaused = false
        host?.onPresentationChanged()
        if (initial) startTimer(navigationIdentity)
        formFill?.navigate(url)
        webView.loadUrl(url)
    }

    private fun installClient(webView: WebView) {
        webView.setDownloadListener { _, _, _, _, _ ->
            if (bound?.webView === webView && closed == null) notice(R.string.shell_download_unsupported)
        }
        webView.webViewClient = ShellWebViewClient(
            onIntercepted = { decision ->
                if (bound?.webView === webView && closed == null) when (decision) {
                    is NavigationDecision.Confirm -> host?.onExternal(decision)
                    is NavigationDecision.Block -> decision.message?.let(::notice)
                    NavigationDecision.Stay -> {}
                }
            },
            onRequest = { url, get ->
                if (bound?.webView === webView) {
                    (host as? ShellActivity)?.webPermissions?.close()
                    formFill?.navigate(url)
                    currentUrl = url
                    currentGet = get
                    requestObserved = true
                }
            },
            onStarted = { url ->
                if (bound?.webView === webView && error == null) {
                    (host as? ShellActivity)?.webPermissions?.close()
                    formFill?.started(url)
                    initialDocumentStarted = true
                    if (!requestObserved && !initialNavigation) currentGet = false
                    currentUrl = url
                    requestObserved = false
                }
            },
            onCommit = { url ->
                if (bound?.webView === webView && error == null) {
                    hasVisiblePaint = true
                    webView.setBackgroundColor(Color.WHITE)
                    stopTimer()
                    initialNavigation = false
                    loading = false
                    if (url.startsWith("https://", true)) {
                        if (url == currentUrl && webView.url == url) formFill?.commit(url)
                        lastCommittedUrl = url
                        if (currentGet) knownSafeGets += url
                        if (url in knownSafeGets) lastSafeGet = url
                    }
                    requestObserved = false
                    host?.onPresentationChanged()
                }
            },
            onFailure = { message -> if (bound?.webView === webView) fail(message) },
            onTlsFailure = { url ->
                if (bound?.webView === webView) {
                    formFill?.rejectDocument()
                    if (url == currentUrl) fail(R.string.shell_tls_error)
                }
            },
            onRendererGone = { rendererFailed(webView) },
        )
    }

    /**
     * A cancelled navigation or refused download keeps the current page. When it replaces the
     * initial load itself (a redirect or download before its document started) there is no page
     * to keep, so the error screen is shown instead.
     */
    private fun notice(message: Int) {
        if (initialNavigation && !initialDocumentStarted && error == null) {
            bound?.webView?.stopLoading()
            fail(message)
        } else host?.onNotice(message)
    }

    private fun fail(message: Int) {
        if (closed != null || error != null) return
        (host as? ShellActivity)?.webPermissions?.close()
        formFill?.rejectDocument()
        stopTimer()
        loading = false
        failedUrl = currentUrl
        failedGet = currentGet
        error = message
        host?.onPresentationChanged()
    }

    fun dismissError(): Boolean {
        if (error == null || rendererGone || lastSafeGet == null) return false
        error = null
        bound?.webView?.let { webView ->
            if (webView.url != null && webView.url != lastSafeGet) {
                if (webView.canGoBack()) webView.goBack() else webView.loadUrl(lastSafeGet!!)
            }
        }
        host?.onPresentationChanged()
        return true
    }

    fun retry() {
        if (error == null || closed != null || recovering) return
        val launch = record?.launchUrl ?: return
        val target = when {
            rendererGone -> lastCommittedUrl ?: launch
            initialNavigation -> launch
            failedGet && failedUrl?.startsWith("https://", true) == true -> failedUrl!!
            else -> lastSafeGet ?: launch
        }
        if (rendererGone || initialNavigation) {
            recovering = true
            host?.onPresentationChanged()
            scope.launch {
                try {
                    val old = bound
                    val result = services.profiles.createAppWebView(context, appId)
                    if (result is AttachResult.Blocked) {
                        when (result.reason) {
                            AttachBlock.CLEARING, AttachBlock.MISSING_APP -> close(CloseReason.RETIRED)
                            AttachBlock.INCOMPATIBLE -> close(CloseReason.INCOMPATIBLE)
                            else -> showRecoveryFailure()
                        }
                        return@launch
                    }
                    val replacement = (result as AttachResult.Attached).bound
                    if (closed != null || record?.let {
                        it.profileState == ProfileState.ACTIVE &&
                            replacement.profileName == ProfileNames.app(it.id, it.profileGeneration)
                    } != true) {
                        services.profiles.release(replacement)
                        return@launch
                    }
                    formFill?.close()
                    formFill = null
                    bound = replacement
                    old?.let {
                        (it.webView.parent as? ViewGroup)?.removeView(it.webView)
                        services.profiles.release(it)
                    }
                    ShellWebSettings.apply(replacement.webView, BuildConfig.DEBUG)
                    appliedThirdPartyCookies = null
                    applyThirdPartyCookies(record!!.allowThirdPartyCookies)
                    installFormFill(replacement.webView)
                    installClient(replacement.webView)
                    host?.onReady(replacement.webView)
                    load(replacement.webView, target, initial = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    showRecoveryFailure()
                } finally {
                    recovering = false
                    host?.onPresentationChanged()
                }
            }
        } else bound?.webView?.let { load(it, target, initial = initialNavigation) }
    }

    private fun showRecoveryFailure() {
        if (closed != null) return
        error = R.string.shell_load_error
        host?.onPresentationChanged()
    }

    private fun rendererFailed(webView: WebView): Boolean {
        if (bound?.webView !== webView || closed != null) return true
        stopTimer()
        loading = false
        formFill?.close()
        formFill = null
        rendererGone = true
        error = R.string.shell_renderer_error
        (webView.parent as? ViewGroup)?.removeView(webView)
        services.profiles.release(bound!!)
        bound = null
        host?.onPresentationChanged()
        return true
    }

    class Factory(
        private val services: ShellServices,
        private val appId: String,
        private val application: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == ShellHolder::class.java)
            return ShellHolder(services, appId, application) as T
        }
    }
}

/** R§9.5 and A§4 WebView settings for a shell, applied before the first load. */
object ShellWebSettings {
    fun apply(webView: WebView, debuggable: Boolean) {
        if (!debuggable) WebView.setWebContentsDebuggingEnabled(false)
        webView.isSaveEnabled = false
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            // Only to observe new-window requests; an ungestured window.open() fails (A§9.4).
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(webView.settings, true)
        }
        webView.webViewClient = ShellWebViewClient()
        webView.webChromeClient = ShellChromeClient()
        // Downloads are unsupported (R§9.5); nothing is ever handed to another app.
        webView.setDownloadListener { _, _, _, _, _ -> }
    }
}

/**
 * Applies [NavigationPolicy] (A§9.4): allowed HTTPS stays in the shell, and every other
 * destination is cancelled before any request is sent and reported through [onIntercepted].
 * POST navigations bypass `shouldOverrideUrlLoading`; a cleartext one is refused by the network
 * security config and reported as "HTTPS required" from [onReceivedError].
 */
private class ShellWebViewClient(
    private val onIntercepted: (NavigationDecision) -> Unit = {},
    private val onRequest: (String, Boolean) -> Unit = { _, _ -> },
    private val onStarted: (String) -> Unit = {},
    private val onCommit: (String) -> Unit = {},
    private val onFailure: (Int) -> Unit = {},
    private val onTlsFailure: (String?) -> Unit = {},
    private val onRendererGone: (WebView) -> Boolean = { true },
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        val decision = NavigationPolicy.decide(url, request.isForMainFrame, request.isRedirect, request.hasGesture())
        if (decision != NavigationDecision.Stay) {
            onIntercepted(decision)
            return true
        }
        if (request.isForMainFrame) onRequest(url, request.method == "GET")
        return false
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onStarted(url)
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        onCommit(url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError?) {
        if (request.isForMainFrame) {
            onRequest(request.url.toString(), request.method == "GET")
            onFailure(if (request.url.scheme.equals("http", ignoreCase = true)) R.string.shell_https_required
                else R.string.shell_load_error)
        }
    }

    override fun onReceivedClientCertRequest(view: WebView, request: android.webkit.ClientCertRequest) {
        request.cancel()
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError?) {
        handler.cancel()
        onTlsFailure(error?.url)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
        onRendererGone(view)
}

/** New windows are refused and site permissions denied until their tasks implement them. */
private class ShellChromeClient : WebChromeClient() {
    override fun onGeolocationPermissionsShowPrompt(origin: String,
        callback: GeolocationPermissions.Callback) {
        callback.invoke(origin, false, false)
    }
}
