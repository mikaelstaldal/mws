package nu.staldal.mws

import android.content.DialogInterface
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class EditorActivityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: ConfigurationRepository
    private var maintenanceStore: MaintenanceStore? = null
    private var controller: ActivityController<EditorActivity>? = null

    @Before fun prepare() {
        repository = ConfigurationRepository(ConfigurationDatabase.shared(context))
        clearApps()
        runBlocking { ShellServices.shared(context).profiles.prepareConfiguration() }
    }

    @After fun cleanup() {
        controller?.let { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        clearApps()
        ShellServices.install(null)
        maintenanceStore?.close()
    }

    private fun clearApps() = runBlocking {
        repository.ordered().forEach { repository.remove(it.id) }
    }

    private fun open(appId: String? = null): EditorActivity {
        val intent = if (appId == null) EditorActivity.addIntent(context)
            else EditorActivity.editIntent(context, appId)
        val created = Robolectric.buildActivity(EditorActivity::class.java, intent).setup()
        controller = created
        val activity = created.get()
        await { activity.findViewById<View>(R.id.editor_save).isEnabled }
        return activity
    }

    private fun EditorActivity.url() = findViewById<EditText>(R.id.editor_url)
    private fun EditorActivity.name() = findViewById<EditText>(R.id.editor_name)
    private fun EditorActivity.urlError() =
        findViewById<TextInputLayout>(R.id.editor_url_layout).error?.toString()
    private fun EditorActivity.nameError() =
        findViewById<TextInputLayout>(R.id.editor_name_layout).error?.toString()
    private fun EditorActivity.thirdParty() = findViewById<CompoundButton>(R.id.editor_third_party_cookies)
    private fun EditorActivity.save() {
        findViewById<View>(R.id.editor_save).performClick()
        ShadowLooper.idleMainLooper()
    }
    private fun latestDialog() = ShadowDialog.getLatestDialog() as AlertDialog
    private fun AlertDialog.press(button: Int) {
        getButton(button).performClick()
        ShadowLooper.idleMainLooper()
    }
    private fun saved() = runBlocking { repository.ordered() }

    @Test fun redirectActionUpdatesOnlyDraftAndDisappearsAfterUrlChange() {
        val activity = open()
        activity.url().setText("https://example.com/start")
        activity.name().setText("My app")
        val stagesField = EditorActivity::class.java.getDeclaredField("stages").apply { isAccessible = true }
        val stages = stagesField.get(activity) as EditorDiscovery
        stages.document(DiscoveryDocumentResult(null, true,
            redirectedUrl = "https://other.example/target"))
        EditorActivity::class.java.getDeclaredMethod("updateDiscoveryPreview").apply {
            isAccessible = true
        }.invoke(activity)
        val notice = activity.findViewById<TextView>(R.id.editor_redirect_notice)
        val button = activity.findViewById<View>(R.id.editor_use_redirected_url)
        assertEquals(View.VISIBLE, notice.visibility)
        assertTrue(notice.text.contains("https://other.example/target"))
        assertEquals(View.VISIBLE, button.visibility)
        assertEquals("https://example.com/start", activity.url().text.toString())
        button.performClick()
        assertEquals("https://other.example/target", activity.url().text.toString())
        assertEquals("My app", activity.name().text.toString())
        assertEquals(View.GONE, button.visibility)
        assertEquals(View.GONE, notice.visibility)
        assertTrue(saved().isEmpty())
    }

    @Test fun refreshClearsPreviousRedirectOffer() {
        val activity = open()
        activity.url().setText("https://example.com/start")
        val stages = EditorActivity::class.java.getDeclaredField("stages").apply {
            isAccessible = true
        }.get(activity) as EditorDiscovery
        stages.document(DiscoveryDocumentResult(null, true,
            redirectedUrl = "https://other.example/target"))
        EditorActivity::class.java.getDeclaredMethod("updateDiscoveryPreview").apply {
            isAccessible = true
        }.invoke(activity)
        activity.findViewById<View>(R.id.editor_refresh_details).performClick()
        assertNull(stages.redirectedUrl)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.editor_use_redirected_url).visibility)
        assertEquals("https://example.com/start", activity.url().text.toString())
    }

    @Test fun currentPageUrlIsCopiedIntoDraftAndSavedOnlyOnSave() {
        val original = app("live-app", "https://example.com/")
        runBlocking { repository.add(original) }
        val unregister = ShellServices.shared(context).shells.register(original.id,
            close = {}, currentUrl = { "https://example.com/next" })
        try {
            val activity = open(original.id)
            val button = activity.findViewById<View>(R.id.editor_use_current_url)
            assertEquals(View.VISIBLE, button.visibility)
            button.performClick()
            assertEquals("https://example.com/next", activity.url().text.toString())
            assertEquals(original.configuredUrl, saved().single().configuredUrl)
            activity.save()
            await { saved().single().configuredUrl == "https://example.com/next" }
        } finally { unregister() }
    }

    @Test fun currentPageUrlActionRequiresLiveCommittedPage() {
        val original = app("live-app", "https://example.com/")
        runBlocking { repository.add(original) }
        val unregister = ShellServices.shared(context).shells.register(original.id,
            close = {}, currentUrl = { null })
        try {
            val activity = open(original.id)
            assertEquals(View.GONE,
                activity.findViewById<View>(R.id.editor_use_current_url).visibility)
        } finally { unregister() }
    }

    @Test fun currentPageUrlActionHidesWhenLivePageBecomesUnavailable() {
        val original = app("live-app", "https://example.com/")
        runBlocking { repository.add(original) }
        var current: String? = "https://example.com/next"
        val unregister = ShellServices.shared(context).shells.register(original.id,
            close = {}, currentUrl = { current })
        try {
            val activity = open(original.id)
            val button = activity.findViewById<View>(R.id.editor_use_current_url)
            assertEquals(View.VISIBLE, button.visibility)
            current = null
            button.performClick()
            assertEquals(View.GONE, button.visibility)
            assertEquals(original.configuredUrl, activity.url().text.toString())
        } finally { unregister() }
    }

    @Test fun schemelessUrlNormalizesAndNewAppIsAppended() {
        runBlocking { repository.add(app("existing", "https://existing.example/")) }
        val activity = open()
        assertEquals(activity.getString(R.string.editor_add_title),
            (activity.findViewById<View>(R.id.editor_scroll) as android.view.ViewGroup)
                .let { (it.getChildAt(0) as android.view.ViewGroup).getChildAt(0) as TextView }.text.toString())
        activity.url().setText("  example.com  ")
        // Name is initialized synchronously from the canonical host.
        assertEquals("example.com", activity.name().text.toString())
        assertFalse(activity.thirdParty().isChecked)
        assertNotNull(activity.findViewById<View>(R.id.editor_mypass_status))
        activity.save()
        await { saved().size == 2 }
        val added = saved()[1]
        assertEquals("https://example.com/", added.configuredUrl)
        assertEquals("https://example.com/", added.launchUrl)
        assertEquals("https://example.com/", added.scope)
        assertEquals("example.com", added.name)
        assertEquals(NameSource.DISCOVERED, added.nameSource)
        assertFalse(added.allowThirdPartyCookies)
        assertEquals(1, added.sortPosition)
        assertTrue(ProfileNames.isOpaqueId(added.id))
        await { activity.isFinishing }
    }

    @org.robolectric.annotation.Config(sdk = [31])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun existingStoredIconIsPreviewedWithoutStartingDiscovery() {
        runBlocking { ShellServices.shared(context).profiles.prepareConfiguration() }
        val icon = SelectedIcon(IconCandidate("https://example.com/favicon.ico",
            IconCandidate.Source.FAVICON), android.graphics.Bitmap.createBitmap(48, 48,
                android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) })
        val reference = IconStore(context).save("preview", icon)
        runBlocking { repository.add(app("preview", "https://example.com/").copy(iconReference = reference)) }
        val activity = open("preview")
        val shown = activity.findViewById<android.widget.ImageView>(R.id.editor_discovery_icon)
            .drawable as android.graphics.drawable.BitmapDrawable
        assertEquals(android.graphics.Color.RED, shown.bitmap.getPixel(24, 24))
        assertEquals("", activity.findViewById<TextView>(R.id.editor_discovery_status).text.toString())
        IconStore(context).delete("preview")
    }

    @Test fun completedTitleAndFaviconArePreviewedAndStoredOnSave() {
        val activity = open()
        activity.url().setText("example.com")
        val stages = EditorDiscovery(null, "https://example.com/")
        stages.document(DiscoveryDocumentResult("https://example.com/page", false))
        stages.title = "Page title"
        stages.manifestCompleted(null)
        stages.icon = SelectedIcon(IconCandidate("https://example.com/favicon.ico",
            IconCandidate.Source.FAVICON), android.graphics.Bitmap.createBitmap(48, 48,
                android.graphics.Bitmap.Config.ARGB_8888))
        EditorActivity::class.java.getDeclaredField("stages").apply { isAccessible = true }
            .set(activity, stages)
        EditorActivity::class.java.getDeclaredMethod("updateDiscoveryPreview")
            .apply { isAccessible = true }.invoke(activity)
        assertEquals("Page title", activity.name().text.toString())
        activity.name().setText("My name")
        val suggestion = activity.findViewById<View>(R.id.editor_suggested_name)
        assertEquals(View.VISIBLE, suggestion.visibility)
        suggestion.performClick()
        assertEquals("Page title", activity.name().text.toString())
        activity.save()
        await { saved().size == 1 }
        val result = saved().single()
        assertEquals("https://example.com/page", result.launchUrl)
        assertEquals(NameSource.DISCOVERED, result.nameSource)
        assertTrue(IconStore(context).exists(result.iconReference!!))
        IconStore(context).delete(result.id)
    }

    @Test fun httpUrlsFailInlineValidation() {
        val activity = open()
        for (input in listOf("http://localhost:8080", "http://example.com/")) {
            activity.url().setText(input)
            activity.name().setText("Local")
            activity.save()
            assertEquals(activity.getString(R.string.editor_https_required), activity.urlError())
        }
        activity.url().setText("exa mple.com")
        activity.save()
        assertEquals(activity.getString(R.string.editor_url_invalid), activity.urlError())
        activity.url().setText("")
        activity.save()
        assertEquals(activity.getString(R.string.editor_url_required), activity.urlError())
        activity.url().setText("https://example.com/" + "a".repeat(UrlPolicy.MAX_URL_BYTES))
        activity.save()
        assertEquals(activity.getString(R.string.editor_url_too_long), activity.urlError())
        // A corrected URL clears the error as the user types.
        activity.url().setText("example.com")
        assertNull(activity.urlError())
        assertFalse(activity.isFinishing)
        assertTrue(saved().isEmpty())
    }

    @Test fun nameIsValidatedAndUserEditedNameIsKept() {
        val activity = open()
        activity.url().setText("example.com")
        activity.name().setText("  ")
        activity.save()
        assertEquals(activity.getString(R.string.editor_name_required), activity.nameError())
        activity.name().setText("Bad\u202Ename")
        activity.save()
        assertEquals(activity.getString(R.string.editor_name_invalid), activity.nameError())
        activity.name().setText("x".repeat(AppNames.MAX_CODE_POINTS + 1))
        activity.save()
        assertEquals(activity.getString(R.string.editor_name_too_long, AppNames.MAX_CODE_POINTS),
            activity.nameError())
        activity.name().setText("  My app ")
        assertNull(activity.nameError())
        activity.url().setText("other.example")
        assertEquals("  My app ", activity.name().text.toString())
        activity.save()
        await { saved().size == 1 }
        assertEquals("My app", saved()[0].name)
        assertEquals(NameSource.USER_EDITED, saved()[0].nameSource)
        assertEquals("https://other.example/", saved()[0].configuredUrl)
    }

    @Test fun hostDerivedNameFollowsUrlAndSurvivesRecreation() {
        var activity = open()
        activity.url().setText("first.example")
        activity.url().setText("second.example/path")
        assertEquals("second.example", activity.name().text.toString())
        controller!!.recreate()
        activity = controller!!.get()
        await { activity.findViewById<View>(R.id.editor_save).isEnabled }
        assertEquals("second.example", activity.name().text.toString())
        activity.url().setText("third.example")
        assertEquals("third.example", activity.name().text.toString())
        activity.save()
        await { saved().size == 1 }
        assertEquals(NameSource.DISCOVERED, saved()[0].nameSource)
    }

    @Test fun thirdPartyCookiesRequireAcknowledgement() {
        val activity = open()
        assertTrue(activity.findViewById<TextView>(R.id.editor_third_party_cookies_note).text
            .contains("does not delete stored cookies"))
        activity.thirdParty().performClick()
        ShadowLooper.idleMainLooper()
        assertFalse(activity.thirdParty().isChecked)
        latestDialog().press(DialogInterface.BUTTON_NEGATIVE)
        assertFalse(activity.thirdParty().isChecked)
        activity.thirdParty().performClick()
        ShadowLooper.idleMainLooper()
        val warning = latestDialog()
        assertEquals(activity.getString(R.string.third_party_cookies_warning),
            warning.findViewById<TextView>(android.R.id.message)!!.text.toString())
        warning.press(DialogInterface.BUTTON_POSITIVE)
        assertTrue(activity.thirdParty().isChecked)
        // Turning it off needs no acknowledgement.
        activity.thirdParty().performClick()
        assertFalse(activity.thirdParty().isChecked)
        activity.thirdParty().performClick()
        ShadowLooper.idleMainLooper()
        latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        activity.url().setText("example.com")
        activity.save()
        await { saved().size == 1 }
        assertTrue(saved()[0].allowThirdPartyCookies)
    }

    @Test fun sensitiveUrlNeedsConsentAndDeclineBlocksSave() {
        val activity = open()
        activity.url().setText("example.com/?token=abc123")
        activity.save()
        val warning = latestDialog()
        val message = warning.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(message.contains("token"))
        assertFalse(message.contains("abc123"))
        assertTrue(message.contains("backup"))
        warning.press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(activity.getString(R.string.editor_sensitive_declined), activity.urlError())
        assertTrue(saved().isEmpty())
        assertFalse(activity.isFinishing)
        activity.save()
        latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        await { saved().size == 1 }
        val record = saved()[0]
        assertEquals("https://example.com/?token=abc123", record.configuredUrl)
        assertTrue(SensitiveUrlConsent.parse(record.sensitiveUrlConsent).covers(record.configuredUrl))
        assertFalse(record.sensitiveUrlConsent!!.contains("abc123"))
    }

    @Test fun consentedUrlIsNotAskedAgain() {
        runBlocking {
            repository.add(app("a", "https://example.com/?token=abc").copy(sensitiveUrlConsent =
                SensitiveUrlConsent.NONE.grant("https://example.com/?token=abc").serialize()))
        }
        val activity = open("a")
        activity.name().setText("Renamed")
        val before = ShadowDialog.getLatestDialog()
        activity.save()
        assertSame(before, ShadowDialog.getLatestDialog())
        await { saved()[0].name == "Renamed" }
    }

    @Test fun cancelLeavesRecordUntouchedAndEditKeepsPosition() {
        runBlocking {
            repository.add(app("a", "https://a.example/"))
            repository.add(app("b", "https://b.example/path").copy(name = "Bee"))
        }
        var activity = open("b")
        assertEquals(activity.getString(R.string.editor_edit_title),
            (activity.findViewById<View>(R.id.editor_scroll) as android.view.ViewGroup)
                .let { (it.getChildAt(0) as android.view.ViewGroup).getChildAt(0) as TextView }.text.toString())
        assertEquals("Bee", activity.name().text.toString())
        assertEquals("https://b.example/path", activity.url().text.toString())
        val original = saved()
        activity.name().setText("Changed")
        activity.findViewById<View>(R.id.editor_cancel).performClick()
        assertTrue(activity.isFinishing)
        assertEquals(original, saved())
        controller!!.pause().stop().destroy()

        activity = open("b")
        activity.name().setText("Changed")
        activity.url().setText("b.example/other")
        activity.save()
        await { saved()[1].name == "Changed" }
        val updated = saved()[1]
        assertEquals("b", updated.id)
        assertEquals(1, updated.sortPosition)
        assertEquals("https://b.example/other", updated.configuredUrl)
        assertEquals("https://b.example/other", updated.launchUrl)
        assertEquals(NameSource.USER_EDITED, updated.nameSource)
        assertEquals(original[0], saved()[0])
    }

    @Test fun originChangingEditRequiresSaveTimeConfirmation() {
        runBlocking { repository.add(app("a", "https://a.example/")) }
        val activity = open("a")
        assertEquals(View.GONE, activity.findViewById<View>(R.id.editor_clearing_notice).visibility)
        activity.url().setText("other.example")
        activity.save()
        assertNull(activity.urlError())
        assertTrue(latestDialog().isShowing)
        latestDialog().press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals("https://a.example/", saved()[0].configuredUrl)
    }

    @Test fun confirmedOriginChangeStoresNewUrlAndRetiresProfile() {
        runBlocking { repository.add(app("a", "https://a.example/").copy(iconReference = "old-icon")) }
        val activity = open("a")
        activity.url().setText("https://other.example/path")
        activity.save()
        assertEquals("https://a.example/", saved()[0].configuredUrl)
        latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        await { saved()[0].configuredUrl == "https://other.example/path" }
        assertEquals(1L, saved()[0].profileGeneration)
        assertNull(saved()[0].iconReference)
        assertEquals("https://other.example/path", saved()[0].launchUrl)
    }

    @org.robolectric.annotation.Config(sdk = [31])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun originSavePrunesOldIconAndKeepsExactDraftDiscoveredIcon() {
        installMaintenance { true }
        fun icon(url: String) = SelectedIcon(IconCandidate(url, IconCandidate.Source.FAVICON),
            android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888))
        val icons = IconStore(context)
        val reference = icons.save("a", icon("https://a.example/favicon.ico"))
        val old = app("a", "https://a.example/").copy(iconReference = reference)
        runBlocking { repository.add(old) }
        val activity = open("a")
        activity.url().setText("https://other.example/path")
        val stages = EditorDiscovery(old, "https://other.example/path")
        stages.icon = icon("https://other.example/favicon.ico")
        EditorActivity::class.java.getDeclaredField("stages").apply { isAccessible = true }
            .set(activity, stages)
        activity.save()
        latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        await { saved()[0].profileState == ProfileState.ACTIVE && saved()[0].profileGeneration == 1L }
        assertFalse(icons.exists(reference))
        assertNotEquals(reference, saved()[0].iconReference)
        assertTrue(icons.exists(saved()[0].iconReference!!))
        icons.delete("a")
    }

    @Test fun sensitiveOriginSaveRequiresBothConsentsAfterCancelAndRepeatSave() {
        runBlocking { repository.add(app("a", "https://a.example/")) }
        val activity = open("a")
        activity.url().setText("https://other.example/?token=abc123")
        activity.save()
        latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        assertEquals(activity.getString(R.string.editor_origin_change_confirmation),
            latestDialog().findViewById<TextView>(android.R.id.message)!!.text.toString())
        latestDialog().press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals("https://a.example/", saved()[0].configuredUrl)
        activity.save()
        latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        await { saved()[0].configuredUrl == "https://other.example/?token=abc123" }
        assertTrue(SensitiveUrlConsent.parse(saved()[0].sensitiveUrlConsent).covers(saved()[0].configuredUrl))
    }

    @Test fun clearingEditorAllowsOnlySameOriginChanges() {
        runBlocking {
            repository.add(app("a", "https://a.example/").copy(profileState = ProfileState.CLEARING,
                profileGeneration = 1, retiringProfileGeneration = 0))
        }
        val activity = open("a")
        await { activity.findViewById<View>(R.id.editor_clearing_notice).visibility == View.VISIBLE }
        activity.url().setText("https://other.example/")
        activity.save()
        assertEquals(activity.getString(R.string.editor_origin_change_clearing), activity.urlError())
        assertEquals("https://a.example/", saved()[0].configuredUrl)
        activity.url().setText("https://a.example/path")
        assertNull(activity.urlError())
        activity.name().setText("Renamed")
        activity.thirdParty().performClick()
        ShadowLooper.idleMainLooper()
        latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        activity.save()
        await { saved()[0].name == "Renamed" }
        val record = saved()[0]
        assertEquals("https://a.example/path", record.configuredUrl)
        assertTrue(record.allowThirdPartyCookies)
        // Saving never changes the profile lifecycle state.
        assertEquals(ProfileState.CLEARING, record.profileState)
        assertEquals(0L, record.retiringProfileGeneration)
    }

    @Test fun clearingNoticeFollowsTheRecord() {
        runBlocking { repository.add(app("a", "https://a.example/")) }
        val activity = open("a")
        val notice = activity.findViewById<View>(R.id.editor_clearing_notice)
        assertEquals(View.GONE, notice.visibility)
        runBlocking { repository.reserveNextGeneration("a") }
        await { notice.visibility == View.VISIBLE }
        runBlocking { repository.finishClearing("a", 0) }
        await { notice.visibility == View.GONE }
    }

    private fun installMaintenance(delete: (String) -> Boolean) {
        val store = MaintenanceStore(context).also { maintenanceStore = it }
        store.createInstallationSentinel()
        val key = ProviderKey("test.provider", "1", 28, "x86_64")
        val compatibility = ProviderCompatibility({ ProviderSnapshot(key, true) },
            { ProviderSelfTestResult(it, true, true, true, true, true, true) },
            floors = mapOf(key.packageName to listOf(1)))
        val shells = LiveShellRegistry()
        val backend = object : WebProfileBackend {
            override fun deleteProfile(name: String) = delete(name)
            override fun profileExists(name: String) = false
            override fun createBoundWebView(context: android.content.Context, name: String):
                android.webkit.WebView = error("Editor must not load an app profile")
            override fun setThirdPartyCookies(webView: android.webkit.WebView, allow: Boolean) = Unit
        }
        val profiles = ProfileManager(repository, store, compatibility, backend, shells,
            object : AppNativeData {
                override fun deleteIcons(appId: String) = Unit
                override fun pruneIcons(appId: String, retainedReference: String?) =
                    IconStore(context).prune(appId, retainedReference)
                override fun widgetsChanged() = Unit
            }, main = Dispatchers.Unconfined, io = Dispatchers.Unconfined)
        ShellServices.install(ShellServices(repository, compatibility, shells, profiles, IconStore(context)))
    }

    private fun confirmClear(activity: EditorActivity) {
        activity.findViewById<View>(R.id.editor_clear).performClick()
        ShadowLooper.idleMainLooper()
        latestDialog().press(DialogInterface.BUTTON_POSITIVE)
    }

    @Test fun clearFailureOffersRetryAndPreservesConfiguration() {
        var failDeletion = true
        val calls = mutableListOf<String>()
        installMaintenance { name ->
            calls += name
            if (failDeletion) throw java.io.IOException("Deletion failed")
            true
        }
        val original = app("a", "https://a.example/")
        runBlocking { repository.add(original) }
        val activity = open("a")
        confirmClear(activity)
        await { activity.findViewById<TextView>(R.id.editor_maintenance_status).text ==
            activity.getString(R.string.editor_clear_failed) }
        assertEquals(ProfileState.CLEARING, saved().single().profileState)
        assertFalse(activity.findViewById<View>(R.id.editor_clear).isEnabled)
        failDeletion = false
        activity.findViewById<View>(R.id.editor_clear_retry).performClick()
        await { activity.findViewById<TextView>(R.id.editor_maintenance_status).text ==
            activity.getString(R.string.editor_clear_done) }
        val record = saved().single()
        assertEquals(original.copy(profileGeneration = 1, updatedAt = record.updatedAt), record)
        assertEquals(listOf(ProfileNames.app("a", 0), ProfileNames.app("a", 0)), calls)
    }

    @Test fun recreationKeepsClearInFlightAndRetainsDeferredOutcome() {
        installMaintenance { throw IllegalStateException("Profile loaded") }
        val gate = CompletableDeferred<Unit>()
        ShellServices.shared(context).shells.register("a") { gate.await() }
        runBlocking { repository.add(app("a", "https://a.example/")) }
        var activity = open("a")
        confirmClear(activity)
        await { saved().single().profileState == ProfileState.CLEARING }
        controller!!.recreate()
        activity = controller!!.get()
        await { activity.findViewById<View>(R.id.editor_remove).isEnabled }
        assertFalse(activity.findViewById<View>(R.id.editor_clear_retry).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.editor_save).isEnabled)
        gate.complete(Unit)
        await { activity.findViewById<TextView>(R.id.editor_maintenance_status).text ==
            activity.getString(R.string.editor_clear_deferred) }
        controller!!.recreate()
        activity = controller!!.get()
        await { activity.findViewById<TextView>(R.id.editor_maintenance_status).text ==
            activity.getString(R.string.editor_clear_deferred) }
        assertEquals(1L, saved().single().profileGeneration)
    }

    @Test fun postCommitRemovalFailureStillClosesEditor() {
        installMaintenance { throw java.io.IOException("Profile cleanup failed") }
        runBlocking { repository.add(app("a", "https://a.example/")) }
        val activity = open("a")
        activity.findViewById<View>(R.id.editor_remove).performClick()
        ShadowLooper.idleMainLooper()
        latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        await { saved().isEmpty() && activity.isFinishing }
        assertEquals(activity.getString(R.string.editor_remove_pending),
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    @Test fun removalSurvivesRecreationAndBlocksImeSave() {
        installMaintenance { true }
        val gate = CompletableDeferred<Unit>()
        ShellServices.shared(context).shells.register("a") { gate.await() }
        runBlocking { repository.add(app("a", "https://a.example/")) }
        var activity = open("a")
        activity.findViewById<View>(R.id.editor_remove).performClick()
        ShadowLooper.idleMainLooper()
        latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        activity.name().setText("Must not save")
        activity.name().onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
        assertEquals("a", saved().single().name)
        controller!!.recreate()
        activity = controller!!.get()
        ShadowLooper.idleMainLooper()
        assertFalse(activity.findViewById<View>(R.id.editor_save).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.editor_remove).isEnabled)
        gate.complete(Unit)
        await { saved().isEmpty() && activity.isFinishing }
    }

    @Test fun deferredClearReturnsUsableAndExplainsRestart() {
        installMaintenance { throw IllegalStateException("Profile still loaded") }
        runBlocking { repository.add(app("a", "https://a.example/")) }
        val activity = open("a")
        confirmClear(activity)
        await { activity.findViewById<TextView>(R.id.editor_maintenance_status).text ==
            activity.getString(R.string.editor_clear_deferred) }
        assertEquals(ProfileState.ACTIVE, saved().single().profileState)
        assertEquals(1L, saved().single().profileGeneration)
    }

    @Test fun maintenanceRequiresConfirmationAndCancelPreservesRecord() {
        runBlocking { repository.add(app("a", "https://a.example/")) }
        val activity = open("a")
        val original = saved()
        activity.findViewById<View>(R.id.editor_clear).performClick()
        ShadowLooper.idleMainLooper()
        val dialog = latestDialog()
        val message = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        listOf("cookies", "DOM storage", "IndexedDB/Web SQL", "Cache Storage", "HTTP caches",
            "service workers", "HTTP-auth", "permission", "form data", "browsing history",
            "Android app-level permissions", "MyPass").forEach { assertTrue(message.contains(it)) }
        dialog.press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(original, saved())
        activity.findViewById<View>(R.id.editor_remove).performClick()
        ShadowLooper.idleMainLooper()
        latestDialog().press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(original, saved())
        assertFalse(activity.isFinishing)
    }

    @Test fun removeConfirmationDeletesRecordEvenWithIncompatibleProvider() {
        runBlocking { repository.add(app("a", "https://a.example/")) }
        val activity = open("a")
        activity.findViewById<View>(R.id.editor_remove).performClick()
        ShadowLooper.idleMainLooper()
        latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        await { saved().isEmpty() && activity.isFinishing }
    }

    @Test fun clearingDisablesClearAndExposesRetryAndRemove() {
        installMaintenance { true }
        runBlocking { repository.add(app("a", "https://a.example/").copy(
            profileState = ProfileState.CLEARING, profileGeneration = 1, retiringProfileGeneration = 0)) }
        val activity = open("a")
        assertFalse(activity.findViewById<View>(R.id.editor_clear).isEnabled)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.editor_clear_retry).visibility)
        assertTrue(activity.findViewById<View>(R.id.editor_remove).isEnabled)
        activity.findViewById<View>(R.id.editor_clear_retry).performClick()
        await { activity.findViewById<TextView>(R.id.editor_maintenance_status).text.isNotEmpty() }
    }

    @Test fun removedAppClosesEditor() {
        runBlocking { repository.add(app("a", "https://a.example/")) }
        val activity = open("a")
        runBlocking { repository.remove("a") }
        await { activity.isFinishing }
    }

    private fun await(condition: () -> Boolean) {
        repeat(100) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(20)
        }
        fail("UI did not update")
    }

    private fun app(id: String, url: String) = ConfiguredApp(
        id = id, name = id, nameSource = NameSource.USER_EDITED, configuredUrl = url,
        finalUrl = null, launchUrl = url, scope = url,
        createdAt = 1, updatedAt = 1,
    )
}
