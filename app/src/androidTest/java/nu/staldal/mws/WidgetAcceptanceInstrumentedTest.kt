package nu.staldal.mws

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Controlled device coverage; the optional hold lets the human inspect the real launcher. */
@RunWith(AndroidJUnit4::class)
class WidgetAcceptanceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun missingRecordDefaultsToAllAppsAndClearingRowsCannotLaunchOrBeSelected() = runBlocking {
        val seconds = InstrumentationRegistry.getArguments().getString("holdSeconds", "0")
            .toInt().also { require(it in 0..120) }
        val services = ShellServices.shared(context)
        MaintenanceStore(context).use { restoreFixture(services, it) }
        val widgetId = MultiWidget.installed(context).minOrNull()
        assertNotNull("Add a multi-app widget before running this test", widgetId)
        val id = widgetId!!
        services.awaitStartup()
        assertTrue("Use the provider-testing build with passing runtime probes",
            services.compatibility.current().compatible)
        val repository = services.repository
        val fixtures = listOf(app("Widget test: before"), app("Widget test: clearing"),
            app("Widget test: after"))
        val factory = MultiWidgetService.Factory(context, id)
        MaintenanceStore(context).use { store ->
            val prior = store.widget(id)
            saveFixture(id, prior, fixtures.map { it.id })
            var configure: MultiWidgetConfigureActivity? = null
            try {
                // Exercise the real collection factory with an installed widget lacking a record.
                store.deleteWidget(id)
                factory.onDataSetChanged()
                val ordered = repository.ordered()
                assertEquals(ordered.size, factory.count)
                ordered.forEachIndexed { index, app ->
                    val view = onMain { factory.getViewAt(index)!!.apply(context, null) }
                    assertEquals(app.name, view.findViewById<TextView>(R.id.widget_row_name).text.toString())
                }
                for (app in fixtures) assertEquals(ConfigurationWriteResult.Success, repository.add(app))
                assertTrue(repository.reserveNextGeneration(fixtures[1].id) is GenerationReservation.Reserved)
                // Selecting the clearing app in an existing record must retain its disabled row.
                store.putWidget(WidgetRecord(id, WidgetType.MULTI_APP, SelectionMode.SELECTED_SUBSET,
                    fixtures.map { it.id }.toSet()))
                factory.onDataSetChanged()
                assertEquals(3, factory.count)
                fixtures.forEachIndexed { index, app ->
                    val view = onMain { factory.getViewAt(index)!!.apply(context, null) }
                    assertEquals(app.name, view.findViewById<TextView>(R.id.widget_row_name).text.toString())
                    if (index == 1) {
                        assertFalse(view.isEnabled)
                        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_row_state).visibility)
                        assertEquals(context.getString(R.string.clearing_label),
                            view.findViewById<TextView>(R.id.widget_row_state).text.toString())
                    }
                }
                val clearing = repository.get(fixtures[1].id)!!
                assertNull(MultiWidget.rowIntent(clearing).data)
                assertEquals(AttachResult.Blocked(AttachBlock.CLEARING),
                    services.profiles.createAppWebView(context, clearing.id))

