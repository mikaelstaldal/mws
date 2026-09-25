package nu.staldal.mws

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class MainScreenTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: ConfigurationDatabase

    @Before fun prepare() {
        database = ConfigurationDatabase.shared(context)
        clearApps()
    }

    @After fun cleanup() {
        clearApps()
    }

    private fun clearApps() = runBlocking {
        val repository = ConfigurationRepository(database)
        repository.ordered().forEach { repository.remove(it.id) }
    }

    @Test fun emptyStateOffersAddAndFab() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        await { activity.findViewById<View>(R.id.empty_state).visibility == View.VISIBLE }
        assertEquals("Add web app", activity.findViewById<TextView>(R.id.empty_add).text.toString())
        activity.findViewById<View>(R.id.empty_add).performClick()
        val next = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(PlaceholderActivity.ACTION_ADD, next.action)
        activity.findViewById<View>(R.id.add_web_app).performClick()
        assertEquals(PlaceholderActivity.ACTION_ADD,
            Shadows.shadowOf(activity).nextStartedActivity.action)
        controller.pause().stop().destroy()
    }

    @Test fun orderedRowsShowNamesUrlsAndAccessibleActions() {
        runBlocking {
            ConfigurationRepository(database).add(app("first", "Alpha", 0))
            ConfigurationRepository(database).add(app("second", "Beta", 1))
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.empty_state).visibility)
        val rows = rows(activity)
        await { rows.childCount == 2 }
        assertEquals("first", rows.getChildAt(0).tag)
        assertEquals("second", rows.getChildAt(1).tag)
        val first = rows.getChildAt(0)
        assertEquals("Alpha", first.findViewById<TextView>(R.id.app_name_text).text.toString())
        assertEquals("https://example.com/long/path", first.findViewById<TextView>(R.id.app_url_text).text.toString())
        assertEquals(android.text.TextUtils.TruncateAt.MIDDLE,
            first.findViewById<TextView>(R.id.app_url_text).ellipsize)
        assertTrue(first.contentDescription.contains("Long press to edit"))
        assertNotNull(first.findViewById<View>(R.id.app_icon).contentDescription)
        assertNotNull((first.findViewById<View>(R.id.app_icon) as android.widget.ImageView).drawable)
        first.performLongClick()
        val edit = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(PlaceholderActivity.ACTION_EDIT, edit.action)
        assertEquals("first", edit.getStringExtra(PlaceholderActivity.EXTRA_APP_ID))
        controller.pause().stop().destroy()
    }

    @Test fun clearingRowKeepsPositionAndExplainsTap() {
        runBlocking {
            ConfigurationRepository(database).add(app("first", "Alpha", 0))
            ConfigurationRepository(database).add(app("clearing", "Beta", 1).copy(
                profileState = ProfileState.CLEARING, retiringProfileGeneration = 0,
                profileGeneration = 1))
            ConfigurationRepository(database).add(app("third", "Gamma", 2))
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val rows = rows(activity)
        await { rows.childCount == 3 }
        val clearing = rows.getChildAt(1)
        assertEquals("clearing", clearing.tag)
        assertEquals("Clearing site data…",
            clearing.findViewById<TextView>(R.id.clearing_label).text.toString())
        assertTrue(clearing.contentDescription.contains("Clearing site data"))
        assertEquals(activity.getString(R.string.clearing_state),
            androidx.core.view.ViewCompat.getStateDescription(clearing))
        clearing.performClick()
        assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("Site data is being cleared"))
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertEquals("third", rows.getChildAt(2).tag)
        controller.pause().stop().destroy()
    }

    @Test fun incompatibleProviderHasErrorEntryAndBlocksRowLaunch() {
        runBlocking { ConfigurationRepository(database).add(app("first", "Alpha", 0)) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val rows = rows(activity)
        await { rows.childCount == 1 }
        await { activity.findViewById<View>(R.id.provider_error_entry).visibility == View.VISIBLE }
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.provider_error_entry).visibility)
        rows.getChildAt(0).performClick()
        assertEquals(ProviderErrorActivity::class.java.name,
            Shadows.shadowOf(activity).nextStartedActivity.component?.className)
        controller.pause().stop().destroy()
    }

    private fun rows(activity: MainActivity): LinearLayout {
        return activity.findViewById(R.id.app_rows)
    }

    private fun await(condition: () -> Boolean) {
        repeat(100) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(20)
        }
        fail("UI did not update")
    }

    private fun app(id: String, name: String, position: Int) = ConfiguredApp(
        id = id, name = name, nameSource = NameSource.USER_EDITED,
        configuredUrl = "https://example.com/long/path", finalUrl = null,
        launchUrl = "https://example.com/long/path", scope = "https://example.com/",
        sortPosition = position, createdAt = 1, updatedAt = 1,
    )
}
