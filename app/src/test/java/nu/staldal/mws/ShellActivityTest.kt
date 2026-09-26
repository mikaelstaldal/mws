package nu.staldal.mws

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.ComponentName
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.net.http.SslCertificate
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.TimeUnit
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShellActivityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: ConfigurationDatabase
    private lateinit var repository: ConfigurationRepository
    private lateinit var store: MaintenanceStore
    private lateinit var services: ShellServices
    private val backend = Backend()
    private var compatible = true
    private val controllers = mutableListOf<ActivityController<*>>()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ConfigurationDatabase::class.java).build()
        repository = ConfigurationRepository(database)
        store = MaintenanceStore(context)
        val compatibility = ProviderCompatibility(
            { ProviderSnapshot(PROVIDER, compatible) },
            { key -> ProviderSelfTestResult(key, true, true, true, true, true, true) },
            floors = mapOf(PROVIDER.packageName to listOf(1)),
        )
        val shells = LiveShellRegistry()
        val profiles = ProfileManager(repository, store, compatibility, backend, shells,
            object : AppNativeData {
                override fun deleteIcons(appId: String) {}
                override fun widgetsChanged() {}
            })
        services = ShellServices(repository, compatibility, shells, profiles, IconStore(context))
        ShellServices.install(services)
    }

    @After fun tearDown() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        ShellServices.install(null)
        database.close()
        store.close()
    }

    @Test fun launchIntentCarriesOnlyTheAppIdAsDocumentDataUri() {
        val intent = ShellActivity.intent(context, "app_1-A")
        assertEquals(ShellActivity::class.java.name, intent.component?.className)
        assertEquals(ShellActivity.ACTION_OPEN, intent.action)
        assertEquals("mws-app:app_1-A", intent.dataString)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK == 0)
        assertNull(intent.extras)
        assertEquals("app_1-A", ShellActivity.appIdOf(intent))
        for (bad in listOf("", "a/b", "https://example.com", "a b", "a".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) { ShellActivity.intent(context, bad) }
        }
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, ShellActivity::class.java), 0)
        assertEquals(ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING, info.documentLaunchMode)
        assertFalse(info.exported)
        assertNull(info.taskAffinity?.takeIf { it != context.packageName })
    }

    @Test fun forgedIntentsAreRejectedBeforeAnyProfileIsBound() {
        val app = addApp()
        val base = ShellActivity.intent(context, app.id)
        val forged = listOf(
            Intent(context, ShellActivity::class.java),
            Intent(base).setAction(Intent.ACTION_VIEW),
            Intent(base).setData(Uri.parse("https://example.com/")),
            Intent(base).setData(null).putExtra("appId", app.id),
            Intent(base).setData(Uri.parse("mws-app:")),
            Intent(base).setData(Uri.parse("mws-app:../${app.id}")),
            Intent(base).setData(Uri.parse("mws-app:${app.id}/x")),
            Intent(base).setData(Uri.parse("mws-app:${app.id}?url=https://evil.example/")),
            Intent(base).setData(Uri.parse("mws-app:${app.id}#x")),
            Intent(base).setData(Uri.parse("mws-app://${app.id}")),
            Intent(base).setData(Uri.parse("MWS-APP:${app.id}")),
            Intent(base).setData(Uri.parse("mws-app:" + "a".repeat(65))),
        )
        for (intent in forged) {
            val activity = launch(intent)
            assertTrue(intent.toString(), activity.isFinishing)
        }
        settle()
        assertTrue(backend.bound.isEmpty())
    }

    @Test fun staleIdIsRejectedWithoutBinding() {
        val activity = launch(ShellActivity.intent(context, "gone"))
        await { activity.isFinishing }
        assertEquals(context.getString(R.string.app_unavailable), ShadowToast.getTextOfLatestToast())
        assertTrue(backend.bound.isEmpty())
        assertTrue(services.shellsFor("gone") == 0)
    }

    @Test fun clearingAppIsNotLaunched() {
        val app = addApp()
        onMain { services.awaitStartup() } // otherwise startup maintenance would finish the clear
        onMain { repository.reserveNextGeneration(app.id) }
        val activity = launch(ShellActivity.intent(context, app.id))
        await { activity.isFinishing }
        assertEquals(context.getString(R.string.clearing_explanation), ShadowToast.getTextOfLatestToast())
        assertTrue(backend.bound.isEmpty())
    }

    @Test fun unresolvedJournalOperationBlocksLaunch() {
        val app = addApp()
        onMain { services.awaitStartup() }
        store.begin(OperationIntent(OperationType.UPDATE_URL, app.id, 0, 1), 1)
        val activity = launch(ShellActivity.intent(context, app.id))
        await { activity.isFinishing }
        assertEquals(context.getString(R.string.shell_start_failed), ShadowToast.getTextOfLatestToast())
        assertTrue(backend.bound.isEmpty())
    }

    @Test fun closeDuringAttachmentNeverLoadsTheDestroyedWebView() {
        val app = addApp()
        backend.onThirdParty = { webView ->
            // The holder's own application, right before the first load, is interrupted by a close.
            if (webView in backend.thirdParty) {
                MainScope().launch(Dispatchers.Unconfined) { services.shells.closeAll(app.id) }
            }
        }
        val activity = launch(ShellActivity.intent(context, app.id))
        await { activity.isFinishing }
        settle()
        assertEquals(0, container(activity).childCount)
        assertEquals(1, backend.views.size)
        assertEquals(0, backend.views.single().loads)
        assertTrue(Shadows.shadowOf(backend.views.single()).wasDestroyCalled())
    }

    @Test fun incompatibleProviderShowsErrorWithoutBinding() {
        val app = addApp()
        compatible = false
        val activity = launch(ShellActivity.intent(context, app.id))
        await { activity.isFinishing }
        assertEquals(ProviderErrorActivity::class.java.name,
            Shadows.shadowOf(activity).nextStartedActivity.component?.className)
        assertTrue(backend.bound.isEmpty())
    }

    @Test fun activeAppBindsItsActiveGenerationAndLoadsLaunchUrl() {
        val app = addApp(generation = 3, themeColor = 0xFF112233.toInt())
        val activity = launch(ShellActivity.intent(context, app.id))
        val webView = awaitWebView(activity)
        assertEquals(listOf(ProfileNames.app(app.id, 3)), backend.bound)
        assertEquals(1, webView.loads)
        assertEquals(app.launchUrl, Shadows.shadowOf(webView).lastLoadedUrl)
        assertFalse(webView.isSaveEnabled)
        val settings = webView.settings
        assertTrue(settings.javaScriptEnabled)
        assertTrue(settings.domStorageEnabled)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertTrue(settings.supportMultipleWindows())
        assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
        assertNotNull(Shadows.shadowOf(webView).webViewClient)
        assertNotNull(Shadows.shadowOf(webView).webChromeClient)
        assertEquals(false, backend.thirdParty[webView])
        await { taskDescription(activity)?.label == "App" }
        assertEquals(0xFF112233.toInt(), taskDescription(activity)?.primaryColor)
        assertNotNull(taskDescription(activity)?.icon)
        assertEquals(1, services.shellsFor(app.id))
    }

    @Test fun recordChangesRefreshRecentsAndThirdPartyCookiesWithoutReload() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val webView = awaitWebView(activity)
        onMain { repository.update(app.copy(name = "Renamed", allowThirdPartyCookies = true)) }
        await { backend.thirdParty[webView] == true && taskDescription(activity)?.label == "Renamed" }
        onMain { repository.update(app.copy(name = "Renamed", allowThirdPartyCookies = false)) }
        await { backend.thirdParty[webView] == false }
        assertEquals(1, webView.loads)
        assertFalse(activity.isFinishing)
    }

    @Test fun configurationChangeKeepsTheLivePage() {
        val app = addApp()
        val controller = controller(ShellActivity.intent(context, app.id))
        val webView = awaitWebView(controller.get())
        controller.recreate()
        val recreated = controller.get()
        assertSame(webView, container(recreated).getChildAt(0))
        assertSame(recreated, (webView.context as android.content.MutableContextWrapper).baseContext)
        settle()
        assertEquals(1, webView.loads)
        assertEquals(1, backend.bound.size)
        assertFalse(Shadows.shadowOf(webView).wasDestroyCalled())
    }

    @Test fun relaunchBringsTheLiveShellForwardWithoutReload() {
        val app = addApp()
        val controller = controller(ShellActivity.intent(context, app.id))
        val webView = awaitWebView(controller.get())
        controller.newIntent(ShellActivity.intent(context, app.id))
        settle()
        assertFalse(controller.get().isFinishing)
        assertEquals(1, webView.loads)
        assertEquals(1, backend.bound.size)
    }

    @Test fun backNavigatesHistoryThenClosesTheShell() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val webView = awaitWebView(activity)
        Shadows.shadowOf(webView).setCanGoBack(true)
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, Shadows.shadowOf(webView).goBackInvocations)
        assertFalse(activity.isFinishing)
        Shadows.shadowOf(webView).setCanGoBack(false)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
    }

    @Test fun initialLoadingAndTimeoutIgnoreLateCommitAndRetryUsesFreshView() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val first = awaitWebView(activity)
        assertNotNull(container(activity).contentDescription ?: container(activity).getChildAt(1).contentDescription)
        ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS)
        assertNotNull(activity.findViewById<Button>(R.id.shell_retry_button))
        Shadows.shadowOf(first).webViewClient.onPageCommitVisible(first, app.launchUrl)
        assertNotNull(activity.findViewById<Button>(R.id.shell_retry_button))
        activity.findViewById<Button>(R.id.shell_retry_button).performClick()
        await { backend.views.size == 2 }
        val holder = ReflectionHelpers.getField<ShellHolder>(activity, "holder")
        await { holder.bound != null || holder.closed != null }
        assertNull("recovery closed: ${holder.closed}", holder.closed)
        val second = awaitWebView(activity)
        assertNotSame(first, second)
        assertEquals(app.launchUrl, Shadows.shadowOf(second).lastLoadedUrl)
        assertTrue(Shadows.shadowOf(first).wasDestroyCalled())
        Shadows.shadowOf(first).webViewClient.onPageCommitVisible(first, app.launchUrl)
        assertNotNull(container(activity).getChildAt(1))
        assertNull(activity.findViewById<Button>(R.id.shell_retry_button))
        Shadows.shadowOf(second).webViewClient.onPageCommitVisible(second, app.launchUrl)
        assertNull(activity.findViewById<Button>(R.id.shell_retry_button))
    }

    @Test fun initialTimerSpansRedirectsAndPausesForMyPass() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        val holder = ReflectionHelpers.getField<ShellHolder>(activity, "holder")
        ShadowLooper.idleMainLooper(10, TimeUnit.SECONDS)
        client.onPageStarted(view, "https://example.com/redirect", null)
        holder.pauseInitialTimer()
        ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS)
        assertNull(activity.findViewById<Button>(R.id.shell_retry_button))
        holder.resumeInitialTimer()
        ShadowLooper.idleMainLooper(20, TimeUnit.SECONDS)
        assertNotNull(activity.findViewById<Button>(R.id.shell_retry_button))
    }

    @Test fun visibleCommitStopsInitialTimerAndCloseButtonFinishesErrorShell() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        Shadows.shadowOf(view).webViewClient.onPageCommitVisible(view, app.launchUrl)
        ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS)
        assertNull(activity.findViewById<Button>(R.id.shell_retry_button))
        Shadows.shadowOf(view).webViewClient.onReceivedError(view,
            request("https://example.com/fail", "GET"), null)
        activity.findViewById<Button>(R.id.shell_close_button).performClick()
        assertTrue(activity.isFinishing)
    }

    @Test fun doubleRetryCreatesOnlyOneReplacement() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val first = awaitWebView(activity)
        Shadows.shadowOf(first).webViewClient.onReceivedError(first,
            request(app.launchUrl, "GET"), null)
        val button = activity.findViewById<Button>(R.id.shell_retry_button)
        button.performClick()
        button.performClick()
        await { backend.views.size == 2 }
        settle()
        assertEquals(2, backend.views.size)
        assertSame(backend.views.last(), awaitWebView(activity))
    }

    @Test fun failedReplacementKeepsRetryAvailableWithLocalError() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val first = awaitWebView(activity)
        backend.failCreateAfter = 1
        Shadows.shadowOf(first).webViewClient.onReceivedError(first,
            request(app.launchUrl, "GET"), null)
        activity.findViewById<Button>(R.id.shell_retry_button).performClick()
        await { activity.findViewById<Button>(R.id.shell_retry_button)?.isEnabled == true &&
            container(activity).getChildAt(container(activity).childCount - 1).contentDescription ==
            context.getString(R.string.shell_load_error) }
        assertFalse(activity.isFinishing)
        assertEquals(1, backend.views.size)
    }

    @Test fun errorOverlaySurvivesRotationAndBackClosesWithoutSafePage() {
        val app = addApp()
        val controller = controller(ShellActivity.intent(context, app.id))
        val firstActivity = controller.get()
        val view = awaitWebView(firstActivity)
        Shadows.shadowOf(view).webViewClient.onReceivedError(view,
            request(app.launchUrl, "GET"), null)
        controller.recreate()
        val recreated = controller.get()
        assertNotNull(recreated.findViewById<Button>(R.id.shell_retry_button))
        recreated.onBackPressedDispatcher.onBackPressed()
        assertTrue(recreated.isFinishing)
    }

    @Test fun failedGetRetriesItButPostReturnsToLastCommittedGet() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        val getUrl = "https://example.com/next"
        client.shouldOverrideUrlLoading(view, request(getUrl, "GET"))
        client.onReceivedError(view, request(getUrl, "GET"), null)
        activity.findViewById<Button>(R.id.shell_retry_button).performClick()
        assertEquals(getUrl, Shadows.shadowOf(view).lastLoadedUrl)
        client.onPageCommitVisible(view, getUrl)
        val postUrl = "https://example.com/submit"
        client.shouldOverrideUrlLoading(view, request(postUrl, "POST"))
        client.onReceivedError(view, request(postUrl, "POST"), null)
        activity.findViewById<Button>(R.id.shell_retry_button).performClick()
        assertEquals(getUrl, Shadows.shadowOf(view).lastLoadedUrl)
    }

    @Test fun historyBackUpdatesLastSafeGetForPostFailure() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        val secondUrl = "https://example.com/second"
        client.shouldOverrideUrlLoading(view, request(secondUrl, "GET"))
        client.onPageStarted(view, secondUrl, null)
        client.onPageCommitVisible(view, secondUrl)
        client.onPageStarted(view, app.launchUrl, null)
        client.onPageCommitVisible(view, app.launchUrl)
        val postUrl = "https://example.com/submit"
        client.onReceivedError(view, request(postUrl, "POST"), null)
        activity.findViewById<Button>(R.id.shell_retry_button).performClick()
        assertEquals(app.launchUrl, Shadows.shadowOf(view).lastLoadedUrl)
    }

    @Test fun backDismissesErrorBeforeNavigatingHistory() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        Shadows.shadowOf(view).webViewClient.onPageCommitVisible(view, app.launchUrl)
        Shadows.shadowOf(view).webViewClient.onReceivedError(view,
            request("https://example.com/fail", "GET"), null)
        Shadows.shadowOf(view).setCanGoBack(true)
        activity.onBackPressedDispatcher.onBackPressed()
        assertNull(activity.findViewById<Button>(R.id.shell_retry_button))
        assertEquals(0, Shadows.shadowOf(view).goBackInvocations)
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, Shadows.shadowOf(view).goBackInvocations)
    }

    @Test fun tlsFailureCancelsHandshakeAndShowsLocalError() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val handler = ReflectionHelpers.callConstructor(SslErrorHandler::class.java)
        val certificate = ReflectionHelpers.newInstance(SslCertificate::class.java)
        Shadows.shadowOf(view).webViewClient.onReceivedSslError(view, handler,
            SslError(SslError.SSL_UNTRUSTED, certificate, app.launchUrl))
        assertTrue(Shadows.shadowOf(handler).wasCancelCalled())
        assertFalse(Shadows.shadowOf(handler).wasProceedCalled())
        assertNotNull(activity.findViewById<Button>(R.id.shell_retry_button))
        assertEquals(context.getString(R.string.shell_tls_error),
            container(activity).getChildAt(container(activity).childCount - 1).contentDescription)
    }

    @Test fun subresourceTlsFailureIsCancelledWithoutCoveringThePage() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        Shadows.shadowOf(view).webViewClient.onPageCommitVisible(view, app.launchUrl)
        val handler = ReflectionHelpers.callConstructor(SslErrorHandler::class.java)
        val certificate = ReflectionHelpers.newInstance(SslCertificate::class.java)
        Shadows.shadowOf(view).webViewClient.onReceivedSslError(view, handler,
            SslError(SslError.SSL_UNTRUSTED, certificate, "https://example.com/image.png"))
        assertTrue(Shadows.shadowOf(handler).wasCancelCalled())
        assertNull(activity.findViewById<Button>(R.id.shell_retry_button))
    }

    @Test fun rendererCrashRebindsSameProfileOnRetry() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val first = awaitWebView(activity)
        val committed = "https://example.com/current"
        Shadows.shadowOf(first).webViewClient.shouldOverrideUrlLoading(first, request(committed, "GET"))
        Shadows.shadowOf(first).webViewClient.onPageCommitVisible(first, committed)
        assertTrue(Shadows.shadowOf(first).webViewClient.onRenderProcessGone(first,
            object : RenderProcessGoneDetail() {
                override fun didCrash() = true
                override fun rendererPriorityAtExit() = 0
            }))
        assertTrue(Shadows.shadowOf(first).wasDestroyCalled())
        activity.findViewById<Button>(R.id.shell_retry_button).performClick()
        await { backend.views.size == 2 }
        val holder = ReflectionHelpers.getField<ShellHolder>(activity, "holder")
        await { holder.bound != null || holder.closed != null }
        assertNull("recovery closed: ${holder.closed}", holder.closed)
        val second = awaitWebView(activity)
        assertEquals(committed, Shadows.shadowOf(second).lastLoadedUrl)
        assertEquals(listOf(ProfileNames.app(app.id, 0), ProfileNames.app(app.id, 0)), backend.bound)
    }

    @Test fun clearAfterRendererCrashClosesShellBeforeRecovery() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        Shadows.shadowOf(view).webViewClient.onRenderProcessGone(view,
            object : RenderProcessGoneDetail() {
                override fun didCrash() = true
                override fun rendererPriorityAtExit() = 0
            })
        assertEquals(ClearResult.Cleared, onMain { services.profiles.clear(app.id) })
        assertTrue(activity.isFinishing)
        assertEquals(1, backend.views.size)
    }

    @Test fun simultaneousShellsAreIsolatedAndClearClosesOnlyTheTargetBeforeDeletion() {
        val first = addApp()
        val second = addApp()
        val a = launch(ShellActivity.intent(context, first.id))
        val b = launch(ShellActivity.intent(context, second.id))
        val aView = awaitWebView(a)
        val bView = awaitWebView(b)
        assertEquals(setOf(ProfileNames.app(first.id, 0), ProfileNames.app(second.id, 0)),
            backend.bound.toSet())
        var closedFirst = false
        backend.onDelete = { closedFirst = a.isFinishing && Shadows.shadowOf(aView).wasDestroyCalled() }

        assertEquals(ClearResult.Cleared, onMain { services.profiles.clear(first.id) })
        assertTrue(closedFirst)
        assertEquals(listOf(ProfileNames.app(first.id, 0)), backend.deleteCalls)
        assertNull(aView.parent)
        assertFalse(b.isFinishing)
        assertFalse(Shadows.shadowOf(bView).wasDestroyCalled())
        assertEquals(0, services.shellsFor(first.id))
        assertEquals(1, services.shellsFor(second.id))

        // The next launch attaches the fresh generation only.
        val relaunched = launch(ShellActivity.intent(context, first.id))
        awaitWebView(relaunched)
        assertEquals(ProfileNames.app(first.id, 1), backend.bound.last())
    }

    @Test fun removeClosesTheLiveShellBeforeProfileDeletion() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val webView = awaitWebView(activity)
        var closedFirst = false
        backend.onDelete = { closedFirst = activity.isFinishing && Shadows.shadowOf(webView).wasDestroyCalled() }
        assertEquals(RemovalResult.Removed, onMain { services.profiles.remove(app.id) })
        assertTrue(closedFirst)
        assertEquals(0, services.shellsFor(app.id))
    }

    @Test fun originChangeReservationClosesTheLiveShell() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val webView = awaitWebView(activity)
        // Seen through record observation even before the clear flow invokes the close hook.
        onMain { repository.reserveNextGeneration(app.id) { it.copy(launchUrl = "https://other.example/") } }
        await { activity.isFinishing }
        assertTrue(Shadows.shadowOf(webView).wasDestroyCalled())
        assertEquals(1, webView.loads)
    }

    @Test fun mainScreenTapLaunchesTheShellDocument() {
        val app = addApp()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controllers += controller
        val rows = controller.get().findViewById<LinearLayout>(R.id.app_rows)
        await { rows.childCount == 1 }
        rows.getChildAt(0).performClick()
        val started = Shadows.shadowOf(controller.get()).nextStartedActivity
        assertEquals(ShellActivity::class.java.name, started.component?.className)
        assertEquals(app.id, ShellActivity.appIdOf(started))
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT != 0)
    }

    private fun permissionDialog() = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog

    private class CaptureRequest(private val capability: String) : android.webkit.PermissionRequest() {
        var allowed: Array<out String>? = null
        var denied = false
        override fun getOrigin() = Uri.parse("https://example.com")
        override fun getResources() = arrayOf(capability)
        override fun grant(resources: Array<out String>) { allowed = resources }
        override fun deny() { denied = true }
    }

    @Test fun geolocationPersistsSiteDecisionsButRechecksAndroidPermission() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        awaitWebView(activity)
        val results = mutableListOf<Boolean>()
        val callback = android.webkit.GeolocationPermissions.Callback { _, allow, retain ->
            assertFalse(retain); results += allow
        }
        activity.webPermissions.onGeolocationPermissionsShowPrompt("https://example.com", callback)
        assertTrue(permissionDialog().findViewById<android.widget.TextView>(android.R.id.message)!!
            .text.contains("https://example.com"))
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick().also { ShadowLooper.idleMainLooper() }
        assertEquals(listOf(false), results)
        activity.webPermissions.onGeolocationPermissionsShowPrompt("https://example.com", callback)
        assertEquals(listOf(false, false), results)
        assertEquals(SiteDecision.SITE_DENY, store.permissionDecision(app.id, app.profileGeneration,
            "https://example.com", PermissionCapability.GEOLOCATION)?.decision)
        store.deletePermissionDecisions(app.id, app.profileGeneration)
        Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        activity.webPermissions.onGeolocationPermissionsShowPrompt("https://example.com", callback)
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick().also { ShadowLooper.idleMainLooper() }
        assertEquals(true, results.last())
        activity.webPermissions.onGeolocationPermissionsShowPrompt("https://example.com", callback)
        assertEquals(4, results.size)
        Shadows.shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        context.getSharedPreferences("android_permission_denials", 0).edit()
            .putBoolean(android.Manifest.permission.ACCESS_FINE_LOCATION, true)
            .putBoolean(android.Manifest.permission.ACCESS_COARSE_LOCATION, true).commit()
        activity.webPermissions.onGeolocationPermissionsShowPrompt("https://example.com", callback)
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick().also { ShadowLooper.idleMainLooper() }
        assertEquals(false, results.last())
        assertEquals(SiteDecision.SITE_ALLOW, store.permissionDecision(app.id, app.profileGeneration,
            "https://example.com", PermissionCapability.GEOLOCATION)?.decision)
        // Clearing site state leaves the Android grant intact.
        Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        store.deletePermissionDecisions(app.id, app.profileGeneration)
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            androidx.core.content.ContextCompat.checkSelfPermission(activity, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        assertNull(store.permissionDecision(app.id, app.profileGeneration, "https://example.com", PermissionCapability.GEOLOCATION))
    }

    @Test fun cameraPromptsEveryUseSerializesAndCancelsPendingRequests() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        awaitWebView(activity)
        Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.CAMERA)
        val first = CaptureRequest(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val second = CaptureRequest(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        activity.webPermissions.onPermissionRequest(first)
        val firstDialog = permissionDialog()
        activity.webPermissions.onPermissionRequest(second)
        assertSame(firstDialog, permissionDialog())
        firstDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick().also { ShadowLooper.idleMainLooper() }
        assertNotNull(first.allowed)
        assertNotSame(firstDialog, permissionDialog())
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick().also { ShadowLooper.idleMainLooper() }
        assertTrue(second.denied)
        val third = CaptureRequest(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        activity.webPermissions.onPermissionRequest(third)
        activity.webPermissions.close()
        assertTrue(third.denied)
        val unknown = CaptureRequest("notifications")
        activity.webPermissions.onPermissionRequest(unknown)
        assertTrue(unknown.denied)
    }

    @Test fun fileChooserRequiresNativeGestureAndRejectsLateResultsAfterCancellation() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val chooser = object : android.webkit.WebChromeClient.FileChooserParams() {
            override fun getMode() = MODE_OPEN_MULTIPLE
            override fun getAcceptTypes() = arrayOf("image/png", "image/jpeg")
            override fun isCaptureEnabled() = false
            override fun getTitle(): CharSequence? = null
            override fun getFilenameHint(): String? = null
            override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT)
        }
        val results = mutableListOf<Array<Uri>?>()
        activity.webPermissions.onShowFileChooser(view, { results += it }, chooser)
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick().also { ShadowLooper.idleMainLooper() }
        val launched = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, launched.intent.action)
        assertTrue(launched.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        activity.webPermissions.close()
        assertEquals(1, results.size)
        assertNull(results.single())
        activity.activityResultRegistry.dispatchResult(launched.requestCode, android.app.Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://documents/example")))
        assertEquals(1, results.size)
    }

    @Test fun microphoneRuntimeDenialDoesNotPersistSiteGrant() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        awaitWebView(activity)
        Shadows.shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        context.getSharedPreferences("android_permission_denials", 0).edit().clear().commit()
        val request = CaptureRequest(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        activity.webPermissions.onPermissionRequest(request)
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick().also { ShadowLooper.idleMainLooper() }
        val launched = Shadows.shadowOf(activity).nextStartedActivityForResult
        activity.activityResultRegistry.dispatchResult(launched.requestCode, android.app.Activity.RESULT_OK,
            Intent().putExtra(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS,
                arrayOf(android.Manifest.permission.RECORD_AUDIO))
                .putExtra(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                    intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED)))
        assertTrue(request.denied)
        val second = CaptureRequest(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        activity.webPermissions.onPermissionRequest(second)
        assertTrue(permissionDialog().isShowing)
        activity.webPermissions.close()
    }

    @Test fun cancellationKeepsOtherQueuedCapabilitiesAndDismissalDoesNotRememberDeny() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        awaitWebView(activity)
        val first = CaptureRequest(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val second = CaptureRequest(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        activity.webPermissions.onPermissionRequest(first)
        activity.webPermissions.onPermissionRequest(second)
        activity.webPermissions.onPermissionRequestCanceled(first)
        assertFalse(first.denied)
        assertFalse(second.denied)
        assertTrue(permissionDialog().isShowing)
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(second.denied)
        val results = mutableListOf<Boolean>()
        activity.webPermissions.onGeolocationPermissionsShowPrompt("https://example.com") { _, allow, _ -> results += allow }
        permissionDialog().cancel()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf(false), results)
        assertNull(store.permissionDecision(app.id, app.profileGeneration, "https://example.com", PermissionCapability.GEOLOCATION))
        activity.webPermissions.onGeolocationPermissionsShowPrompt("https://example.com") { _, _, _ -> }
        val camera = CaptureRequest(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        activity.webPermissions.onPermissionRequest(camera)
        activity.webPermissions.onGeolocationPermissionsHidePrompt()
        assertFalse(camera.denied)
        assertTrue(permissionDialog().isShowing)
        activity.webPermissions.close()
    }

    @Test fun rotationDisposesOldControllerAndIgnoresItsLateRequests() {
        val app = addApp()
        val controller = controller(ShellActivity.intent(context, app.id))
        val activity = controller.get()
        val view = awaitWebView(activity)
        val old = activity.webPermissions
        val pending = CaptureRequest(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        old.onPermissionRequest(pending)
        controller.recreate()
        assertTrue(pending.denied)
        assertSame(view, awaitWebView(controller.get()))
        val stale = CaptureRequest(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        old.onPermissionRequest(stale)
        assertTrue(stale.denied)
        val results = mutableListOf<Boolean>()
        old.onGeolocationPermissionsShowPrompt("https://example.com") { _, allow, _ -> results += allow }
        assertEquals(listOf(false), results)
        assertSame(controller.get().webPermissions, Shadows.shadowOf(view).webChromeClient)
    }

    @Test fun rotationIgnoresOldRuntimeResultAndResumesQueue() {
        val app = addApp()
        val controller = controller(ShellActivity.intent(context, app.id))
        val activity = controller.get()
        awaitWebView(activity)
        context.getSharedPreferences("android_permission_denials", 0).edit().clear().commit()
        Shadows.shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        val old = CaptureRequest(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        activity.webPermissions.onPermissionRequest(old)
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        val launched = Shadows.shadowOf(activity).nextStartedActivityForResult
        controller.recreate()
        val recreated = controller.get()
        assertTrue(old.denied)
        val camera = CaptureRequest(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        recreated.webPermissions.onPermissionRequest(camera)
        assertNull(camera.allowed)
        recreated.activityResultRegistry.dispatchResult(launched.requestCode, android.app.Activity.RESULT_OK,
            Intent().putExtra(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS,
                arrayOf(android.Manifest.permission.RECORD_AUDIO))
                .putExtra(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                    intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED)))
        assertNull(old.allowed)
        assertTrue(permissionDialog().isShowing)
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(camera.denied)
    }

    @Test fun navigationAndProfileClearCancelPermissionCallbacks() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val pending = CaptureRequest(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        activity.webPermissions.onPermissionRequest(pending)
        Shadows.shadowOf(view).webViewClient.shouldOverrideUrlLoading(view, request("https://example.com/next", "GET"))
        assertTrue(pending.denied)
        val next = CaptureRequest(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        activity.webPermissions.onPermissionRequest(next)
        assertEquals(ClearResult.Cleared, onMain { services.profiles.clear(app.id) })
        assertTrue(next.denied)
        assertTrue(activity.isFinishing)
    }

    @Test fun combinedCaptureGrantsOnlyApprovedCapabilityAndRejectsInsecureOrigin() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        awaitWebView(activity)
        Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.CAMERA)
        var granted: Array<out String>? = null
        val combined = object : android.webkit.PermissionRequest() {
            override fun getOrigin() = Uri.parse("https://other.example:8443")
            override fun getResources() = arrayOf(RESOURCE_VIDEO_CAPTURE, RESOURCE_AUDIO_CAPTURE)
            override fun grant(resources: Array<out String>) { granted = resources }
            override fun deny() { fail("Approved camera should still be granted") }
        }
        activity.webPermissions.onPermissionRequest(combined)
        assertTrue(permissionDialog().findViewById<android.widget.TextView>(android.R.id.message)!!.text.contains("https://other.example:8443"))
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertNull(granted)
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertArrayEquals(arrayOf(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE), granted)
        var allowed: Boolean? = null
        activity.webPermissions.onGeolocationPermissionsShowPrompt("http://example.com") { _, allow, _ -> allowed = allow }
        assertEquals(false, allowed)
    }

    private fun addApp(generation: Long = 0, themeColor: Int? = null): ConfiguredApp {
        val app = ConfiguredApp(java.util.UUID.randomUUID().toString(), "App", NameSource.DISCOVERED,
            "https://example.com/", null, "https://example.com/start", "https://example.com/",
            profileGeneration = generation, themeColor = themeColor, createdAt = 1, updatedAt = 1)
        assertEquals(ConfigurationWriteResult.Success, onMain { repository.add(app) })
        return app
    }

    private fun controller(intent: Intent) =
        Robolectric.buildActivity(ShellActivity::class.java, intent).setup().also { controllers += it }

    private fun launch(intent: Intent): ShellActivity = controller(intent).get()

    private fun container(activity: ShellActivity) = activity.findViewById<FrameLayout>(R.id.shell_container)

    private fun awaitWebView(activity: ShellActivity): CountingWebView {
        await { container(activity).childCount > 0 && container(activity).getChildAt(0) is CountingWebView }
        return container(activity).getChildAt(0) as CountingWebView
    }

    private fun request(url: String, method: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = method
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }


    private fun taskDescription(activity: ShellActivity): ActivityManager.TaskDescription? =
        ReflectionHelpers.getField(activity, "mTaskDescription")

    private fun ShellServices.shellsFor(appId: String): Int =
        ReflectionHelpers.getField<Map<String, List<*>>>(shells, "closers")[appId]?.size ?: 0

    private fun <T> onMain(block: suspend () -> T): T {
        var result: Result<T>? = null
        MainScope().launch(Dispatchers.Main) { result = runCatching { block() } }
        await { result != null }
        return result!!.getOrThrow()
    }

    private fun settle() {
        repeat(5) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    private fun await(condition: () -> Boolean) {
        repeat(200) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Condition not reached")
    }

    class CountingWebView(context: Context) : WebView(context) {
        var loads = 0
        override fun loadUrl(url: String) {
            loads++
            super.loadUrl(url)
        }
    }

    private class Backend : WebProfileBackend {
        val bound = mutableListOf<String>()
        val deleteCalls = mutableListOf<String>()
        val thirdParty = mutableMapOf<WebView, Boolean>()
        val views = mutableListOf<CountingWebView>()
        var failCreateAfter: Int? = null
        var onDelete: (String) -> Unit = {}
        var onThirdParty: (WebView) -> Unit = {}

        override fun deleteProfile(name: String): Boolean {
            deleteCalls += name
            onDelete(name)
            return true
        }

        override fun profileExists(name: String): Boolean = true

        override fun createBoundWebView(context: Context, name: String): WebView {
            if (views.size >= (failCreateAfter ?: Int.MAX_VALUE)) throw IllegalStateException("bind failed")
            bound += name
            return CountingWebView(context).also { views += it }
        }

        override fun setThirdPartyCookies(webView: WebView, allow: Boolean) {
            onThirdParty(webView)
            thirdParty[webView] = allow
        }
    }

    private companion object {
        val PROVIDER = ProviderKey("com.google.android.webview", "130.0.1", 28, "x86_64")
    }
}
