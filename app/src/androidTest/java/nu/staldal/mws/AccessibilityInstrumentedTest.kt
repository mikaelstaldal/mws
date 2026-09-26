package nu.staldal.mws

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.ContextWrapper
import java.io.File
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertTrue
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Before
import org.junit.After
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import okhttp3.mockwebserver.MockResponse
import java.util.UUID
import org.junit.runner.RunWith

/** Requires installed single/multi widgets for the configuration screen checks. */
@RunWith(AndroidJUnit4::class)
class AccessibilityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private lateinit var database: ConfigurationDatabase
    private lateinit var store: MaintenanceStore
    private lateinit var services: ShellServices
    private lateinit var fixtureDirectory: File
    private var compatible = false
    private val fixtureIds = mutableListOf<String>()

    @Before fun prepare() {
        database = Room.inMemoryDatabaseBuilder(context, ConfigurationDatabase::class.java).build()
        fixtureDirectory = File(context.cacheDir, "accessibility-${UUID.randomUUID()}").apply { mkdirs() }
        store = MaintenanceStore(object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = fixtureDirectory
        })
        store.createInstallationSentinel()
        val snapshot = AndroidProviderSnapshotSource(context).snapshot()
        val key = snapshot.key
        val compatibility = ProviderCompatibility(
            { snapshot.copy(multiProfile = compatible && snapshot.multiProfile) },
            { k -> ProviderSelfTestResult(k, true, true, true, true, true, true) },
            floors = key?.let { mapOf(it.packageName to listOf(0)) } ?: emptyMap())
        val repository = ConfigurationRepository(database)
        val shells = LiveShellRegistry()
        val icons = IconStore(context)
        val profiles = ProfileManager(repository, store, compatibility, AndroidWebProfileBackend(), shells,
            object : AppNativeData {
                override fun deleteIcons(appId: String) {}
                override fun widgetsChanged() {}
            })
        services = ShellServices(repository, compatibility, shells, profiles, icons)
        ShellServices.install(services)
    }

    @After fun cleanup() {
        runBlocking { fixtureIds.forEach { services.profiles.remove(it) } }
        ShellServices.install(null)
        database.close()
        store.close()
        fixtureDirectory.deleteRecursively()
    }

    private fun check(intent: Intent, dialog: Boolean = false, readyText: Int? = null, reorder: Boolean = false) {
        val activity = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.waitForIdleSync()
            if (readyText != null || activity is EditorActivity) {
                fun contains(view: View): Boolean =
                    (view.isShown && view is TextView && view.text.toString() == context.getString(readyText!!)) ||
                        (view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) })
                var ready = false
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        ready = if (activity is EditorActivity) activity.findViewById<View>(R.id.editor_save).isEnabled
                            else contains(activity.window.decorView)
                    }
                    if (!ready) SystemClock.sleep(50)
                }
                assertTrue("Native screen did not become ready", ready)
            }
            if (dialog) onView(withId(R.id.editor_third_party_cookies)).perform(scrollTo(), click())
            AccessibilityChecks.enable().setRunChecksFromRootView(true)
            try {
                if (reorder) onView(withId(R.id.reorder_apps)).perform(click())
                onView(isRoot()).check(AccessibilityChecks.accessibilityAssertion())
                if (activity is MultiWidgetConfigureActivity) {
                    onView(withText(R.string.widget_selected_apps)).perform(click())
                    onView(isRoot()).check(AccessibilityChecks.accessibilityAssertion())
                }
            } finally { AccessibilityChecks.disable() }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun mainScreen() = check(Intent(context, MainActivity::class.java), readyText = R.string.empty_state)
    @Test fun populatedMainAndReorder() = runBlocking {
        repeat(2) { index ->
            val id = UUID.randomUUID().toString()
            fixtureIds += id
            services.repository.add(ConfiguredApp(id = id, name = "Accessibility fixture $index",
                nameSource = NameSource.USER_EDITED, configuredUrl = "https://example.com/",
                finalUrl = null, launchUrl = "https://example.com/", scope = "https://example.com/",
                sortPosition = index, createdAt = 1, updatedAt = 1))
        }
        services.repository.reserveNextGeneration(fixtureIds.first())
        check(Intent(context, MainActivity::class.java), readyText = R.string.reorder_apps)
        check(Intent(context, MainActivity::class.java), readyText = R.string.reorder_apps, reorder = true)
    }
    @Test fun editor() = check(EditorActivity.addIntent(context))
    @Test fun confirmationDialog() = check(EditorActivity.addIntent(context), dialog = true)
    @Test fun providerError() = check(Intent(context, ProviderErrorActivity::class.java), readyText = R.string.provider_settings)
    @Test fun shellError() = runBlocking {
        val snapshot = AndroidProviderSnapshotSource(context).snapshot()
        assumeTrue(snapshot.key != null && snapshot.multiProfile)
        compatible = true
        LocalHttpsServer().use { server ->
            server.dispatch { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            val id = UUID.randomUUID().toString()
            val url = server.url.toString()
            val app = ConfiguredApp(id = id, name = "Accessibility fixture", nameSource = NameSource.USER_EDITED,
                configuredUrl = url, finalUrl = null, launchUrl = url, scope = url,
                sortPosition = 0, createdAt = 1, updatedAt = 1)
            fixtureIds += id
            services.repository.add(app)
            check(ShellActivity.intent(context, id), readyText = R.string.shell_retry)
        }
    }

    private fun widget(type: Class<out Activity>, ids: Set<Int>) {
        runBlocking {
            val id = UUID.randomUUID().toString()
            fixtureIds += id
            services.repository.add(ConfiguredApp(id = id, name = "Widget accessibility fixture",
                nameSource = NameSource.USER_EDITED, configuredUrl = "https://example.com/",
                finalUrl = null, launchUrl = "https://example.com/", scope = "https://example.com/",
                sortPosition = 0, createdAt = 1, updatedAt = 1))
        }
        check(Intent(context, type).setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
            ids.firstOrNull() ?: error("Install a widget of this type before testing accessibility")),
            readyText = if (type == SingleWidgetConfigureActivity::class.java) R.string.widget_pick_app else R.string.widget_configure)
    }
    @Test fun singleWidgetConfiguration() = widget(SingleWidgetConfigureActivity::class.java, SingleWidget.installed(context))
    @Test fun multiWidgetConfiguration() = widget(MultiWidgetConfigureActivity::class.java, MultiWidget.installed(context))
}
