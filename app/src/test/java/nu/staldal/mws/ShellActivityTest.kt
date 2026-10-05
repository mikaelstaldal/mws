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
@Config(sdk = [31])
class ShellActivityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: ConfigurationDatabase
    private lateinit var repository: ConfigurationRepository
    private lateinit var store: MaintenanceStore
    private lateinit var services: ShellServices
    private val backend = Backend()
    private var compatible = true
    private var basicAuthProbe = true
    private val controllers = mutableListOf<ActivityController<*>>()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ConfigurationDatabase::class.java).build()
        repository = ConfigurationRepository(database)
        store = MaintenanceStore(context)
        store.createInstallationSentinel()
        val compatibility = ProviderCompatibility(
            { ProviderSnapshot(PROVIDER, compatible) },
            { key -> ProviderSelfTestResult(key, true, true, basicAuthProbe, true, true, true) },
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

    @Test fun manifestBackgroundIsOnlyUsedBeforeVisiblePaintAndNotOnReattach() {
        val app = addApp(backgroundColor = android.graphics.Color.RED)
        val controller = controller(ShellActivity.intent(context, app.id))
        val activity = controller.get()
        val webView = awaitWebView(activity)
        assertEquals(android.graphics.Color.RED,
            webView.canvasColor)
        Shadows.shadowOf(webView).webViewClient.onPageCommitVisible(webView, app.launchUrl)
        assertEquals(android.graphics.Color.WHITE,
            webView.canvasColor)
        controller.configurationChange()
        val retained = awaitWebView(controller.get())
        assertSame(webView, retained)
        assertEquals(android.graphics.Color.WHITE,
            retained.canvasColor)
    }

    @Suppress("DEPRECATION") // ShadowWebView.setCanGoBack
    @Test fun currentUrlRequiresCommittedIdlePageAndBackMarksNavigationPending() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        assertNull(services.shells.currentUrl(app.id))
        client.onPageCommitVisible(view, app.launchUrl)
        assertEquals(app.launchUrl, services.shells.currentUrl(app.id))
        // Model the WebView URL changes made by same-document navigation, without a new commit.
        view.loadUrl("https://example.com/next")
        assertEquals("https://example.com/next", services.shells.currentUrl(app.id))
        view.loadUrl("https://other.example/")
        assertNull(services.shells.currentUrl(app.id))
        view.loadUrl(app.launchUrl)
        Shadows.shadowOf(view).setCanGoBack(true)
        val holder = ReflectionHelpers.getField<ShellHolder>(activity, "holder")
        holder.goBack()
        assertNull(services.shells.currentUrl(app.id))
        client.onPageStarted(view, app.launchUrl, null)
        client.onPageCommitVisible(view, app.launchUrl)
        assertEquals(app.launchUrl, services.shells.currentUrl(app.id))
        client.onReceivedError(view, request(app.launchUrl, "GET"), null)
        assertNull(services.shells.currentUrl(app.id))
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

    @Test fun adHocLaunchUsesDisposableProfileAndLeavesNoConfiguration() {
        val intent = ShellActivity.adHocIntent(context, "https://example.com/quick")
        assertEquals("https://example.com/quick", ShellActivity.adHocUrlOf(intent))
        assertNull(intent.extras)
        assertNotNull(ShellActivity.appIdOf(intent))
        val activity = launch(intent)
        val view = awaitWebView(activity)
        assertEquals("https://example.com/quick", view.url)
        await { taskDescription(activity)?.label == "example.com" }
        assertTrue(backend.bound.single().startsWith("mws-disposable-"))
        assertTrue(onMain { repository.ordered() }.isEmpty())
        controllers.last().pause().stop().destroy()
        await { backend.deleteCalls.contains(backend.bound.single()) }
        assertNull(ShellActivity.adHocUrlOf(intent))
    }

    @Test fun adHocIntentRejectsInvalidUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellActivity.adHocIntent(context, "http://example.com/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellActivity.adHocIntent(context, "https://user@example.com/")
        }
    }

    @Test fun adHocLaunchNeverRequestsMyPass() {
        installMyPass()
        val activity = launch(ShellActivity.adHocIntent(context, "https://example.com/private"))
        val view = awaitWebView(activity)
        val holder = ReflectionHelpers.getField<ShellHolder>(activity, "holder")
        assertNull(ReflectionHelpers.getField<FormFillEngine?>(holder, "formFill"))
        Shadows.shadowOf(view).webViewClient.shouldInterceptRequest(view,
            request("https://example.com/private", "GET"))
        val answer = Answer()
        basicAuth(activity).challenge(answer, "example.com", "Private")
        assertEquals(listOf("cancel"), answer.calls)
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
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

    @Suppress("DEPRECATION") // TaskDescription.icon has no public replacement for a bitmap icon
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

    @Test fun pullToRefreshIsBlockedUntilLoadedAndWheneverPageCanScrollUp() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val refresh = container(activity).parent as androidx.swiperefreshlayout.widget.SwipeRefreshLayout
        assertTrue(refresh.canChildScrollUp())
        Shadows.shadowOf(view).webViewClient.onPageCommitVisible(view, app.launchUrl)
        assertFalse(refresh.canChildScrollUp())
        view.canScrollUp = true
        assertTrue(refresh.canChildScrollUp())
        fun intercept(action: Int, y: Float): Boolean {
            val event = android.view.MotionEvent.obtain(0, 10, action, 100f, y, 0)
            return try { refresh.onInterceptTouchEvent(event) } finally { event.recycle() }
        }
        assertFalse(intercept(android.view.MotionEvent.ACTION_DOWN, 100f))
        assertFalse(intercept(android.view.MotionEvent.ACTION_MOVE, 400f))
        view.canScrollUp = false
        assertFalse(intercept(android.view.MotionEvent.ACTION_MOVE, 500f))
        view.canScrollUp = true
        intercept(android.view.MotionEvent.ACTION_UP, 500f)
        val owner = ReflectionHelpers.getField<ShellHolder>(activity, "holder")
        owner.reload()
        assertEquals(0, view.reloads)
        view.canScrollUp = false
        assertFalse(refresh.canChildScrollUp())
        assertFalse(intercept(android.view.MotionEvent.ACTION_DOWN, 100f))
        assertTrue(intercept(android.view.MotionEvent.ACTION_MOVE, 400f))
        intercept(android.view.MotionEvent.ACTION_CANCEL, 400f)
        refresh.isRefreshing = true
        ReflectionHelpers.getField<androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener>(
            refresh, "mListener").onRefresh()
        assertFalse(refresh.isRefreshing)
        assertEquals(1, view.reloads)
        assertEquals(1, view.loads)
    }

    @Test fun horizontalSwipeAtPageTopIsLeftToThePage() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        Shadows.shadowOf(view).webViewClient.onPageCommitVisible(view, app.launchUrl)
        val refresh = container(activity).parent as androidx.swiperefreshlayout.widget.SwipeRefreshLayout
        val slop = android.view.ViewConfiguration.get(activity).scaledTouchSlop
        fun intercept(action: Int, x: Float, y: Float): Boolean {
            val event = android.view.MotionEvent.obtain(0, 10, action, x, y, 0)
            return try { refresh.onInterceptTouchEvent(event) } finally { event.recycle() }
        }
        assertFalse(intercept(android.view.MotionEvent.ACTION_DOWN, 100f, 100f))
        // Sideways with downward drift past the slop: SwipeRefreshLayout alone would take this.
        assertFalse(intercept(android.view.MotionEvent.ACTION_MOVE, 100f + 4 * slop, 100f + 2 * slop))
        assertFalse(intercept(android.view.MotionEvent.ACTION_MOVE, 100f + 4 * slop, 400f))
        intercept(android.view.MotionEvent.ACTION_UP, 100f + 4 * slop, 400f)
        // The next gesture decides its axis afresh.
        assertFalse(intercept(android.view.MotionEvent.ACTION_DOWN, 100f, 100f))
        assertTrue(intercept(android.view.MotionEvent.ACTION_MOVE, 100f + slop, 100f + 4 * slop))
        intercept(android.view.MotionEvent.ACTION_CANCEL, 100f + slop, 100f + 4 * slop)
        assertEquals(0, view.reloads)
    }

    @Test fun pullToRefreshFollowsTheRemainingFingerAndYieldsToAClaimingChild() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        Shadows.shadowOf(view).webViewClient.onPageCommitVisible(view, app.launchUrl)
        val refresh = container(activity).parent as androidx.swiperefreshlayout.widget.SwipeRefreshLayout
        val slop = android.view.ViewConfiguration.get(activity).scaledTouchSlop
        fun intercept(action: Int, vararg pointers: Pair<Int, Pair<Float, Float>>): Boolean {
            val properties = pointers.map { (id, _) ->
                android.view.MotionEvent.PointerProperties().apply { this.id = id }
            }.toTypedArray()
            val coords = pointers.map { (_, xy) ->
                android.view.MotionEvent.PointerCoords().apply { x = xy.first; y = xy.second }
            }.toTypedArray()
            val event = android.view.MotionEvent.obtain(0, 10, action, pointers.size, properties, coords,
                0, 0, 1f, 1f, 0, 0, 0, 0)
            return try { refresh.onInterceptTouchEvent(event) } finally { event.recycle() }
        }
        fun pointerAction(action: Int, index: Int) =
            action or (index shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val far = 100f + 10 * slop
        assertFalse(intercept(android.view.MotionEvent.ACTION_DOWN, 0 to (100f to 100f)))
        assertFalse(intercept(pointerAction(android.view.MotionEvent.ACTION_POINTER_DOWN, 1),
            0 to (100f to 100f), 1 to (far to 100f)))
        assertFalse(intercept(pointerAction(android.view.MotionEvent.ACTION_POINTER_UP, 0),
            0 to (100f to 100f), 1 to (far to 100f)))
        // The second finger moves straight down from where it was, not sideways from the first.
        assertTrue(intercept(android.view.MotionEvent.ACTION_MOVE, 1 to (far to 100f + 4 * slop)))
        intercept(android.view.MotionEvent.ACTION_CANCEL, 1 to (far to 100f + 4 * slop))

        assertFalse(intercept(android.view.MotionEvent.ACTION_DOWN, 0 to (100f to 100f)))
        refresh.requestDisallowInterceptTouchEvent(true)
        assertFalse(intercept(android.view.MotionEvent.ACTION_MOVE, 0 to (100f to 100f + 4 * slop)))
        intercept(android.view.MotionEvent.ACTION_UP, 0 to (100f to 100f + 4 * slop))
        assertEquals(0, view.reloads)
    }

    @Test fun refreshCannotReplaceAnAcceptedNavigationOrRepeatAnInFlightReload() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        val refresh = container(activity).parent as androidx.swiperefreshlayout.widget.SwipeRefreshLayout
        val owner = ReflectionHelpers.getField<ShellHolder>(activity, "holder")
        assertFalse(client.shouldOverrideUrlLoading(view, request("https://example.com/next", "GET")))
        assertTrue(refresh.canChildScrollUp())
        owner.reload()
        assertEquals(0, view.reloads)
        client.onPageStarted(view, "https://example.com/next", null)
        client.onPageCommitVisible(view, "https://example.com/next")
        assertFalse(refresh.canChildScrollUp())
        owner.reload()
        owner.reload()
        assertEquals(1, view.reloads)
        assertTrue(refresh.canChildScrollUp())
    }

    @Test fun explicitReloadPreparesFormFillButCannotRearmBasicAuthentication() {
        installMyPass()
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        Shadows.shadowOf(view).webViewClient.onPageStarted(view, app.launchUrl, null)
        Shadows.shadowOf(view).webViewClient.onPageCommitVisible(view, app.launchUrl)
        val owner = ReflectionHelpers.getField<ShellHolder>(activity, "holder")
        val engine = ReflectionHelpers.getField<FormFillEngine>(owner, "formFill")
        assertFalse(ReflectionHelpers.getField<Boolean>(engine, "prepared"))
        owner.reload()
        assertTrue(ReflectionHelpers.getField<Boolean>(engine, "prepared"))
        Shadows.shadowOf(view).webViewClient.shouldInterceptRequest(view, request(app.launchUrl, "GET"))
        basicAuth(activity).challenge(Answer(), "example.com", "Private")
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
        assertEquals(1, view.reloads)
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

    @Test fun externalLinkOpensMatchedTaskAndReusesItsWebView() {
        val source = addApp()
        val target = addApp().let {
            it.copy(configuredUrl = "https://other.example/", launchUrl = "https://other.example/start",
                scope = "https://other.example/")
        }
        assertEquals(ConfigurationWriteResult.Success, onMain { repository.update(target) })
        val sourceActivity = launch(ShellActivity.intent(context, source.id))
        val targetController = controller(ShellActivity.intent(context, target.id))
        val targetView = awaitWebView(targetController.get())
        val link = "https://other.example/page?foo=1"

        sourceActivity.openExternalLink(link)
        await { Shadows.shadowOf(sourceActivity).peekNextStartedActivity() != null }
        val routed = Shadows.shadowOf(sourceActivity).nextStartedActivity
        assertEquals("mws-app:${target.id}", routed.dataString)
        assertFalse(routed.toUri(0).contains(link))
        targetController.newIntent(routed)
        assertEquals(link, targetView.url)
        assertEquals(2, targetView.loads)
        assertSame(targetView, awaitWebView(targetController.get()))
        assertEquals(2, backend.bound.size)
    }

    @Test fun externalLinkInitialLaunchAndExpiredToken() {
        val target = addApp()
        val link = "https://example.com/deep"
        val intent = ShellActivity.linkIntent(context, target.id, link)
        val first = launch(intent)
        assertEquals(link, awaitWebView(first).url)
        // Reusing an already consumed token (or restoring after process death) loads the saved URL.
        val second = launch(intent)
        assertEquals(target.launchUrl, awaitWebView(second).url)
    }

    @Test fun externalLinkOutsideTargetScopeFallsBackToBrowser() {
        val target = addApp()
        installBrowser("org.example.browser")
        val activity = launch(ShellActivity.linkIntent(context, target.id, "https://other.example/page"))
        assertEquals(target.launchUrl, awaitWebView(activity).url)
        val fallback = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, fallback.action)
        assertEquals("https://other.example/page", fallback.dataString)
    }

    @Test fun externalLinkTokenCannotBeUsedForAnotherApp() {
        val original = addApp()
        val other = addApp()
        val intent = ShellActivity.linkIntent(context, original.id, "https://example.com/deep")
            .setData(Uri.parse("mws-app:${other.id}"))
        val activity = launch(intent)
        assertEquals(other.launchUrl, awaitWebView(activity).url)
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }

    @Test fun externalLinkWithSensitiveParameterNeedsConsentBeforeCreatingTask() {
        val source = addApp()
        val target = addApp().copy(configuredUrl = "https://other.example/",
            launchUrl = "https://other.example/start", scope = "https://other.example/")
        assertEquals(ConfigurationWriteResult.Success, onMain { repository.update(target) })
        val activity = launch(ShellActivity.intent(context, source.id))
        awaitWebView(activity)
        activity.openExternalLink("https://other.example/page?token=secret")
        await { org.robolectric.shadows.ShadowDialog.getLatestDialog() is androidx.appcompat.app.AlertDialog }
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        settle()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }

    @Suppress("DEPRECATION") // ShadowWebView.setCanGoBack
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
            activity.findViewById<android.widget.TextView>(R.id.shell_error_message)?.text ==
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

    @Suppress("DEPRECATION") // ShadowWebView.setCanGoBack
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
            activity.findViewById<android.widget.TextView>(R.id.shell_error_message)?.text)
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

    @Suppress("DEPRECATION") // RenderProcessGoneDetail() is only meant for the platform
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

    @Suppress("DEPRECATION") // RenderProcessGoneDetail() is only meant for the platform
    @Test fun externalLinkDuringRendererCrashLoadsInRecoveredProfile() {
        val app = addApp()
        val controller = controller(ShellActivity.intent(context, app.id))
        val first = awaitWebView(controller.get())
        Shadows.shadowOf(first).webViewClient.onRenderProcessGone(first,
            object : RenderProcessGoneDetail() {
                override fun didCrash() = true
                override fun rendererPriorityAtExit() = 0
            })
        val destination = "https://example.com/deep"
        controller.newIntent(ShellActivity.linkIntent(context, app.id, destination))
        await { backend.views.size == 2 }
        val second = awaitWebView(controller.get())
        assertEquals(destination, Shadows.shadowOf(second).lastLoadedUrl)
        assertEquals(listOf(ProfileNames.app(app.id, 0), ProfileNames.app(app.id, 0)), backend.bound)
    }

    @Suppress("DEPRECATION") // RenderProcessGoneDetail() is only meant for the platform
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

    @Test fun outOfScopeHttpsLinkStaysInTheShell() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        assertFalse(client.shouldOverrideUrlLoading(view, request("https://other.example/page", "GET")))
        assertFalse(client.shouldOverrideUrlLoading(view,
            request("https://login.other.example/cb", "GET", redirect = true, gesture = false)))
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertNull(ShadowToast.getLatestToast())
    }

    @Test fun cleartextLinksRedirectsAndPopupsAreRejectedKeepingThePage() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        for (request in listOf(request("http://example.com/", "GET"),
            request("http://example.com/", "GET", redirect = true, gesture = false))) {
            ShadowToast.reset()
            assertTrue(client.shouldOverrideUrlLoading(view, request))
            assertEquals(context.getString(R.string.shell_https_required), ShadowToast.getTextOfLatestToast())
        }
        assertNull(activity.findViewById<Button>(R.id.shell_retry_button))
        assertEquals(1, view.loads)
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }

    @Test fun cleartextRedirectOfTheInitialLoadShowsHttpsRequiredError() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        assertTrue(Shadows.shadowOf(view).webViewClient.shouldOverrideUrlLoading(view,
            request("http://example.com/start", "GET", redirect = true, gesture = false)))
        assertEquals(context.getString(R.string.shell_https_required),
            activity.findViewById<android.widget.TextView>(R.id.shell_error_message)?.text)
    }

    @Test fun cleartextPostIsInterceptedBeforeTransmissionAndRetryDoesNotReplayIt() =
        assertInterceptedPostRetry(commitBeforeFailure = false)

    @Test fun cleartextCommitBeforePostedFailureStillRetriesTheLastSafeGet() =
        assertInterceptedPostRetry(commitBeforeFailure = true)

    private fun assertInterceptedPostRetry(commitBeforeFailure: Boolean) {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        val safe = "https://example.com/safe"
        client.shouldOverrideUrlLoading(view, request(safe, "GET"))
        view.loadUrl(safe)
        client.onPageCommitVisible(view, safe)
        val rejected = "http://127.0.0.1/submit"
        val response = client.shouldInterceptRequest(view, request(rejected, "POST"))
        assertNotNull(response)
        assertEquals(403, response!!.statusCode)
        assertEquals(-1, response.data.read())
        if (commitBeforeFailure) {
            client.onPageStarted(view, rejected, null)
            client.onPageCommitVisible(view, rejected)
        }
        ShadowLooper.idleMainLooper()
        assertEquals(context.getString(R.string.shell_https_required), errorText(activity))
        val loads = view.loads
        activity.findViewById<Button>(R.id.shell_retry_button).performClick()
        val current = awaitWebView(activity)
        assertSame(view, current)
        assertEquals(loads + 1, current.loads)
        assertEquals(safe, Shadows.shadowOf(current).lastLoadedUrl)
    }

    @Test fun cleartextSubresourceIsBlockedWithoutFailingTheMainDocument() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val response = Shadows.shadowOf(view).webViewClient.shouldInterceptRequest(view,
            request("http://example.com/image", "GET", mainFrame = false))
        assertNotNull(response)
        ShadowLooper.idleMainLooper()
        assertNull(errorText(activity))
    }

    @Test fun cleartextPostFailureShowsHttpsRequiredAndRetryNeverReplaysIt() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        client.onReceivedError(view, request("http://example.com/submit", "POST"), null)
        assertEquals(context.getString(R.string.shell_https_required),
            activity.findViewById<android.widget.TextView>(R.id.shell_error_message)?.text)
        activity.findViewById<Button>(R.id.shell_retry_button).performClick()
        assertEquals(app.launchUrl, Shadows.shadowOf(view).lastLoadedUrl)
    }

    @Test fun dangerousAndUnknownSchemesAreBlockedAndNeverPassedToAndroid() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        for (url in listOf("javascript:alert(1)", "data:text/html,x", "file:///sdcard/a", "content://x/y",
            "intent://scan/#Intent;scheme=zxing;end", "custom-app:open")) {
            ShadowToast.reset()
            assertTrue(url, client.shouldOverrideUrlLoading(view, request(url, "GET")))
            assertEquals(context.getString(R.string.shell_scheme_blocked), ShadowToast.getTextOfLatestToast())
        }
        assertNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertEquals(1, view.loads)
    }

    @Test fun allowlistedSchemeIsDelegatedOnlyAfterConfirmationEveryTime() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        val url = "tel:+46701234567"
        assertTrue(client.shouldOverrideUrlLoading(view, request(url, "GET")))
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        var dialog = permissionDialog()
        assertTrue(dialog.isShowing)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url, started.dataString)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), started.categories)
        assertNull(started.extras)
        assertNull(started.component)

        // No permanent grant: the next tap asks again, and Cancel delegates nothing.
        assertTrue(client.shouldOverrideUrlLoading(view, request(url, "GET")))
        dialog = permissionDialog()
        assertTrue(dialog.isShowing)
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        settle()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }

    @Test fun bankIdAppLinkIsDelegatedAfterConfirmation() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        for (url in listOf("https://app.bankid.com/?autostarttoken=example&redirect=null",
            "bankid:///?autostarttoken=example&redirect=null")) {
            assertTrue(client.shouldOverrideUrlLoading(view, request(url, "GET")))
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
            val dialog = permissionDialog()
            assertEquals(context.getString(R.string.external_prompt_bankid),
                dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString())
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            assertFalse(dialog.isShowing)
            assertEquals(dialog, org.robolectric.shadows.ShadowDialog.getLatestDialog())
            val started = Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, started.action)
            assertEquals(url, started.dataString)
            assertEquals(setOf(Intent.CATEGORY_BROWSABLE), started.categories)
            assertNull(started.extras)
        }
        val extra = "https://app.bankid.com/?autostarttoken=example&session=private-value"
        assertTrue(client.shouldOverrideUrlLoading(view, request(extra, "GET")))
        val dialog = permissionDialog()
        val message = dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
        assertTrue(message.contains("“session”"))
        assertFalse(message.contains("private-value"))
        assertFalse(message.contains("autostarttoken"))
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
        assertEquals(dialog, org.robolectric.shadows.ShadowDialog.getLatestDialog())
        assertEquals(extra, Shadows.shadowOf(activity).nextStartedActivity.dataString)
    }

    @Test fun redirectedUngesturedOrSubframeExternalSchemesAreBlockedWithoutPrompt() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        for (request in listOf(request("mailto:a@example.com", "GET", redirect = true),
            request("sms:+4612345", "GET", gesture = false))) {
            ShadowToast.reset()
            assertTrue(client.shouldOverrideUrlLoading(view, request))
            assertEquals(context.getString(R.string.shell_external_blocked), ShadowToast.getTextOfLatestToast())
        }
        ShadowToast.reset()
        assertTrue(client.shouldOverrideUrlLoading(view, request("geo:1,2", "GET", mainFrame = false)))
        assertNull(ShadowToast.getLatestToast())
        assertNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }

    @Test fun credentialLikeExternalParametersNeedFreshRedactedConsent() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        val url = "mailto:a@example.com?subject=hi&token=s3cr3t"
        client.shouldOverrideUrlLoading(view, request(url, "GET"))
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        val warning = permissionDialog()
        val message = warning.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
        assertTrue(message.contains("“token”"))
        assertFalse(message.contains("s3cr3t"))
        warning.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
        assertEquals(url, Shadows.shadowOf(activity).nextStartedActivity.dataString)
    }

    @Test fun missingExternalHandlerShowsNonFatalError() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        Shadows.shadowOf(context as android.app.Application).checkActivities(true)
        client.shouldOverrideUrlLoading(view, request("market://details?id=x", "GET"))
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
        assertEquals(context.getString(R.string.shell_no_handler), ShadowToast.getTextOfLatestToast())
        assertFalse(activity.isFinishing)
    }

    @Test fun openInBrowserIsOfferedOnlyForAllowedHttpsLinksAndTransfersNothingElse() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        installBrowser("org.example.browser")
        for ((type, url) in listOf(android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE to "http://example.com/",
            android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE to "https://user@example.com/",
            android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE to "mailto:a@example.com",
            android.webkit.WebView.HitTestResult.IMAGE_TYPE to "https://example.com/a.png",
            android.webkit.WebView.HitTestResult.EDIT_TEXT_TYPE to null)) {
            assertTrue(url, contextMenu(activity, view, type, url).isEmpty())
        }
        val items = contextMenu(activity, view, android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE,
            "https://Other.Example/path")
        assertEquals(context.getString(R.string.open_in_browser), items.single().title.toString())
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        items.single().click()
        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://other.example/path", started.dataString)
        assertEquals("org.example.browser", started.`package`)
        assertNull(started.extras)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun openInBrowserAsksConsentForCredentialLikeParameters() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        installBrowser("org.example.browser")
        contextMenu(activity, view, android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE,
            "https://other.example/#/cb?access_token=abc").single().click()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        permissionDialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        settle()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }

    @Test fun blockedNavigationFromTheLoadingInitialDocumentKeepsItLoading() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageStarted(view, app.launchUrl, null)
        for (url in listOf("market://details?id=x", "intent://x#Intent;end", "http://example.com/")) {
            ShadowToast.reset()
            assertTrue(client.shouldOverrideUrlLoading(view, request(url, "GET", gesture = false)))
            assertNotNull(ShadowToast.getLatestToast())
        }
        assertNull(activity.findViewById<Button>(R.id.shell_retry_button))
        client.onPageCommitVisible(view, app.launchUrl)
        assertEquals(context.getString(R.string.shell_https_required), ShadowToast.getTextOfLatestToast())
    }

    @Test fun downloadInPlaceOfTheInitialLoadShowsTheUnsupportedError() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        Shadows.shadowOf(view).downloadListener.onDownloadStart(app.launchUrl, "ua", "attachment",
            "application/octet-stream", 1)
        assertEquals(context.getString(R.string.shell_download_unsupported),
            activity.findViewById<android.widget.TextView>(R.id.shell_error_message)?.text)
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }

    @Test fun doubleTapOnOpenDelegatesOnce() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        client.shouldOverrideUrlLoading(view, request("tel:123", "GET"))
        val open = permissionDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        open.performClick()
        open.performClick()
        settle()
        assertNotNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }

    @Test fun destroyingTheShellDismissesPendingDelegationWithoutStarting() {
        val app = addApp()
        val controller = controller(ShellActivity.intent(context, app.id))
        val activity = controller.get()
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        client.shouldOverrideUrlLoading(view, request("mailto:a@example.com?token=x", "GET"))
        val confirmation = permissionDialog()
        confirmation.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
        val consent = permissionDialog()
        assertTrue(consent.isShowing)
        controller.recreate()
        settle()
        assertFalse(consent.isShowing)
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertNull(Shadows.shadowOf(controller.get()).nextStartedActivity)
    }

    @Test fun openInBrowserWithoutBrowserShowsNonFatalError() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        contextMenu(activity, view, android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE,
            "https://other.example/").single().click()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertEquals(context.getString(R.string.shell_no_browser), ShadowToast.getTextOfLatestToast())
        assertFalse(activity.isFinishing)
    }

    @Test fun downloadsIncludingBlobAndDataAreUnsupportedAndNeverDelegated() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        Shadows.shadowOf(view).webViewClient.onPageCommitVisible(view, app.launchUrl)
        val listener = Shadows.shadowOf(view).downloadListener
        for (url in listOf("https://example.com/file.zip", "blob:https://example.com/id", "data:text/plain,x")) {
            ShadowToast.reset()
            listener.onDownloadStart(url, "ua", "attachment", "application/octet-stream", 1)
            assertEquals(context.getString(R.string.shell_download_unsupported), ShadowToast.getTextOfLatestToast())
        }
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertNull(activity.findViewById<Button>(R.id.shell_retry_button))
        assertFalse(activity.isFinishing)
    }

    @Test fun originReplacementClosesOldShellAndMyPassUsesOnlyNewUrl() {
        installMyPass()
        val app = addApp()
        val oldActivity = launch(ShellActivity.intent(context, app.id))
        val oldView = awaitWebView(oldActivity)
        val snapshot = app.copy(configuredUrl = "https://new.example/private",
            launchUrl = "https://new.example/private", scope = "https://new.example/")
        assertEquals(ConfigurationWriteResult.Success,
            onMain { services.profiles.changeOrigin(snapshot) })
        assertTrue(oldActivity.isFinishing)
        assertTrue(Shadows.shadowOf(oldView).wasDestroyCalled())
        val fresh = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(fresh)
        assertEquals(snapshot.launchUrl, Shadows.shadowOf(view).lastLoadedUrl)
        Shadows.shadowOf(view).webViewClient.shouldInterceptRequest(view,
            request(snapshot.launchUrl, "GET"))
        basicAuth(fresh).challenge(Answer(), "new.example", "New site")
        val lookup = Shadows.shadowOf(fresh).nextStartedActivityForResult
        assertEquals(snapshot.launchUrl, lookup.intent.getStringExtra(MyPassClient.EXTRA_URL))
        assertEquals(ProfileNames.app(app.id, 1), backend.bound.last())
    }

    @Test fun basicChallengeLooksUpExactUrlAndRealmPausesTimerAndSuppliesOnce() {
        installMyPass()
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        // The main document's own request does not disarm the gate.
        assertNull(client.shouldInterceptRequest(view, request(app.launchUrl, "GET")))
        val answer = Answer()
        basicAuth(activity).challenge(answer, "example.com", "Private area")
        val request = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertEquals(MyPassClient.ACTION, request.intent.action)
        assertEquals(MyPassClient.PACKAGE, request.intent.`package`)
        assertEquals(app.launchUrl, request.intent.getStringExtra(MyPassClient.EXTRA_URL))
        assertEquals("Private area", request.intent.getStringExtra(MyPassClient.EXTRA_REALM))
        // The initial-load timer is paused while MyPass is outstanding.
        ShadowLooper.idleMainLooper(45, TimeUnit.SECONDS)
        assertNull(activity.findViewById<Button>(R.id.shell_retry_button))
        Shadows.shadowOf(activity).receiveResult(request.intent, android.app.Activity.RESULT_OK, credential())
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("proceed:user:secret"), answer.calls)

        // A repeated challenge for the same navigation, URL and realm is a rejection.
        val repeat = Answer()
        basicAuth(activity).challenge(repeat, "example.com", "Private area")
        assertEquals(listOf("cancel"), repeat.calls)
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
        assertEquals(context.getString(R.string.shell_auth_rejected), errorText(activity))
    }

    @Test fun initialPageStartedDoesNotCancelEligibleTopLevelChallenge() {
        installMyPass()
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageStarted(view, app.launchUrl, null)
        client.shouldInterceptRequest(view, request(app.launchUrl, "GET"))
        basicAuth(activity).challenge(Answer(), "example.com", "Private")
        assertEquals(app.launchUrl, Shadows.shadowOf(activity).nextStartedActivityForResult
            .intent.getStringExtra(MyPassClient.EXTRA_URL))
    }

    @Test fun missingMyPassCancelsChallengeAndShowsAuthenticationUnavailable() {
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        Shadows.shadowOf(view).webViewClient.shouldInterceptRequest(view, request(app.launchUrl, "GET"))
        val answer = Answer()
        basicAuth(activity).challenge(answer, "example.com", "Private")
        assertEquals(listOf("cancel"), answer.calls)
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
        assertEquals(context.getString(R.string.shell_auth_unavailable), errorText(activity))
    }

    @Test fun cancelledMyPassLookupCancelsChallengeWithNonSensitiveError() {
        installMyPass()
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        Shadows.shadowOf(view).webViewClient.shouldInterceptRequest(view, request(app.launchUrl, "GET"))
        val answer = Answer()
        basicAuth(activity).challenge(answer, "example.com", "Private")
        val request = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertEquals(app.launchUrl, request.intent.getStringExtra(MyPassClient.EXTRA_URL))
        Shadows.shadowOf(activity).receiveResult(request.intent, android.app.Activity.RESULT_CANCELED, null)
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("cancel"), answer.calls)
        assertEquals(context.getString(R.string.shell_auth_unavailable), errorText(activity))
    }

    @Test fun belowFloorOrFailedProbeCancelsEveryChallengeWithoutMyPass() {
        installMyPass()
        basicAuthProbe = false
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        awaitWebView(activity)
        val answer = Answer()
        basicAuth(activity).challenge(answer, "example.com", "Private")
        assertEquals(listOf("cancel"), answer.calls)
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun iframeSubresourceAndLateChallengesAreCancelled() {
        installMyPass()
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        val auth = basicAuth(activity)
        // An iframe or early subresource request disarms before any challenge is answered.
        client.shouldInterceptRequest(view, request("https://example.com/frame", "GET", mainFrame = false))
        ShadowLooper.idleMainLooper()
        Answer().also { auth.challenge(it, "example.com", "Private"); assertEquals(listOf("cancel"), it.calls) }
        // A late challenge after commit.
        client.onPageCommitVisible(view, app.launchUrl)
        Answer().also { auth.challenge(it, "example.com", "Private"); assertEquals(listOf("cancel"), it.calls) }
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
        assertNull(activity.findViewById<Button>(R.id.shell_retry_button))
    }

    @Test fun staleIframeChallengeCannotUseLaterNavigationEvenAfterItsMainRequest() {
        installMyPass()
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.shouldInterceptRequest(view, request(app.launchUrl, "GET"))
        client.shouldInterceptRequest(view, request("https://example.com/frame", "GET", mainFrame = false))
        assertFalse(client.shouldOverrideUrlLoading(view, request("https://example.com/image-page", "GET")))
        client.shouldInterceptRequest(view, request("https://example.com/image-page", "GET"))
        val answer = Answer()
        basicAuth(activity).challenge(answer, "example.com", "Frame")
        assertEquals(listOf("cancel"), answer.calls)
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun sameOriginRedirectIsLookedUpButCrossOriginRedirectCannotInherit() {
        installMyPass()
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        val auth = basicAuth(activity)
        client.shouldInterceptRequest(view, request(app.launchUrl, "GET"))
        assertFalse(client.shouldOverrideUrlLoading(view,
            request("https://example.com/login", "GET", redirect = true, gesture = false)))
        auth.challenge(Answer(), "example.com", "Private")
        assertEquals("https://example.com/login", Shadows.shadowOf(activity).nextStartedActivityForResult
            .intent.getStringExtra(MyPassClient.EXTRA_URL))

        client.onPageCommitVisible(view, "https://example.com/login")
        assertFalse(client.shouldOverrideUrlLoading(view, request("https://example.com/account", "GET")))
        assertFalse(client.shouldOverrideUrlLoading(view,
            request("https://auth.other.example/login", "GET", redirect = true, gesture = false)))
        for (host in listOf("auth.other.example", "example.com")) {
            Answer().also { auth.challenge(it, host, "Private"); assertEquals(listOf("cancel"), it.calls) }
        }
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun noCredentialIsSuppliedToTlsErrorOrCleartextNavigation() {
        installMyPass()
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onReceivedSslError(view, ReflectionHelpers.callConstructor(SslErrorHandler::class.java),
            SslError(SslError.SSL_UNTRUSTED, ReflectionHelpers.newInstance(SslCertificate::class.java), app.launchUrl))
        Answer().also { basicAuth(activity).challenge(it, "example.com", "Private"); assertEquals(listOf("cancel"), it.calls) }

        val second = launch(ShellActivity.intent(context, addApp().id))
        val secondView = awaitWebView(second)
        val secondClient = Shadows.shadowOf(secondView).webViewClient
        secondClient.onPageCommitVisible(secondView, "https://example.com/start")
        assertTrue(secondClient.shouldOverrideUrlLoading(secondView, request("http://example.com/", "GET")))
        Answer().also { basicAuth(second).challenge(it, "example.com", "Private"); assertEquals(listOf("cancel"), it.calls) }
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
        assertNull(Shadows.shadowOf(second).nextStartedActivityForResult)
    }

    @Test fun newNavigationCancelsOutstandingChallengeAndIgnoresLateCredential() {
        installMyPass()
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.shouldInterceptRequest(view, request(app.launchUrl, "GET"))
        val answer = Answer()
        basicAuth(activity).challenge(answer, "example.com", "Private")
        val request = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertFalse(client.shouldOverrideUrlLoading(view, request("https://example.com/other", "GET")))
        assertEquals(listOf("cancel"), answer.calls)
        Shadows.shadowOf(activity).receiveResult(request.intent, android.app.Activity.RESULT_OK, credential())
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("cancel"), answer.calls)
    }

    @Test fun challengeFromARequestInFlightBeforeTheNavigationIsCancelled() {
        installMyPass()
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        val client = Shadows.shadowOf(view).webViewClient
        client.onPageCommitVisible(view, app.launchUrl)
        // A fetch from the committed page is still in flight when a link arms the gate.
        assertFalse(client.shouldOverrideUrlLoading(view, request("https://example.com/next", "GET")))
        Answer().also { basicAuth(activity).challenge(it, "example.com", "Private"); assertEquals(listOf("cancel"), it.calls) }
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun lookupResumesTheInitialTimerAfterwards() {
        installMyPass()
        val app = addApp()
        val activity = launch(ShellActivity.intent(context, app.id))
        val view = awaitWebView(activity)
        Shadows.shadowOf(view).webViewClient.shouldInterceptRequest(view, request(app.launchUrl, "GET"))
        ShadowLooper.idleMainLooper(20, TimeUnit.SECONDS)
        val answer = Answer()
        basicAuth(activity).challenge(answer, "example.com", "Private")
        val request = Shadows.shadowOf(activity).nextStartedActivityForResult
        ShadowLooper.idleMainLooper(50, TimeUnit.SECONDS)
        assertNull(activity.findViewById<Button>(R.id.shell_retry_button))
        Shadows.shadowOf(activity).receiveResult(request.intent, android.app.Activity.RESULT_OK, credential())
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)
        assertEquals(listOf("proceed:user:secret"), answer.calls)
        assertNull(activity.findViewById<Button>(R.id.shell_retry_button))
        ShadowLooper.idleMainLooper(6, TimeUnit.SECONDS)
        assertEquals(context.getString(R.string.shell_timeout), errorText(activity))
    }

    @Test fun activityRecreationCancelsOutstandingLookupWithErrorButKeepsTheNavigationArmed() {
        installMyPass()
        val app = addApp()
        val controller = controller(ShellActivity.intent(context, app.id))
        val view = awaitWebView(controller.get())
        Shadows.shadowOf(view).webViewClient.shouldInterceptRequest(view, request(app.launchUrl, "GET"))
        val answer = Answer()
        basicAuth(controller.get()).challenge(answer, "example.com", "Private")
        assertNotNull(Shadows.shadowOf(controller.get()).nextStartedActivityForResult)
        controller.recreate()
        assertEquals(listOf("cancel"), answer.calls)
        val recreated = controller.get()
        assertEquals(context.getString(R.string.shell_auth_unavailable), errorText(recreated))

        val second = launch(ShellActivity.intent(context, addApp().id))
        val secondView = awaitWebView(second)
        Shadows.shadowOf(secondView).webViewClient.shouldInterceptRequest(secondView, request(app.launchUrl, "GET"))
        // A navigation armed across a configuration change still gets its lookup.
        val holderController = controllers.last()
        holderController.recreate()
        val again = holderController.get() as ShellActivity
        basicAuth(again).challenge(Answer(), "example.com", "Private")
        assertNotNull(Shadows.shadowOf(again).nextStartedActivityForResult)
    }

    private class Answer : AuthAnswer {
        val calls = mutableListOf<String>()
        override fun proceed(username: String, password: String) { calls += "proceed:$username:$password" }
        override fun cancel() { calls += "cancel" }
    }

    private fun basicAuth(activity: ShellActivity): ShellBasicAuth =
        ReflectionHelpers.getField(ReflectionHelpers.getField<ShellHolder>(activity, "holder"), "basicAuth")

    private fun errorText(activity: ShellActivity): CharSequence? =
        activity.findViewById<android.widget.TextView>(R.id.shell_error_message)?.text

    private fun credential() = Intent()
        .putExtra(MyPassClient.EXTRA_RESULT_USERNAME, "user")
        .putExtra(MyPassClient.EXTRA_RESULT_PASSWORD, "secret")

    @Suppress("DEPRECATION") // ShadowPackageManager.addResolveInfoForIntent
    private fun installMyPass() {
        Shadows.shadowOf(context as android.app.Application).grantPermissions(MyPassClient.PERMISSION)
        val info = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = MyPassClient.PACKAGE
                name = "${MyPassClient.PACKAGE}.FetchActivity"
            }
        }
        Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(
            MyPassClient.requestIntent("https://example.invalid/"), info)
    }

    @Suppress("DEPRECATION") // ShadowPackageManager.addResolveInfoForIntent
    private fun installBrowser(packageName: String) {
        val info = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.Browser"
            }
        }
        Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE), info)
    }

    private fun contextMenu(activity: ShellActivity, view: WebView, type: Int, url: String?):
        List<org.robolectric.fakes.RoboMenuItem> {
        Shadows.shadowOf(view).setHitTestResult(org.robolectric.shadows.ShadowWebView.createHitTestResult(type, url))
        val menu = org.robolectric.fakes.RoboMenu(activity)
        val contextMenu = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(android.view.ContextMenu::class.java)) { _, method, args ->
            if (method.declaringClass == android.view.ContextMenu::class.java) null
            else method.invoke(menu, *(args ?: emptyArray()))
        } as android.view.ContextMenu
        activity.onCreateContextMenu(contextMenu, view, null)
        return (0 until menu.size()).map { menu.getItem(it) as org.robolectric.fakes.RoboMenuItem }
    }

    private fun addApp(generation: Long = 0, themeColor: Int? = null, backgroundColor: Int? = null): ConfiguredApp {
        val app = ConfiguredApp(java.util.UUID.randomUUID().toString(), "App", NameSource.DISCOVERED,
            "https://example.com/", null, "https://example.com/start", "https://example.com/",
            profileGeneration = generation, themeColor = themeColor, backgroundColor = backgroundColor, createdAt = 1, updatedAt = 1)
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

    private fun request(url: String, method: String, mainFrame: Boolean = true, redirect: Boolean = false,
                        gesture: Boolean = true) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = redirect
        override fun hasGesture() = gesture
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
        var canvasColor: Int? = null
        override fun setBackgroundColor(color: Int) {
            canvasColor = color
            super.setBackgroundColor(color)
        }
        var canScrollUp = false
        override fun canScrollVertically(direction: Int): Boolean = direction < 0 && canScrollUp
        var reloads = 0
        override fun reload() {
            reloads++
            super.reload()
        }
        var loads = 0
        override fun loadUrl(url: String) {
            loads++
            super.loadUrl(url)
        }
    }

    private class Backend : WebProfileBackend {
        override fun basicAuthSafety(webView: WebView): () -> Boolean = { true }
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
