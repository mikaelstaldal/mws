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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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
import java.util.UUID
import kotlin.math.abs
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
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var container: FrameLayout
    private var taskDescriptionJob: Job? = null
    private lateinit var presentation: ShellPresentation
    private var closing = false
    private var overlay: View? = null
    private var announcedOverlay: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val adHocUrl = adHocUrlOf(intent)
        webPermissions = WebPermissions(this, savedInstanceState, { holder?.record },
            persistDecisions = adHocUrl == null)
        myPass = MyPassClient(this)
        myPass.restoreIdentity(savedInstanceState)
        handoff = ShellHandoff(this)
        val appId = appIdOf(intent)
        if (appId == null || (intent.action == ACTION_OPEN_AD_HOC && adHocUrl == null)) {
            finishAndRemoveTask()
            return
        }
        container = FrameLayout(this).apply { id = R.id.shell_container }
        refresh = object : SwipeRefreshLayout(this) {
            private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
            private var startedAtTop = false
            private var horizontal = false
            private var axisDecided = false
            private var childClaimed = false
            private var activePointer = MotionEvent.INVALID_POINTER_ID
            private var downX = 0f
            private var downY = 0f
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startedAtTop = !canChildScrollUp()
                        horizontal = false
                        axisDecided = false
                        childClaimed = false
                        activePointer = event.getPointerId(0)
                        downX = event.x
                        downY = event.y
                    }
                    MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == activePointer) {
                        // Follow a remaining finger from where it is now.
                        val next = if (event.actionIndex == 0) 1 else 0
                        activePointer = event.getPointerId(next)
                        downX = event.getX(next)
                        downY = event.getY(next)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val index = event.findPointerIndex(activePointer)
                        if (!axisDecided && index >= 0) {
                            // SwipeRefreshLayout only looks at vertical travel, so a sideways swipe
                            // that drifts downward would be stolen from the page. The first axis to
                            // pass the touch slop owns the gesture.
                            val dx = abs(event.getX(index) - downX)
                            val dy = abs(event.getY(index) - downY)
                            if (dx > touchSlop || dy > touchSlop) {
                                axisDecided = true
                                horizontal = dx >= dy
                            }
                        }
                    }
                }
                return startedAtTop && !horizontal && !childClaimed && super.onInterceptTouchEvent(event)
            }

            // SwipeRefreshLayout 1.1.0 drops this request when its target does not nest scrolling,
            // which the shell container does not, so honour it for the rest of the gesture.
            override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
                if (disallow) childClaimed = true
                parent?.requestDisallowInterceptTouchEvent(disallow)
            }
        }.apply {
            addView(container, ViewGroup.LayoutParams(-1, -1))
            setOnChildScrollUpCallback { _, _ ->
                val owner = holder
                val view = owner?.bound?.webView
                owner == null || view == null || owner.loading || owner.error != null ||
                    owner.recovering || owner.navigationInFlight || view.canScrollVertically(-1)
            }
            setOnRefreshListener {
                holder?.reload()
                isRefreshing = false
            }
        }
        setContentView(refresh)
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
        holder = ViewModelProvider(this, ShellHolder.Factory(services, appId, applicationContext, adHocUrl))
            .get(ShellHolder::class.java)
        consumeLink(intent, appId)
        holder?.attach(this)
        renderOverlay()
    }

    override fun onResume() {
        super.onResume()
        holder?.record?.let(::onRecord)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val appId = appIdOf(intent)
        val url = takeLink(intent, appId)
        if (appId == holder?.appId && url != null) holder?.openExternalDestination(url)
    }

    private fun consumeLink(intent: Intent, appId: String) {
        takeLink(intent, appId)?.let { holder?.openExternalDestination(it) }
    }

    override fun onDestroy() {
        taskDescriptionJob?.cancel()
        webPermissions.dispose()
        handoff.close()
        windowRouting?.close()
        windowRouting = null
        myPass.close()
        holder?.detach(this)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webPermissions.saveState(outState)
        myPass.saveIdentity(outState)
        super.onSaveInstanceState(outState)
    }

    private var windowRouting: NewWindowRouting? = null

    override fun onReady(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        container.removeAllViews()
        overlay = null
        holder?.record?.let(presentation::apply)
        if (holder?.hasVisiblePaint != true) webView.setBackgroundColor(presentation.backgroundColor)
        windowRouting?.close()
        windowRouting = NewWindowRouting(
            create = { servicesOwner ->
                ShellServices.shared(application).profiles.createWindowProbe(this, servicesOwner)
            },
            owner = { holder?.bound },
            release = { ShellServices.shared(application).profiles.release(it) },
            scope = { holder?.record?.scope },
            route = { url, inside ->
                if (inside) holder?.openWindowDestination(webView, url) else openExternalLink(url)
            },
            reject = { handoff.notice(it) },
        )
        webPermissions.newWindow = { source, gesture, message ->
            windowRouting?.open(source, gesture, message) == true
        }
        webView.webChromeClient = webPermissions
        registerForContextMenu(webView)
        container.addView(webView, FrameLayout.LayoutParams(-1, -1))
        renderOverlay()
    }

    override fun onPresentationChanged() = renderOverlay()

    override fun onNotice(message: Int) = handoff.notice(message)

    override fun onExternal(decision: NavigationDecision.Confirm) = handoff.confirmExternal(decision)

    internal fun openExternalLink(url: String) {
        val sourceId = holder?.appId ?: return
        lifecycleScope.launch {
            val target = try {
                ExternalLinkTarget.find(UrlPolicy.parseNavigation(url) ?: return@launch, sourceId,
                    ShellServices.shared(this@ShellActivity).repository.ordered())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!isDestroyed && !closing) {
                if (target == null) handoff.openInBrowser(url)
                else handoff.openInApp(url, target.id)
            }
        }
    }

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
            android.widget.ScrollView(this).apply {
                isFillViewport = true
                addView(LinearLayout(this@ShellActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    val surface = android.util.TypedValue()
                    theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, surface, true)
                    setBackgroundColor(surface.data)
                    addView(TextView(this@ShellActivity).apply {
                        id = R.id.shell_error_message
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
                })
            }
        } else if (owner.loading) {
            FrameLayout(this).apply {
                contentDescription = getString(R.string.shell_loading)
                addView(ProgressBar(this@ShellActivity).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
        } else {
            announcedOverlay = null
            return
        }
        overlay = view
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        val announcement = error ?: R.string.shell_loading
        if (announcement != announcedOverlay) {
            announcedOverlay = announcement
            // Pane titles and live regions would re-announce each rebuilt overlay (A§ accessibility).
            @Suppress("DEPRECATION")
            view.announceForAccessibility(getString(announcement))
        }
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
        private const val ACTION_OPEN_AD_HOC = "nu.staldal.mws.action.OPEN_URL"
        private val adHocLaunches = mutableMapOf<String, String>()
        private val linkLaunches = mutableMapOf<String, Pair<String, String>>()
        private const val EXTRA_LINK_TOKEN = "nu.staldal.mws.extra.LINK_TOKEN"

        /** The only way to open a shell: the data URI identifies the app and its document task. */
        fun intent(context: Context, appId: String): Intent {
            require(ProfileNames.isOpaqueId(appId)) { "App ID is not opaque" }
            return Intent(context, ShellActivity::class.java)
                .setAction(ACTION_OPEN)
                .setData(Uri.parse("$SCHEME:$appId"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }

        /** The intent carries an opaque one-use token, never a possibly sensitive destination. */
        internal fun linkIntent(context: Context, appId: String, url: String): Intent {
            require(UrlPolicy.parseNavigation(url)?.serialized == url)
            val token = UUID.randomUUID().toString()
            linkLaunches[token] = appId to url
            // A launch abandoned by Android must not retain a possibly sensitive URL forever.
            Handler(Looper.getMainLooper()).postDelayed({ linkLaunches.remove(token) }, 120_000)
            return intent(context, appId).putExtra(EXTRA_LINK_TOKEN, token)
        }

        internal fun discardLink(intent: Intent) {
            intent.getStringExtra(EXTRA_LINK_TOKEN)?.let(linkLaunches::remove)
        }

        private fun takeLink(intent: Intent, appId: String?): String? {
            val token = intent.getStringExtra(EXTRA_LINK_TOKEN) ?: return null
            intent.removeExtra(EXTRA_LINK_TOKEN)
            val link = linkLaunches.remove(token) ?: return null
            return link.second.takeIf { link.first == appId }
        }

        fun adHocIntent(context: Context, url: String): Intent {
            val canonical = UrlPolicy.normalizeUserInput(url)?.serialized
            require(canonical == url) { "Invalid ad-hoc URL" }
            val id = UUID.randomUUID().toString()
            adHocLaunches[id] = url
            return Intent(context, ShellActivity::class.java)
                .setAction(ACTION_OPEN_AD_HOC)
                .setData(Uri.parse("$SCHEME:$id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }

        fun adHocUrlOf(intent: Intent?): String? {
            if (intent?.action != ACTION_OPEN_AD_HOC) return null
            return appIdOf(intent)?.let(adHocLaunches::get)
        }

        internal fun forgetAdHoc(appId: String) { adHocLaunches.remove(appId) }

        /** Accepts exactly `mws-app:<opaque ID>`; anything else, including a URL, is rejected. */
        fun appIdOf(intent: Intent?): String? {
            if (intent?.action != ACTION_OPEN && intent?.action != ACTION_OPEN_AD_HOC) return null
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
    private val adHocUrl: String? = null,
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
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var disposableProfile: DisposableProfile? = null
    private var cleared = false

    /** WebView context; points at the attached activity while there is one. */
    private val context = MutableContextWrapper(application)
    private var host: Host? = null
    private var started = false
    private var unregister: (() -> Unit)? = null
    private var appliedThirdPartyCookies: Boolean? = null
    /** Set once the launch URL is loading; record changes are applied from then on. */
    private var ready = false
    private var pendingExternalDestination: String? = null
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
    private var basicAuth = newBasicAuth()

    private fun newBasicAuth(eligible: () -> Boolean = { false }) = ShellBasicAuth(
        enabled = { adHocUrl == null && services.compatibility.current().features.basicAuth && closed == null },
        client = { (host as? ShellActivity)?.myPass },
        flow = appId,
        eligible = eligible,
        onLookup = {
            // A Basic challenge takes precedence over form fill (R§11.1).
            formFill?.invalidate()
            pauseInitialTimer()
        },
        onLookupEnded = ::resumeInitialTimer,
        onError = ::notice,
    )
    private var rendererGone = false
    var recovering = false
        private set
    var navigationInFlight = false
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
        // The activity's MyPass client closes with it; its lookup cannot complete.
        basicAuth.detach()
        this.host = null
        bound?.webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        context.baseContext = application
    }

    private fun start() {
        if (started) return
        started = true
        if (adHocUrl == null) scope.launch {
            services.repository.observe(appId).catch { }.collect(::onRecordChanged)
        }
        scope.launch {
            val result = try {
                services.awaitStartup()
                if (adHocUrl == null) {
                    // Registered before attaching, so a concurrent Clear or Remove always reaches it.
                    unregister = services.shells.register(appId) { close(CloseReason.RETIRED) }
                    services.profiles.createAppWebView(context, appId)
                } else {
                    val profile = withContext(kotlinx.coroutines.NonCancellable) {
                        services.profiles.createDisposableProfile()?.also {
                            disposableProfile = it
                            if (cleared || closed != null) retireAdHocProfile()
                        }
                    }
                    if (profile == null) return@launch close(CloseReason.FAILED)
                    if (cleared || closed != null) return@launch
                    services.profiles.createDisposableWebView(context, profile)
                }
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
        basicAuth.close()
        basicAuth = newBasicAuth(if (adHocUrl == null) attached.basicAuthSafe else ({ false }))
        bound = attached
        val app = try {
            if (adHocUrl != null) {
                val url = UrlPolicy.parseHttps(adHocUrl) ?: return close(CloseReason.FAILED)
                ConfiguredApp(appId, url.host, NameSource.USER_EDITED, adHocUrl, null,
                    adHocUrl, EditorDraft.originScope(url), createdAt = 0, updatedAt = 0)
            } else services.repository.get(appId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (closed != null) return
        if (app == null || (adHocUrl == null && !isCurrent(app))) return close(CloseReason.UNAVAILABLE)
        record = app
        if (adHocUrl != null) host?.onRecord(app)
        // Records are validated on save; this guards the one navigation this task performs.
        if (!app.launchUrl.startsWith("https://")) return close(CloseReason.FAILED)
        // No suspension from here on: nothing can close the shell before the load starts, and
        // [app] is at least as new as any record observed so far.
        val webView = attached.webView
        ShellWebSettings.apply(webView, debuggable = BuildConfig.DEBUG)
        applyThirdPartyCookies(app.allowThirdPartyCookies)
        if (closed != null) return
        ready = true
        if (adHocUrl == null) installFormFill(webView)
        installClient(webView)
        host?.onReady(webView)
        val requested = pendingExternalDestination
        val initialUrl = requested?.takeIf { isExternalDestinationAllowed(it, app) } ?: app.launchUrl
        pendingExternalDestination = null
        load(webView, initialUrl, initial = true)
        if (requested != null && requested != initialUrl) (host as? ShellActivity)?.handoff?.openInBrowser(requested)
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
        retireAdHocProfile()
        host?.onClosed(reason)
    }

    private fun retireAdHocProfile() {
        val profile = disposableProfile ?: return
        disposableProfile = null
        cleanupScope.launch { services.profiles.retireDisposable(profile) }
    }

    private fun release() {
        basicAuth.close()
        val attached = bound ?: return
        (host as? ShellActivity)?.webPermissions?.close()
        formFill?.close()
        formFill = null
        bound = null
        (attached.webView.parent as? ViewGroup)?.removeView(attached.webView)
        services.profiles.release(attached)
    }

    override fun onCleared() {
        cleared = true
        stopTimer()
        scope.cancel()
        unregister?.invoke()
        unregister = null
        release()
        retireAdHocProfile()
        if (adHocUrl != null) ShellActivity.forgetAdHoc(appId)
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

    /** New-window GETs need the same identity, form-fill and Basic-auth setup as native loads. */
    internal fun openWindowDestination(webView: WebView, url: String) {
        if (closed != null || bound?.webView !== webView || UrlPolicy.parseNavigation(url) == null) return
        load(webView, url, initial = false)
    }

    /** A document-task relaunch loads the link in its existing profile and WebView. */
    internal fun openExternalDestination(url: String) {
        if (closed != null || adHocUrl != null) {
            (host as? ShellActivity)?.handoff?.openInBrowser(url)
            return
        }
        val app = record
        val webView = bound?.webView
        if (!ready || app == null || webView == null || rendererGone || recovering) {
            pendingExternalDestination = url
            if (rendererGone && !recovering) retry()
        } else if (isExternalDestinationAllowed(url, app)) {
            load(webView, url, initial = false)
        } else {
            (host as? ShellActivity)?.handoff?.openInBrowser(url)
        }
    }

    private fun isExternalDestinationAllowed(url: String, app: ConfiguredApp): Boolean {
        if (app.profileState != ProfileState.ACTIVE) return false
        val target = UrlPolicy.parseNavigation(url) ?: return false
        val scope = UrlPolicy.parseHttps(app.scope) ?: return false
        return UrlPolicy.isWithinScope(target, scope)
    }

    /** Explicit user reload: keep WebView reload semantics, including its current history entry. */
    fun reload() {
        val webView = bound?.webView ?: return
        val url = webView.url ?: return
        if (closed != null || loading || navigationInFlight || error != null || recovering ||
            webView.canScrollVertically(-1) || UrlPolicy.parseNavigation(url) == null) return
        navigationIdentity++
        navigationInFlight = true
        currentUrl = url
        // Reload may repeat a POST; never promote it to a safe GET for error recovery.
        requestObserved = true
        failedUrl = null
        failedGet = false
        initialNavigation = false
        stopTimer()
        (host as? ShellActivity)?.webPermissions?.close()
        formFill?.navigate(url)
        basicAuth.arm(url)
        webView.reload()
    }

    private fun load(webView: WebView, url: String, initial: Boolean) {
        navigationIdentity++
        navigationInFlight = true
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
        basicAuth.arm(url)
        webView.loadUrl(url)
    }

    private fun installClient(webView: WebView) {
        val auth = basicAuth
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
            onRequest = { url, get, redirect ->
                if (bound?.webView === webView) {
                    navigationInFlight = true
                    if (redirect) auth.redirect(url) else auth.arm(url)
                    (host as? ShellActivity)?.webPermissions?.close()
                    formFill?.navigate(url)
                    currentUrl = url
                    currentGet = get
                    requestObserved = true
                }
            },
            onStarted = { url ->
                if (bound?.webView === webView) auth.started(url)
                if (bound?.webView === webView && error == null) {
                    (host as? ShellActivity)?.webPermissions?.close()
                    navigationInFlight = true
                    formFill?.started(url)
                    initialDocumentStarted = true
                    if (!requestObserved && !initialNavigation) currentGet = false
                    currentUrl = url
                    requestObserved = false
                }
            },
            onCommit = { url ->
                if (bound?.webView === webView) auth.disarm()
                if (bound?.webView === webView && error == null) {
                    hasVisiblePaint = true
                    webView.setBackgroundColor(Color.WHITE)
                    stopTimer()
                    initialNavigation = false
                    loading = false
                    navigationInFlight = false
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
            onFailure = { message ->
                if (bound?.webView === webView) {
                    auth.disarm()
                    fail(message)
                }
            },
            onTlsFailure = { url ->
                if (bound?.webView === webView) {
                    auth.disarm()
                    formFill?.rejectDocument()
                    if (url == currentUrl) fail(R.string.shell_tls_error)
                }
            },
            onCancelled = {
                if (bound?.webView === webView) {
                    navigationInFlight = false
                    auth.disarm()
                }
            },
            onIntercept = auth::intercepted,
            onAuthRequest = { answer, authHost, realm ->
                if (bound?.webView === webView) auth.challenge(answer, authHost, realm) else answer.cancel()
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
        basicAuth.disarm()
        (host as? ShellActivity)?.webPermissions?.close()
        formFill?.rejectDocument()
        stopTimer()
        loading = false
        navigationInFlight = false
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
                if (webView.canGoBack()) {
                    basicAuth.disarm()
                    webView.goBack()
                } else {
                    basicAuth.arm(lastSafeGet!!)
                    webView.loadUrl(lastSafeGet!!)
                }
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
                    val result = if (adHocUrl == null) services.profiles.createAppWebView(context, appId)
                        else disposableProfile?.let { services.profiles.createDisposableWebView(context, it) }
                            ?: AttachResult.Blocked(AttachBlock.NOT_REGISTERED)
                    if (result is AttachResult.Blocked) {
                        when (result.reason) {
                            AttachBlock.CLEARING, AttachBlock.MISSING_APP -> close(CloseReason.RETIRED)
                            AttachBlock.INCOMPATIBLE -> close(CloseReason.INCOMPATIBLE)
                            else -> showRecoveryFailure()
                        }
                        return@launch
                    }
                    val replacement = (result as AttachResult.Attached).bound
                    if (closed != null || (adHocUrl == null && record?.let {
                        it.profileState == ProfileState.ACTIVE &&
                            replacement.profileName == ProfileNames.app(it.id, it.profileGeneration)
                    } != true)) {
                        services.profiles.release(replacement)
                        return@launch
                    }
                    formFill?.close()
                    formFill = null
                    basicAuth.close()
                    basicAuth = newBasicAuth(if (adHocUrl == null) replacement.basicAuthSafe else ({ false }))
                    bound = replacement
                    old?.let {
                        (it.webView.parent as? ViewGroup)?.removeView(it.webView)
                        services.profiles.release(it)
                    }
                    ShellWebSettings.apply(replacement.webView, BuildConfig.DEBUG)
                    appliedThirdPartyCookies = null
                    applyThirdPartyCookies(record!!.allowThirdPartyCookies)
                    if (adHocUrl == null) installFormFill(replacement.webView)
                    installClient(replacement.webView)
                    host?.onReady(replacement.webView)
                    val requested = pendingExternalDestination
                    val destination = requested?.takeIf { isExternalDestinationAllowed(it, record!!) }
                        ?: target
                    pendingExternalDestination = null
                    load(replacement.webView, destination, initial = true)
                    if (requested != null && requested != destination)
                        (host as? ShellActivity)?.handoff?.openInBrowser(requested)
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
        basicAuth.disarm()
        stopTimer()
        loading = false
        navigationInFlight = false
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
        private val adHocUrl: String? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == ShellHolder::class.java)
            return ShellHolder(services, appId, application, adHocUrl) as T
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
 * POST navigations bypass `shouldOverrideUrlLoading`; HTTP requests are synchronously replaced
 * with a local response in [shouldInterceptRequest], before the provider transmits them.
 */
private class ShellWebViewClient(
    private val onIntercepted: (NavigationDecision) -> Unit = {},
    /** An allowed main-frame request: URL, whether it is a GET, and whether it is a redirect. */
    private val onRequest: (String, Boolean, Boolean) -> Unit = { _, _, _ -> },
    private val onStarted: (String) -> Unit = {},
    private val onCommit: (String) -> Unit = {},
    private val onFailure: (Int) -> Unit = {},
    private val onTlsFailure: (String?) -> Unit = {},
    /** A main-frame navigation was cancelled by policy. */
    private val onCancelled: () -> Unit = {},
    /** Called on a background thread for every request, including the main document. */
    private val onIntercept: (String, Boolean) -> Unit = { _, _ -> },
    private val onAuthRequest: (AuthAnswer, String?, String?) -> Unit = { answer, _, _ -> answer.cancel() },
    private val onRendererGone: (WebView) -> Boolean = { true },
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        val decision = NavigationPolicy.decide(url, request.isForMainFrame, request.isRedirect, request.hasGesture()) {
            DisallowedSchemeLog.log(it, url)
        }
        if (decision != NavigationDecision.Stay) {
            if (request.isForMainFrame) onCancelled()
            onIntercepted(decision)
            return true
        }
        if (request.isForMainFrame) onRequest(url, request.method == "GET", request.isRedirect)
        return false
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        onIntercept(request.url.toString(), request.isForMainFrame)
        val blocked = CleartextRequestBlocker.response(request.url) ?: return null
        if (request.isForMainFrame) {
            val url = request.url.toString()
            val get = request.method == "GET"
            // Interception runs off the UI thread. The response blocks transmission synchronously;
            // this posted callback only updates the shell's error and Retry state.
            view.post {
                onRequest(url, get, false)
                onFailure(R.string.shell_https_required)
            }
        }
        return blocked
    }

    /** A§11: never answered from the provider HTTP-auth database; only the navigation gate may supply a credential. */
    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
        onAuthRequest(handler.asAnswer(), host, realm)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onStarted(url)
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        onCommit(url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError?) {
        if (request.isForMainFrame) {
            onRequest(request.url.toString(), request.method == "GET", false)
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
