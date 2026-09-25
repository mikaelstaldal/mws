package nu.staldal.mws

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
    private lateinit var container: FrameLayout
    private var taskDescriptionJob: Job? = null
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appId = appIdOf(intent)
        if (appId == null) {
            finishAndRemoveTask()
            return
        }
        container = FrameLayout(this).apply { id = R.id.shell_container }
        setContentView(container)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = holder?.bound?.webView
                if (webView != null && webView.canGoBack()) webView.goBack() else finishAndRemoveTask()
            }
        })
        val services = ShellServices.shared(this)
        // Saved instance state is ignored: a holder survives only configuration changes, and after
        // process death a fresh holder loads the resolved launch URL (A§9.2).
        holder = ViewModelProvider(this, ShellHolder.Factory(services, appId, applicationContext))
            .get(ShellHolder::class.java).also { it.attach(this) }
    }

    override fun onResume() {
        super.onResume()
        holder?.record?.let(::onRecord)
    }

    override fun onDestroy() {
        taskDescriptionJob?.cancel()
        holder?.detach(this)
        super.onDestroy()
    }

    override fun onReady(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        container.addView(webView, FrameLayout.LayoutParams(-1, -1))
    }

    override fun onRecord(app: ConfiguredApp) {
        taskDescriptionJob?.cancel()
        taskDescriptionJob = lifecycleScope.launch {
            val size = IconPipeline.SIZES.firstOrNull {
                it >= resources.displayMetrics.density * 48
            } ?: IconPipeline.SIZES.last()
            val icon = withContext(Dispatchers.IO) {
                ShellServices.shared(this@ShellActivity).icons.load(app.iconReference, size)
            } ?: ShellServices.shared(this@ShellActivity).icons.defaultIcon().toBitmap(size, size)
            setTaskDescription(taskDescription(app, icon))
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

        /** The task color must be opaque; presentation rules refine the choice later (A§9.3). */
        @Suppress("DEPRECATION")
        fun taskDescription(app: ConfiguredApp, icon: Bitmap): ActivityManager.TaskDescription =
            ActivityManager.TaskDescription(app.name, icon,
                app.themeColor?.takeIf { Color.alpha(it) == 0xFF } ?: 0)
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
        start()
    }

    /** Main thread only. The WebView stays alive for the next activity. */
    fun detach(host: Host) {
        if (this.host !== host) return
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
        // Records are validated on save; this guards the one navigation this task performs.
        if (!app.launchUrl.startsWith("https://")) return close(CloseReason.FAILED)
        // No suspension from here on: nothing can close the shell before the load starts, and
        // [app] is at least as new as any record observed so far.
        val webView = attached.webView
        ShellWebSettings.apply(webView, debuggable = BuildConfig.DEBUG)
        applyThirdPartyCookies(app.allowThirdPartyCookies)
        if (closed != null) return
        ready = true
        host?.onReady(webView)
        webView.loadUrl(app.launchUrl)
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
        unregister?.invoke()
        unregister = null
        release()
        host?.onClosed(reason)
    }

    private fun release() {
        val attached = bound ?: return
        bound = null
        (attached.webView.parent as? ViewGroup)?.removeView(attached.webView)
        services.profiles.release(attached)
    }

    override fun onCleared() {
        scope.cancel()
        unregister?.invoke()
        unregister = null
        release()
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
    }
}

/**
 * Fail-closed baseline until the full navigation policy (A§9.4) lands: HTTPS stays in the shell
 * and nothing else is loaded or handed to another app.
 */
private class ShellWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !request.url.toString().startsWith("https://", ignoreCase = true)
}

/** New windows are refused and site permissions denied until their tasks implement them. */
private class ShellChromeClient : WebChromeClient() {
    override fun onGeolocationPermissionsShowPrompt(origin: String,
        callback: GeolocationPermissions.Callback) {
        callback.invoke(origin, false, false)
    }
}
