package nu.staldal.mws

import android.content.Context
import android.widget.TextView
import androidx.room.Room
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class ProviderErrorActivityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: ConfigurationDatabase
    private lateinit var store: MaintenanceStore
    private var snapshot = ProviderSnapshot(PROVIDER, true)

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ConfigurationDatabase::class.java).build()
        store = MaintenanceStore(context)
        val repository = ConfigurationRepository(database)
        val compatibility = ProviderCompatibility({ snapshot }, { null },
            floors = mapOf(PROVIDER.packageName to listOf(100)))
        val shells = LiveShellRegistry()
        val profiles = ProfileManager(repository, store, compatibility, object : WebProfileBackend {
            override fun deleteProfile(name: String) = false
            override fun profileExists(name: String) = false
            override fun createBoundWebView(context: Context, name: String): android.webkit.WebView =
                error("Provider error screen must not load a profile")
            override fun setThirdPartyCookies(webView: android.webkit.WebView, allow: Boolean) = Unit
        }, shells, object : AppNativeData {
            override fun deleteIcons(appId: String) = Unit
            override fun widgetsChanged() = Unit
        })
        ShellServices.install(ShellServices(repository, compatibility, shells, profiles, IconStore(context)))
    }

    @After fun tearDown() {
        ShellServices.install(null)
        database.close()
        store.close()
    }

    @Test fun everyBlockReasonHasDistinctMessage() {
        val messages = ProviderBlockReason.entries.map { context.getString(it.messageRes) }
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(context.getString(ProviderBlockReason.SELF_TEST_PENDING.messageRes).contains("Restart"))
        assertTrue(context.getString(ProviderBlockReason.NO_PROVIDER.messageRes).contains("Install or enable"))
    }

    @Test fun showsProviderVersionAndBlockReason() {
        for ((provider, reason) in listOf(
            PROVIDER to ProviderBlockReason.SELF_TEST_PENDING,
            PROVIDER.copy(packageName = "com.example.webview") to ProviderBlockReason.UNLISTED_PROVIDER,
            PROVIDER.copy(version = "99.0") to ProviderBlockReason.BELOW_FLOOR)) {
            snapshot = ProviderSnapshot(provider, true)
            val controller = Robolectric.buildActivity(ProviderErrorActivity::class.java).setup()
            try {
                val message = controller.get().findViewById<TextView>(R.id.provider_error_message)
                val expected = context.getString(reason.messageRes)
                await { message.text.contains(expected) }
                assertTrue(message.text.contains(provider.packageName))
                assertTrue(message.text.contains(provider.version))
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        repeat(100) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(20)
        }
        fail("UI did not update")
    }

    private companion object {
        val PROVIDER = ProviderKey("com.google.android.webview", "153.0.8010.36", 28, "x86_64")
    }
}