                configure = instrumentation.startActivitySync(
                    Intent(context, MultiWidgetConfigureActivity::class.java)
                        .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MultiWidgetConfigureActivity
                val activity = configure!!
                await {
                    onMain { descendants(activity.window.decorView).filterIsInstance<CheckBox>()
                        .any { it.text.toString().startsWith(fixtures[1].name) } }
                }
                onMain {
                    val boxes = descendants(activity.window.decorView).filterIsInstance<CheckBox>()
                    val disabled = boxes.single { it.text.toString().startsWith(fixtures[1].name) }
                    assertFalse(disabled.isEnabled)
                    assertFalse(disabled.isChecked)
                    assertTrue(disabled.text.contains(context.getString(R.string.clearing_label)))
                    fixtures.filterIndexed { index, _ -> index != 1 }.forEach { app ->
                        assertTrue(boxes.single { it.text.toString() == app.name }.isEnabled)
                    }
                    activity.finish()
                }
                configure = null
                MultiWidget.refresh(context)
                if (seconds > 0) {
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "\nFixture ready for ${seconds}s: press Home; find the widget showing " +
                            "Widget test: before / clearing / after. Verify the middle row stays in place, " +
                            "shows Clearing site data…, and tapping it does not launch.\n")
                    })
                    SystemClock.sleep(seconds * 1000L)
                }
            } finally {
                onMain { configure?.finish() }
                factory.onDestroy()
                restoreFixture(services, store)
            }
        }
    }

    @Test fun deletionRemovalAndStartupReconciliationCleanWidgetRecords() = runBlocking {
        // Isolate the maintenance database and app records from the user's configuration.
        val directory = File(context.cacheDir, "widget-acceptance-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = directory
        }
        val database = Room.inMemoryDatabaseBuilder(context, ConfigurationDatabase::class.java).build()
        val repository = ConfigurationRepository(database)
        try {
            MaintenanceStore(isolated).use { store ->
                store.createInstallationSentinel()
                val kept = app("Kept")
                val removed = app("Removed")
                assertEquals(ConfigurationWriteResult.Success, repository.add(kept))
                assertEquals(ConfigurationWriteResult.Success, repository.add(removed))
                val deletedId = -101
                val orphanId = -102
                val installedId = MultiWidget.installed(context).minOrNull()
                assertNotNull("Add a multi-app widget before running this test", installedId)
                val liveId = installedId!!
                val singleId = -103
                store.putWidget(WidgetRecord(deletedId, WidgetType.MULTI_APP, SelectionMode.ALL_APPS))
                store.putWidget(WidgetRecord(orphanId, WidgetType.MULTI_APP, SelectionMode.ALL_APPS))
                store.putWidget(WidgetRecord(liveId, WidgetType.MULTI_APP, SelectionMode.SELECTED_SUBSET,
                    setOf(kept.id, removed.id, "missing-test-app")))
                store.putWidget(WidgetRecord(singleId, WidgetType.SINGLE_APP, targetAppId = removed.id))
                MultiWidgetProvider().onDeleted(isolated, intArrayOf(deletedId))
                assertNull(store.widget(deletedId))
                assertNotNull(store.widget(liveId))
                val profiles = ProfileManager(repository, store,
                    ProviderCompatibility({ ProviderSnapshot(null, false) }, { null }),
                    AndroidWebProfileBackend(), LiveShellRegistry(), object : AppNativeData {
                        override fun deleteIcons(appId: String) = Unit
                        override fun widgetsChanged() = Unit
                    })
                // This is the production removal journal, not a direct selection-store call.
                profiles.remove(removed.id)
                assertNull(repository.get(removed.id))
                assertEquals(setOf(kept.id, "missing-test-app"), store.widget(liveId)!!.selectedAppIds)
                assertEquals(removed.id, store.widget(singleId)!!.targetAppId)
                // Native process-start reconciliation prunes dangling subset IDs.
                profiles.prepareConfiguration()
                assertEquals(setOf(kept.id), store.widget(liveId)!!.selectedAppIds)
                // Widget startup reconciliation queries the real installed provider IDs.
                MultiWidget.reconcile(isolated)
                assertNull(store.widget(orphanId))
                assertNotNull(store.widget(liveId))
                assertNotNull(store.widget(singleId)) // Other provider records are untouched.
            }
        } finally {
            database.close()
            if (!directory.deleteRecursively()) Log.w("WidgetAcceptance", "Isolated fixture cleanup failed")
        }
    }

    private val fixtureBackup get() = AtomicFile(File(context.noBackupFilesDir, "widget-acceptance-fixture.json"))

    private fun saveFixture(id: Int, prior: WidgetRecord?, appIds: List<String>) {
        val record = prior?.let {
            JSONObject().put("type", it.widgetType.name).put("mode", it.selectionMode?.name ?: JSONObject.NULL)
                .put("selected", JSONArray(it.selectedAppIds.toList()))
                .put("target", it.targetAppId ?: JSONObject.NULL)
        }
        val data = JSONObject().put("widget", id).put("apps", JSONArray(appIds))
            .put("prior", record ?: JSONObject.NULL).toString().toByteArray(Charsets.UTF_8)
        val backup = fixtureBackup
        val output = backup.startWrite()
        try {
            output.write(data)
            backup.finishWrite(output)
        } catch (failure: Throwable) {
            backup.failWrite(output)
            throw failure
        }
    }

    /** A rerun also restores a fixture left by a killed instrumentation process. */
    private suspend fun restoreFixture(services: ShellServices, store: MaintenanceStore) {
        val backup = fixtureBackup
        if (!backup.baseFile.exists() && !File(backup.baseFile.path + ".bak").exists()) return
        val data = backup.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        val apps = data.getJSONArray("apps")
        val failures = (0 until apps.length()).mapNotNull { index ->
            runCatching {
                val result = services.profiles.remove(apps.getString(index))
                check(result !is RemovalResult.NativeDeletionPending) { "Fixture cleanup failed: $result" }
            }.exceptionOrNull()
        }
        val id = data.getInt("widget")
        val prior = if (data.isNull("prior")) null else data.getJSONObject("prior").let { record ->
            val selected = record.getJSONArray("selected")
            WidgetRecord(id, WidgetType.valueOf(record.getString("type")),
                if (record.isNull("mode")) null else SelectionMode.valueOf(record.getString("mode")),
                (0 until selected.length()).map { selected.getString(it) }.toSet(),
                if (record.isNull("target")) null else record.getString("target"))
        }
        val restore = prior?.takeIf { id in MultiWidget.installed(context) }
        if (restore == null) store.deleteWidget(id) else store.putWidget(restore)
        assertEquals("Original widget record must be restored", restore, store.widget(id))
        MultiWidget.refresh(context)
        failures.firstOrNull()?.let { throw it }
        backup.delete()
    }

    private fun app(name: String): ConfiguredApp {
        val url = "https://widget-acceptance.invalid/"
        return ConfiguredApp(UUID.randomUUID().toString(), name, NameSource.USER_EDITED,
            url, null, url, url, createdAt = 1, updatedAt = 1)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Widget configuration did not show fixture choices")
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
}
