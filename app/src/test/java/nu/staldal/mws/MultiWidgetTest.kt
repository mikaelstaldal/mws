package nu.staldal.mws

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import android.view.View
import android.widget.TextView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MultiWidgetTest {
    private fun app(id: String, position: Int, state: ProfileState = ProfileState.ACTIVE) =
        ConfiguredApp(id = id, name = id, nameSource = NameSource.USER_EDITED,
            configuredUrl = "https://example.com/$id", finalUrl = null,
            launchUrl = "https://example.com/$id", scope = "https://example.com/",
            profileState = state, sortPosition = position, createdAt = 1, updatedAt = 1)

    @Test fun emptyCollectionHidesListAndEntireEmptyAreaOpensMainScreen() {
        val context = RuntimeEnvironment.getApplication()
        val root = MultiWidget.baseViews(context, 981).apply(context, null)
        val list = root.findViewById<android.widget.ListView>(R.id.widget_list)
        val empty = root.findViewById<View>(R.id.widget_empty)
        list.adapter = android.widget.ArrayAdapter<String>(context,
            android.R.layout.simple_list_item_1, emptyList())
        assertSame(empty, list.emptyView)
        assertEquals(View.GONE, list.visibility)
        assertEquals(View.VISIBLE, empty.visibility)
        assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, empty.layoutParams.height)
        assertTrue(empty.performClick())
        val started = org.robolectric.Shadows.shadowOf(context).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        list.adapter = android.widget.ArrayAdapter(context,
            android.R.layout.simple_list_item_1, listOf("app"))
        assertEquals(View.VISIBLE, list.visibility)
        assertEquals(View.GONE, empty.visibility)
    }

    @Test fun instanceSelectionsKeepCanonicalOrderAndClearingRows() {
        val apps = listOf(app("b", 0), app("a", 1, ProfileState.CLEARING), app("c", 2))
        val first = WidgetRecord(11, WidgetType.MULTI_APP, SelectionMode.SELECTED_SUBSET,
            setOf("a", "c"))
        val second = WidgetRecord(12, WidgetType.MULTI_APP, SelectionMode.SELECTED_SUBSET,
            setOf("b", "c"))
        assertEquals(listOf("a", "c"), MultiWidget.select(apps, first).map { it.id })
        assertEquals(ProfileState.CLEARING, MultiWidget.select(apps, first).first().profileState)
        assertEquals(listOf("b", "c"), MultiWidget.select(apps, second).map { it.id })
        assertEquals(apps, MultiWidget.select(apps, null))
        assertEquals(apps, MultiWidget.select(apps,
            WidgetRecord(13, WidgetType.MULTI_APP, SelectionMode.ALL_APPS)))
        assertEquals(listOf("c", "a"), MultiWidget.select(apps.reversed(), first).map { it.id })
    }

    @Test fun recordsPruneRemovedAppsAndDeletedInstances() {
        MaintenanceStore(RuntimeEnvironment.getApplication()).use { store ->
            store.putWidget(WidgetRecord(901, WidgetType.MULTI_APP,
                SelectionMode.SELECTED_SUBSET, setOf("a", "b")))
            store.putWidget(WidgetRecord(902, WidgetType.MULTI_APP,
                SelectionMode.ALL_APPS))
            store.putWidget(WidgetRecord(903, WidgetType.SINGLE_APP, targetAppId = "b"))
            store.pruneWidgetSelection("a")
            assertEquals(setOf("b"), store.widget(901)?.selectedAppIds)
            store.pruneWidgets(setOf(902), WidgetType.MULTI_APP)
            assertNull(store.widget(901))
            assertNotNull(store.widget(902))
            assertNotNull(store.widget(903))
            store.deleteWidget(902)
            store.deleteWidget(903)
            assertTrue(store.widgetIds().isEmpty())
        }
    }

    @Test fun rowFillInCarriesOnlyOpaqueIdAndClearingRowsCannotLaunch() {
        val app = app("valid", 0)
        val fillIn = MultiWidget.rowIntent(app)
        assertEquals("mws-app:valid", fillIn.dataString)
        assertTrue(fillIn.extras == null || fillIn.extras!!.isEmpty)
        assertNull(MultiWidget.rowIntent(app.copy(profileState = ProfileState.CLEARING)).data)
        assertNull(ShellActivity.appIdOf(fillIn)) // Action and component come from the template.
    }

    @Test fun providerDeletionRemovesOnlyDeletedInstance() {
        val context = RuntimeEnvironment.getApplication()
        MaintenanceStore(context).use { store ->
            store.putWidget(WidgetRecord(941, WidgetType.MULTI_APP, SelectionMode.ALL_APPS))
            store.putWidget(WidgetRecord(942, WidgetType.MULTI_APP, SelectionMode.ALL_APPS))
        }
        MultiWidgetProvider().onDeleted(context, intArrayOf(941))
        MaintenanceStore(context).use { store ->
            assertNull(store.widget(941))
            assertNotNull(store.widget(942))
            store.deleteWidget(942)
        }
    }

    @Test fun remoteRowsRenderNameDefaultIconAndDisabledClearingState() {
        val context = RuntimeEnvironment.getApplication()
        val active = MultiWidget.rowViews(context, app("active", 0)).apply(context, null)
        assertEquals("active", active.findViewById<TextView>(R.id.widget_row_name).text.toString())
        assertTrue(active.isEnabled)
        assertEquals(View.GONE, active.findViewById<View>(R.id.widget_row_state).visibility)
        val clearing = MultiWidget.rowViews(context,
            app("clearing", 1, ProfileState.CLEARING)).apply(context, null)
        assertFalse(clearing.isEnabled)
        assertEquals(View.VISIBLE, clearing.findViewById<View>(R.id.widget_row_state).visibility)
        assertEquals("Clearing site data…",
            clearing.findViewById<TextView>(R.id.widget_row_state).text.toString())
    }

    @Test fun collectionEmbedsRowsInOrder() {
        val context = RuntimeEnvironment.getApplication()
        val items = MultiWidget.collection(context, listOf(app("b", 0), app("a", 1)))
        assertEquals(2, items.itemCount)
        assertEquals(listOf(0L, 1L), (0 until items.itemCount).map(items::getItemId))
        assertEquals("a", items.getItemView(1).apply(context, null)
            .findViewById<TextView>(R.id.widget_row_name).text.toString())
        assertEquals(1, items.viewTypeCount)
    }

    @Test fun widgetManifestExportsNoCollectionServiceAndOnlyConfiguration() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val services = manifest.getElementsByTagName("service")
        assertTrue((0 until services.length).map { services.item(it) }.none {
            it.attributes.getNamedItem("android:permission")?.nodeValue ==
                "android.permission.BIND_REMOTEVIEWS" })
        val activities = manifest.getElementsByTagName("activity")
        val configure = (0 until activities.length).map { activities.item(it) }
            .first { it.attributes.getNamedItem("android:name")?.nodeValue ==
                ".MultiWidgetConfigureActivity" }
        assertEquals("true", configure.attributes.getNamedItem("android:exported").nodeValue)
        assertTrue(File("src/main/AndroidManifest.xml").readText()
            .contains("android.appwidget.action.APPWIDGET_CONFIGURE"))
    }
}
