package nu.staldal.mws

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** A§9.1 task model against the real activity manager: one document task per app. */
@RunWith(AndroidJUnit4::class)
class ShellTaskInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var database: ConfigurationDatabase
    private lateinit var store: MaintenanceStore
    private lateinit var services: ShellServices
    private lateinit var server: LocalHttpsServer
    private val apps = mutableListOf<String>()

    @Before fun setUp() {
        val snapshot = AndroidProviderSnapshotSource(context).snapshot()
        val key = snapshot.key
        assumeTrue(key != null && snapshot.multiProfile)
        server = LocalHttpsServer()
        server.dispatch { okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/html")
            .setBody("<html><body>shell</body></html>") }
        database = Room.inMemoryDatabaseBuilder(context, ConfigurationDatabase::class.java).build()
        store = MaintenanceStore(context)
        val repository = ConfigurationRepository(database)
        // The provider is qualified for this test only; the runtime self-test has its own suite.
        val compatibility = ProviderCompatibility({ snapshot },
            { k -> ProviderSelfTestResult(k, true, true, true, true, true, true) },
            floors = mapOf(key!!.packageName to listOf(0)))
        val shells = LiveShellRegistry()
        val icons = IconStore(context)
        val profiles = ProfileManager(repository, store, compatibility, AndroidWebProfileBackend(),
            shells, object : AppNativeData {
                override fun deleteIcons(appId: String) = icons.delete(appId)
                override fun widgetsChanged() {}
            })
        services = ShellServices(repository, compatibility, shells, profiles, icons)
        ShellServices.install(services)
    }

    @After fun tearDown() {
        if (!::services.isInitialized) return
        appTasks().forEach { runCatching { it.finishAndRemoveTask() } }
        runBlocking { apps.forEach { services.profiles.remove(it) } }
        ShellServices.install(null)
        server.close()
        database.close()
        store.close()
    }

    @Test fun eachAppHasOneRecentsTaskAndRelaunchBringsItForward() {
        val first = addApp("First")
        val second = addApp("Second")
        launch(first)
        val a = awaitShell(first)
        launch(second)
        val b = awaitShell(second)
        assertNotSame(a, b)

        launch(first)
        assertSame(a, awaitShell(first))
        await { tasksFor(first).size == 1 && tasksFor(second).size == 1 }
        assertTrue(stage(b) != Stage.DESTROYED)
        await { tasksFor(first).single().taskInfo.taskDescription?.label == "First" }
        await { tasksFor(second).single().taskInfo.taskDescription?.label == "Second" }
    }

    @Test fun clearClosesOnlyTheTargetAppsTask() {
        val first = addApp("First")
        val second = addApp("Second")
        launch(first)
        val a = awaitShell(first)
        launch(second)
        awaitShell(second)

        val original = runBlocking { services.repository.get(first) }!!
        val result = runBlocking { services.profiles.clear(first) }
        assertTrue("Unexpected clear result: $result",
            result == ClearResult.Cleared || result == ClearResult.PendingRestart)
        val cleared = runBlocking { services.repository.get(first) }!!
        assertEquals(ProfileState.ACTIVE, cleared.profileState)
        assertEquals(original.profileGeneration + 1, cleared.profileGeneration)
        assertEquals(null, cleared.retiringProfileGeneration)
        if (result == ClearResult.PendingRestart) {
            assertTrue(store.hasTombstone(ProfileNames.app(first, original.profileGeneration)))
        } else {
            assertFalse(store.hasTombstone(ProfileNames.app(first, original.profileGeneration)))
        }
        await { stage(a) == Stage.DESTROYED && tasksFor(first).isEmpty() }
        assertEquals(1, tasksFor(second).size)
    }

    private fun addApp(name: String): String {
        val id = UUID.randomUUID().toString()
        val url = server.url.toString()
        val result = runBlocking {
            services.repository.add(ConfiguredApp(id, name, NameSource.USER_EDITED, url, null, url, url,
                createdAt = 1, updatedAt = 1))
        }
        assertEquals(ConfigurationWriteResult.Success, result)
        apps += id
        return id
    }

    private fun launch(appId: String) {
        context.startActivity(ShellActivity.intent(context, appId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun appTasks(): List<ActivityManager.AppTask> =
        context.getSystemService(ActivityManager::class.java).appTasks

    private fun tasksFor(appId: String) = appTasks().filter {
        runCatching { it.taskInfo.baseIntent.dataString }.getOrNull() == "${ShellActivity.SCHEME}:$appId"
    }

    private fun awaitShell(appId: String): ShellActivity {
        var found: ShellActivity? = null
        await {
            found = onMain {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ShellActivity>()
                    .firstOrNull { ShellActivity.appIdOf(it.intent) == appId && it.holder()?.bound != null }
            }
            found != null
        }
        return found!!
    }

    private fun ShellActivity.holder(): ShellHolder? =
        ShellActivity::class.java.getDeclaredField("holder").apply { isAccessible = true }
            .get(this) as ShellHolder?

    private fun stage(activity: Activity): Stage =
        onMain { ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Condition not reached")
    }
}
