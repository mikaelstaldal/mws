package nu.staldal.mws

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.ComponentName
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
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
        await { container(activity).childCount == 1 }
        return container(activity).getChildAt(0) as CountingWebView
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
        var onDelete: (String) -> Unit = {}
        var onThirdParty: (WebView) -> Unit = {}

        override fun deleteProfile(name: String): Boolean {
            deleteCalls += name
            onDelete(name)
            return true
        }

        override fun createBoundWebView(context: Context, name: String): WebView {
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
