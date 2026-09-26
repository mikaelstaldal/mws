package nu.staldal.mws

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class SingleWidgetTest {
    private fun app(id: String) = ConfiguredApp(id = id, name = id,
        nameSource = NameSource.USER_EDITED, configuredUrl = "https://example.com/",
        finalUrl = null, launchUrl = "https://example.com/", scope = "https://example.com/",
        sortPosition = 0, createdAt = 1, updatedAt = 1)

    @Test fun instancesResolveSameAndDifferentTargetsAndPreserveRemovedRecord() {
        val apps = listOf(app("a"), app("b"))
        MaintenanceStore(RuntimeEnvironment.getApplication()).use { store ->
            listOf(11 to "a", 12 to "a", 13 to "b").forEach { (id, target) ->
                store.putWidget(WidgetRecord(id, WidgetType.SINGLE_APP, targetAppId = target))
            }
            assertEquals("a", SingleWidget.target(apps, store.widget(11))?.id)
            assertEquals("a", SingleWidget.target(apps, store.widget(12))?.id)
            assertEquals("b", SingleWidget.target(apps, store.widget(13))?.id)
            store.pruneWidgetSelection("a")
            assertEquals("a", store.widget(11)?.targetAppId)
            assertNull(SingleWidget.target(apps.drop(1), store.widget(11)))
            SingleWidgetProvider().onDeleted(RuntimeEnvironment.getApplication(), intArrayOf(11))
            assertNull(store.widget(11))
            assertNotNull(store.widget(12))
            store.deleteWidget(12); store.deleteWidget(13)
        }
    }

    @Test fun activeRemovedMissingAndClearingTargetsRenderAndRouteSafely() {
        val context = RuntimeEnvironment.getApplication()
        val active = app("a")
        fun label(app: ConfiguredApp?) = SingleWidget.views(context, 21, app).apply(context, null)
            .findViewById<TextView>(R.id.widget_single_name).text.toString()
        assertEquals("a", label(active))
        assertEquals("a", ShellActivity.appIdOf(SingleWidget.clickIntent(context, 21, active)))
        assertEquals("App unavailable", label(null))
        assertEquals(MainActivity::class.java.name, SingleWidget.clickIntent(context, 21, null).component?.className)
        val clearing = active.copy(profileState = ProfileState.CLEARING)
        assertEquals("App data maintenance", label(clearing))
        assertNull(ShellActivity.appIdOf(SingleWidget.clickIntent(context, 21, clearing)))
        assertNull(SingleWidget.target(listOf(active), null))
        assertNull(SingleWidget.target(listOf(active), WidgetRecord(22, WidgetType.SINGLE_APP, targetAppId = "forged")))
        assertNull(SingleWidget.target(listOf(active), WidgetRecord(23, WidgetType.MULTI_APP, SelectionMode.ALL_APPS)))
    }

    @Test fun storedIconRendersOfflineAndUnavailableUsesDefault() {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = android.graphics.Bitmap.createBitmap(72, 72, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        val store = IconStore(context)
        val reference = store.save("widget-icon", SelectedIcon(
            IconCandidate("https://example.com/icon.png", IconCandidate.Source.PAGE), bitmap))
        try {
            val view = SingleWidget.views(context, 31, app("widget-icon").copy(iconReference = reference)).apply(context, null)
            val drawable = view.findViewById<android.widget.ImageView>(R.id.widget_single_icon).drawable
            assertTrue(drawable is android.graphics.drawable.BitmapDrawable)
            assertEquals(android.graphics.Color.RED, (drawable as android.graphics.drawable.BitmapDrawable).bitmap.getPixel(36, 36))
            val unavailable = SingleWidget.views(context, 31, null).apply(context, null)
            assertNotNull(unavailable.findViewById<android.widget.ImageView>(R.id.widget_single_icon).drawable)
        } finally { store.delete("widget-icon"); bitmap.recycle() }
    }

    @Test fun configurationRejectsForgedWidgetIdsWithoutWriting() {
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 99999)
        val activity = Robolectric.buildActivity(SingleWidgetConfigureActivity::class.java, intent).create().get()
        assertTrue(activity.isFinishing)
        MaintenanceStore(activity).use { assertNull(it.widget(99999)) }
    }
}
