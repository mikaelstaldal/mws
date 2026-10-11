package nu.staldal.mws

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
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

    @Test fun startupDisablesConfigurationUntilCheckCompletes() {
        val services = ShellServices.shared(context)
        val checked = kotlinx.coroutines.CompletableDeferred<Unit>()
        ShellServices.install(ShellServices(services.repository, services.compatibility,
            services.shells, services.profiles, services.icons, selfTest = { checked.await() }))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val controls = listOf(R.id.add_web_app, R.id.empty_add, R.id.import_apps,
            R.id.quick_launch, R.id.quick_url, R.id.reorder_apps)
        controls.forEach { assertFalse(activity.findViewById<View>(it).isEnabled) }
        assertEquals(activity.getString(R.string.provider_self_test_running),
            activity.findViewById<TextView>(R.id.provider_check_status).text.toString())
        activity.findViewById<View>(R.id.add_web_app).performClick()
        activity.findViewById<View>(R.id.import_apps).performClick()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        controller.pause().resume()
        controls.forEach { assertFalse(activity.findViewById<View>(it).isEnabled) }
        checked.complete(Unit)
        await { activity.findViewById<View>(R.id.add_web_app).isEnabled }
        controls.forEach { assertTrue(activity.findViewById<View>(it).isEnabled) }
        assertEquals(activity.getString(R.string.provider_check_complete),
            activity.findViewById<TextView>(R.id.provider_check_status).text.toString())
        controller.pause().stop().destroy()
        ShellServices.install(services)
    }

    @Test fun systemBarsInsetBothListAndFloatingAddButtonWithoutAccumulating() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val button = activity.findViewById<View>(R.id.add_web_app)
        val root = button.parent as android.widget.FrameLayout
        val insets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),
                androidx.core.graphics.Insets.of(0, 24, 0, 48))
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
                androidx.core.graphics.Insets.of(30, 0, 0, 0)).build()
        repeat(2) { androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, insets) }
        assertEquals(30, root.paddingLeft)
        assertEquals(24, root.paddingTop)
        assertEquals(48, root.paddingBottom)
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 600, 1000)
        val margins = button.layoutParams as android.widget.FrameLayout.LayoutParams
        assertEquals(1000 - 48 - margins.bottomMargin, button.bottom)
        assertEquals(1000 - 48,
            activity.findViewById<View>(R.id.app_list_scroll).bottom)
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,
            androidx.core.view.WindowInsetsCompat.Builder().build())
        assertEquals(0, root.paddingBottom)
        controller.pause().stop().destroy()
    }

    @Test fun emptyStateOffersAddAndFab() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        await { activity.findViewById<View>(R.id.empty_state).visibility == View.VISIBLE }
        await { activity.findViewById<View>(R.id.add_web_app).isEnabled }
        assertEquals("Add web app", activity.findViewById<TextView>(R.id.empty_add).text.toString())
        activity.findViewById<View>(R.id.empty_add).performClick()
        val next = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(EditorActivity::class.java.name, next.component?.className)
        activity.findViewById<View>(R.id.add_web_app).performClick()
        val fab = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(EditorActivity::class.java.name, fab.component?.className)
        assertNull(fab.getStringExtra(EditorActivity.EXTRA_APP_ID))
        controller.pause().stop().destroy()
    }

    @Test fun transferControlsOpenDocumentPickersAndWarnBeforeExport() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        await { activity.findViewById<View>(R.id.import_apps).isEnabled }
        activity.findViewById<View>(R.id.import_apps).performClick()
        val opened = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, opened.action)
        activity.findViewById<View>(R.id.export_apps).performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(dialog.findViewById<TextView>(android.R.id.message)!!.text.contains("secrets"))
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val created = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, created.action)
        assertEquals("application/json", created.type)
        assertEquals("mws-apps.json", created.getStringExtra(android.content.Intent.EXTRA_TITLE))
        controller.pause().stop().destroy()
    }

    @Test fun pendingImportConfirmationSurvivesRotationAndCancelAddsNothing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val state = androidx.lifecycle.ViewModelProvider(controller.get())[ConfigurationTransferState::class.java]
        state.pending = listOf(app("imported", "Imported", 0))
        controller.recreate()
        await { controller.get().findViewById<View>(R.id.import_apps).isEnabled }
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(dialog.isShowing)
        assertTrue(dialog.findViewById<TextView>(android.R.id.message)!!.text.contains("Add 1 apps"))
        controller.pause().resume()
        await { controller.get().findViewById<View>(R.id.import_apps).isEnabled }
        assertSame(dialog, org.robolectric.shadows.ShadowDialog.getLatestDialog())
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(state.pending)
        assertTrue(runBlocking { ConfigurationRepository(database).ordered().isEmpty() })
        controller.pause().stop().destroy()
    }

    @Test fun quickUrlHasAccessibleTouchTargetHeight() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val input = activity.findViewById<EditText>(R.id.quick_url)
        // Exercise a compact theme/font: intrinsic text and background must not set the target size.
        input.background = null
        input.setPadding(0, 0, 0, 0)
        input.textSize = 12f
        input.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue("URL touch target must be at least 48dp",
            input.measuredHeight >= kotlin.math.ceil(48 * activity.resources.displayMetrics.density).toInt())
        controller.pause().stop().destroy()
    }

    @Test fun quickLaunchValidatesAndOpensWithoutAddingAnApp() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        await { activity.findViewById<View>(R.id.quick_launch).isEnabled }
        val key = ProviderKey("test.webview", "1.0", 31, "x86")
        org.robolectric.util.ReflectionHelpers.setField(activity, "compatibility",
            ProviderCompatibility({ ProviderSnapshot(key, true) },
                { ProviderSelfTestResult(key, true, true, true, true, true, true) },
                floors = mapOf(key.packageName to listOf(1))))
        val input = activity.findViewById<EditText>(R.id.quick_url)
        input.setText("http://example.com")
        activity.findViewById<View>(R.id.quick_launch).performClick()
        assertNotNull(input.error)
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        input.setText("example.com/path")
        activity.findViewById<View>(R.id.quick_launch).performClick()
        val launched = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(ShellActivity::class.java.name, launched.component?.className)
        assertEquals("https://example.com/path", ShellActivity.adHocUrlOf(launched))
        assertTrue(runBlocking { ConfigurationRepository(database).ordered().isEmpty() })
        controller.pause().stop().destroy()
    }

    @Test fun orderedRowsShowNamesUrlsAndAccessibleActions() {
        runBlocking {
            ConfigurationRepository(database).add(app("first", "Alpha", 0))
            ConfigurationRepository(database).add(app("second", "Beta", 1))
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val rows = rows(activity)
        await { rows.childCount == 2 }
        await { activity.findViewById<View>(R.id.add_web_app).isEnabled }
        assertEquals(View.GONE, activity.findViewById<View>(R.id.empty_state).visibility)
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
        assertEquals(EditorActivity::class.java.name, edit.component?.className)
        assertEquals("first", edit.getStringExtra(EditorActivity.EXTRA_APP_ID))
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

    @Test fun reorderAccessibleCancelSaveAndRestart() {
        runBlocking {
            ConfigurationRepository(database).add(app("first", "Alpha", 0))
            ConfigurationRepository(database).add(app("second", "Beta", 1))
        }
        var controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        var activity = controller.get()
        await { rows(activity).childCount == 2 }
        activity.findViewById<View>(R.id.reorder_apps).performClick()
        assertNotNull(rows(activity).getChildAt(0).findViewById<View>(R.id.drag_handle))
        val node = rows(activity).getChildAt(0).createAccessibilityNodeInfo()
        val down = node.actionList.first { it.label == "Move down" }
        assertTrue(rows(activity).getChildAt(0).performAccessibilityAction(down.id, null))
        assertEquals("second", rows(activity).getChildAt(0).tag)
        assertEquals(listOf("first", "second"), runBlocking {
            ConfigurationRepository(database).ordered().map { it.id }
        })
        activity.findViewById<View>(R.id.reorder_cancel).performClick()
        assertEquals("first", rows(activity).getChildAt(0).tag)
        assertEquals(listOf("first", "second"), runBlocking {
            ConfigurationRepository(database).ordered().map { it.id }
        })
        activity.findViewById<View>(R.id.reorder_apps).performClick()
        assertTrue(activity.moveDraft("second", 0))
        val up = rows(activity).getChildAt(1).createAccessibilityNodeInfo().actionList
            .first { it.label == "Move up" }
        assertTrue(rows(activity).getChildAt(1).performAccessibilityAction(up.id, null))
        assertEquals("first", rows(activity).getChildAt(0).tag)
        val target = rows(activity).getChildAt(0)
        fun drag(action: Int): android.view.DragEvent {
            val obtain = android.view.DragEvent::class.java.declaredMethods.first {
                it.name == "obtain" && it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
            }
            obtain.isAccessible = true
            val args: Array<Any?> = obtain.parameterTypes.map { type ->
                when (type) {
                    Int::class.javaPrimitiveType -> action
                    Float::class.javaPrimitiveType -> 0f
                    Boolean::class.javaPrimitiveType -> false
                    Any::class.java -> "second"
                    else -> null
                }
            }.toTypedArray()
            return obtain.invoke(null, *args) as android.view.DragEvent
        }
        assertTrue(target.dispatchDragEvent(drag(android.view.DragEvent.ACTION_DRAG_STARTED)))
        assertTrue(target.dispatchDragEvent(drag(android.view.DragEvent.ACTION_DROP)))
        assertEquals("second", rows(activity).getChildAt(0).tag)
        activity.findViewById<View>(R.id.reorder_done).performClick()
        await { activity.findViewById<View>(R.id.reorder_apps).visibility == View.VISIBLE }
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        await { rows(activity).childCount == 2 }
        assertEquals("second", rows(activity).getChildAt(0).tag)
        controller.pause().stop().destroy()
    }

    @Test fun draftSurvivesRecreationAndReconcilesMembership() {
        runBlocking {
            ConfigurationRepository(database).add(app("first", "Alpha", 0))
            ConfigurationRepository(database).add(app("second", "Beta", 1))
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        var activity = controller.get()
        await { rows(activity).childCount == 2 }
        activity.findViewById<View>(R.id.reorder_apps).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.add_web_app).visibility)
        rows(activity).getChildAt(0).performClick()
        rows(activity).getChildAt(0).performLongClick()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        activity.moveDraft("second", 0)
        controller.recreate()
        activity = controller.get()
        await { rows(activity).childCount == 2 }
        assertEquals("second", rows(activity).getChildAt(0).tag)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.reorder_done).visibility)
        runBlocking {
            ConfigurationRepository(database).remove("first")
            ConfigurationRepository(database).add(app("third", "Gamma", 2))
        }
        await { rows(activity).childCount == 2 && rows(activity).getChildAt(1).tag == "third" }
        activity.findViewById<View>(R.id.reorder_done).performClick()
        await { activity.findViewById<View>(R.id.reorder_done).visibility == View.GONE }
        assertEquals(listOf("second", "third"), runBlocking {
            ConfigurationRepository(database).ordered().map { it.id }
        })
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
