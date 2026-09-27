package nu.staldal.mws

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MaintenanceStoreTest {
    private fun open(): MaintenanceStore = MaintenanceStore(RuntimeEnvironment.getApplication())

    @Test fun maintenanceDataPersistsOutsideBackupDirectory() {
        val context = RuntimeEnvironment.getApplication()
        val store = open()
        val suffix = UUID.randomUUID().toString()
        val appId = "app_$suffix"
        val tombstone = ProfileTombstone("profile_$suffix", 3, 100L)
        val discovery = DiscoveryProfile("discovery_$suffix", 101L)
        val permission = PermissionDecision(appId, 3, "https://example.com:443",
            PermissionCapability.GEOLOCATION, SiteDecision.SITE_DENY)
        val widget = WidgetRecord(12345, WidgetType.MULTI_APP, SelectionMode.SELECTED_SUBSET,
            setOf(appId, "second"))
        store.putTombstone(tombstone)
        store.registerDiscoveryProfile(discovery)
        store.putPermissionDecision(permission)
        store.putWidget(widget)
        store.putSelfTest(RuntimeSelfTest("provider-key", mapOf("profiles" to true, "delete" to false)))
        store.createInstallationSentinel()
        store.close()

        assertTrue(File(context.noBackupFilesDir, "maintenance.db").isFile)
        assertFalse(context.getDatabasePath("maintenance.db").exists())
        open().use { reopened ->
            assertTrue(tombstone in reopened.tombstones())
            assertTrue(discovery in reopened.discoveryProfiles())
            assertEquals(permission, reopened.permissionDecision(appId, 3,
                "https://example.com:443", PermissionCapability.GEOLOCATION))
            assertEquals(widget, reopened.widget(12345))
            assertEquals(RuntimeSelfTest("provider-key", mapOf("profiles" to true, "delete" to false)),
                reopened.selfTest())
            assertTrue(reopened.hasInstallationSentinel())
            reopened.pruneWidgetSelection(appId)
            assertEquals(setOf("second"), reopened.widget(12345)?.selectedAppIds)
            reopened.deletePermissionDecisions(appId, 3)
            assertNull(reopened.permissionDecision(appId, 3, "https://example.com:443",
                PermissionCapability.GEOLOCATION))
            reopened.deleteWidget(12345)
            assertNull(reopened.widget(12345))
            reopened.deleteTombstone(tombstone.profileName)
            reopened.deleteDiscoveryProfile(discovery.profileId)
        }
    }

    @Test fun journalResumesAfterDeathAtEveryStepAndReplaysUncommittedAction() {
        for (crashStep in 0..2) {
            val effects = mutableSetOf<Int>()
            val calls = mutableListOf<Int>()
            val intent = OperationIntent(OperationType.CLEAR_DATA, "app", 1, 2)
            val store = open()
            var crash = true
            val steps = (0..2).map { step: Int -> { _: OperationIntent ->
                calls += step
                effects += step // an idempotent external action
                if (step == crashStep && crash) throw SimulatedDeath()
            } }
            val journal = OperationJournal(store, mapOf(OperationType.CLEAR_DATA to steps))
            val entry = journal.start(intent)
            try { journal.run(entry); fail("Expected crash") } catch (_: SimulatedDeath) { }
            assertEquals(crashStep, store.pendingOperations().single().completedSteps)
            store.close()
            crash = false
            open().use { resumed ->
                assertTrue(OperationJournal(resumed,
                    mapOf(OperationType.CLEAR_DATA to steps)).resumeOnStartup().failures.isEmpty())
                assertTrue(resumed.pendingOperations().isEmpty())
                assertEquals(entry.id, resumed.completedOperations().single().id)
                resumed.discardCompleted(entry.id)
                assertTrue(resumed.completedOperations().isEmpty())
            }
            assertEquals(setOf(0, 1, 2), effects)
            assertEquals(2, calls.count { it == crashStep })
        }
    }

    @Test fun failedJournalEntryDoesNotBlockLaterEntries() {
        open().use { store ->
            val bad = store.begin(OperationIntent(OperationType.REMOVE_APP, "bad"), 1)
            val good = store.begin(OperationIntent(OperationType.REMOVE_APP, "good"), 1)
            val result = OperationJournal(store, mapOf(OperationType.REMOVE_APP to listOf({ intent ->
                if (intent.appId == "bad") error("transient failure")
            }))).resumeOnStartup()
            assertEquals(listOf(good.id), result.completedIds)
            assertTrue(result.failures.containsKey(bad.id))
            assertEquals(listOf(bad.id), store.pendingOperationIds())
            store.completeStep(bad.id, 0)
            store.discardCompleted(bad.id)
            store.discardCompleted(good.id)
        }
    }

    @Test fun sqliteSettingsAndGenerationScopedPermissionUniqueness() {
        open().use { store ->
            assertEquals(Triple("delete", "2", "1"), store.storagePragmas())
            val one = PermissionDecision("app", 1, "https://example.com",
                PermissionCapability.GEOLOCATION, SiteDecision.SITE_ALLOW)
            store.putPermissionDecision(one)
            store.putPermissionDecision(one.copy(decision = SiteDecision.SITE_DENY))
            store.putPermissionDecision(one.copy(generation = 2))
            assertEquals(SiteDecision.SITE_DENY, store.permissionDecision("app", 1,
                "https://example.com", PermissionCapability.GEOLOCATION)?.decision)
            assertEquals(SiteDecision.SITE_ALLOW, store.permissionDecision("app", 2,
                "https://example.com", PermissionCapability.GEOLOCATION)?.decision)
        }
    }

    @Test fun backupRulesIncludeOnlyConfigurationDatabase() {
        val resources = listOf("backup_rules.xml", "data_extraction_rules.xml")
        for (name in resources) {
            val file = File("src/main/res/xml/$name")
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val includes = document.getElementsByTagName("include")
            assertTrue(includes.length > 0)
            for (index in 0 until includes.length) {
                val item = includes.item(index).attributes
                assertEquals("database", item.getNamedItem("domain").nodeValue)
                assertEquals("configuration.db", item.getNamedItem("path").nodeValue)
            }
            val text = file.readText()
            assertFalse(text.contains("maintenance.db"))
            assertFalse(text.contains("icons/"))
            assertFalse(text.contains("app_webview"))
            if (name == "data_extraction_rules.xml") {
                assertEquals(1, document.getElementsByTagName("cloud-backup").length)
                assertEquals(1, document.getElementsByTagName("device-transfer").length)
                for (section in listOf("cloud-backup", "device-transfer")) {
                    val element = document.getElementsByTagName(section).item(0) as org.w3c.dom.Element
                    assertEquals(1, element.getElementsByTagName("include").length)
                }
            }
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"true\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    private class SimulatedDeath : RuntimeException()
}
