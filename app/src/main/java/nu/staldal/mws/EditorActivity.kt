package nu.staldal.mws

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Add/edit screen (R§6.1). Nothing is written until Save; Cancel and Back leave the persisted
 * record untouched. Editing works while the WebView provider is incompatible.
 */
class EditorActivity : AppCompatActivity() {
    private lateinit var repository: ConfigurationRepository
    private lateinit var myPassClient: MyPassClient
    private lateinit var urlLayout: TextInputLayout
    private lateinit var urlField: TextInputEditText
    private lateinit var nameLayout: TextInputLayout
    private lateinit var nameField: TextInputEditText
    private lateinit var thirdPartyCookies: SwitchMaterial
    private lateinit var clearingNotice: TextView
    private lateinit var loadError: TextView
    private lateinit var myPassStatus: MyPassStatusIndicator
    private lateinit var clearButton: MaterialButton
    private lateinit var retryButton: MaterialButton
    private lateinit var removeButton: MaterialButton
    private lateinit var maintenanceStatus: TextView
    private val maintenance get() = ShellServices.shared(this).editorMaintenance
    private val maintenanceState get() = appId?.let { maintenance.state(it).value } ?: EditorMaintenanceState()
    private val maintaining get() = maintenanceState.clearing
    private val removing get() = maintenanceState.removing || maintenanceState.removalResult != null
    private lateinit var saveButton: View

    private val appId: String? get() = intent.getStringExtra(EXTRA_APP_ID)
    /** The persisted record as last observed; `null` for a new app or before loading. */
    private var saved: ConfiguredApp? = null
    private var loaded = false
    private var saving = false
    private var pendingOriginChange = false
    private var nameSource = NameSource.DISCOVERED
    /** The host last copied into Name; it keeps following the URL until the user edits Name. */
    private var hostName: String? = null
    private var programmatic = false
    private var restoredState: Bundle? = null
    private lateinit var discoveryHost: FrameLayout
    private lateinit var discoveryStatus: TextView
    private lateinit var discoveryUrl: TextView
    private lateinit var suggestion: MaterialButton
    private lateinit var iconPreview: ImageView
    private var discovery: DiscoverySession? = null
    private var stages: EditorDiscovery? = null
    private var metadataJob: Job? = null
    private var blockedDeadline: Job? = null
    private var nativeClient: NativeDiscoveryClient? = null
    private var stageIdentity = 0L
    private var discoveryBytes = java.util.concurrent.atomic.AtomicLong(0)

    private fun cancelDiscovery() {
        stageIdentity++
        discoveryBytes = java.util.concurrent.atomic.AtomicLong(0)
        blockedDeadline?.cancel()
        blockedDeadline = null
        nativeClient?.cancel()
        nativeClient = null
        metadataJob?.cancel()
        discovery?.cancel()
    }

    private fun prepareDiscovery() {
        discovery = DiscoverySession(discoveryHost, ShellServices.shared(this).profiles,
            lifecycleScope, onDocument = { attempt, result ->
                stages?.document(result)
                updateDiscoveryPreview()
                if (result.crossOrigin) {
                    discoveryStatus.setText(R.string.editor_discovery_cross_origin)
                    discovery?.finish(attempt.id)
                } else {
                    if (result.failure != null) discoveryStatus.setText(R.string.editor_discovery_failed)
                    // Start the independent favicon fallback immediately. DOM success replaces
                    // this work with manifest/page-icon discovery; a stalled DOM cannot skip it.
                    fetchMetadata(attempt, null)
                }
            }, onOverallTimeout = { _ ->
                nativeClient?.cancel()
                metadataJob?.cancel()
                discoveryStatus.setText(R.string.editor_discovery_failed)
                updateDiscoveryPreview()
            }, onBlocked = {
                discoveryStatus.setText(R.string.editor_discovery_failed)
                stages?.let { current ->
                    fetchMetadata(DiscoveryAttempt(-1,
                        UrlPolicy.parseHttps(current.configuredUrl)!!, DiscoveryTrigger.REFRESH), null)
                    blockedDeadline?.cancel()
                    blockedDeadline = lifecycleScope.launch {
                        delay(30_000)
                        nativeClient?.cancel()
                        metadataJob?.cancel()
                        discoveryStatus.setText(R.string.editor_discovery_failed)
                    }
                }
            }, onSnapshot = { attempt, snapshot -> fetchMetadata(attempt, snapshot) })
    }

    private fun fetchMetadata(attempt: DiscoveryAttempt, snapshot: DiscoveryDomSnapshot?) {
        val current = stages ?: return
        val identity = stageIdentity
        metadataJob?.cancel()
        nativeClient?.cancel()
        val document = current.finalUrl ?: current.configuredUrl
        val client = NativeDiscoveryClient(document, UrlPolicy.parseHttps(document)!!.origin,
            attemptBytes = discoveryBytes)
        nativeClient = client
        metadataJob = lifecycleScope.launch {
            if (snapshot != null) current.title = snapshot.title
            updateDiscoveryPreview()
            val link = snapshot?.selectedManifest
            val response = withContext(Dispatchers.IO) {
                link?.takeIf { it.usable }?.url?.let {
                    client.fetchManifest(it, link.crossorigin, link.referrerPolicy
                        ?.takeIf(String::isNotBlank) ?: "no-referrer")
                }
            }
            if (identity != stageIdentity || stages !== current) return@launch
            var manifestFailed = link != null && response == null
            if (snapshot != null && link == null) current.manifestCompleted(null)
            else if (response != null) {
                val result = ManifestProcessor.process(response.body.toString(Charsets.UTF_8),
                    response.url, current.finalUrl, current.configuredUrl, current.title)
                if (result.snapshot != null) current.manifestCompleted(result.snapshot)
                else manifestFailed = true
            }
            updateDiscoveryPreview()
            val candidates = mutableListOf<IconCandidate>()
            if (response == null) current.manifest?.let { client.retainManifestSource(it.manifestUrl) }
            if (snapshot != null) candidates += current.manifest?.icons.orEmpty()
            snapshot?.icons?.filter { it.usable && it.mediaMatches }?.forEachIndexed { index, link ->
                candidates += IconCandidate(link.url!!, IconCandidate.Source.PAGE,
                    link.sizes, link.type, null, index, link.referrerPolicy)
            }
            candidates += IconCandidate(UrlPolicy.parseHttps(document)!!.origin + "/favicon.ico",
                IconCandidate.Source.FAVICON)
            client.setReferrerPolicies("no-referrer", "no-referrer")
            val icon = withContext(Dispatchers.IO) { IconPipeline.select(candidates, client) }
            if (identity != stageIdentity || stages !== current) return@launch
            current.icon = icon ?: current.icon
            updateDiscoveryPreview()
            discoveryStatus.setText(if (snapshot == null || manifestFailed || icon == null || client.limitsExceeded)
                R.string.editor_discovery_failed else R.string.editor_discovery_credentialless)
            if (snapshot != null || attempt.documentResult?.failure != null) discovery?.finish(attempt.id)
        }
    }

    private fun updateDiscoveryPreview() {
        val current = stages ?: return
        val proposed = current.suggestedName()
        if (nameSource != NameSource.USER_EDITED) {
            setProgrammatically { nameField.setText(proposed) }
            hostName = proposed
            if (nameLayout.error != null) showNameError()
        }
        suggestion.text = getString(R.string.editor_use_suggested_name, proposed)
        suggestion.visibility = if (nameSource == NameSource.USER_EDITED &&
            AppNames.normalize(nameField.text.toString()) != proposed) View.VISIBLE else View.GONE
        discoveryUrl.text = current.resolved().launchUrl
        if (current.icon != null) iconPreview.setImageBitmap(current.icon?.source)
        else showRetainedIcon()
    }

    private fun showRetainedIcon() {
        val store = IconStore(this)
        val retained = saved?.takeIf { old ->
            val current = stages?.configuredUrl?.let(UrlPolicy::parseHttps)
            val previous = UrlPolicy.parseHttps(old.configuredUrl)
            current != null && previous != null && UrlPolicy.sameOrigin(current, previous)
        }?.iconReference?.let { store.load(it, 48) }
        if (retained != null) iconPreview.setImageBitmap(retained)
        else iconPreview.setImageDrawable(store.defaultIcon())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredState = savedInstanceState
        myPassClient = MyPassClient(this)
        repository = ShellServices.shared(this).repository
        savedInstanceState?.let { state ->
            nameSource = NameSource.valueOf(state.getString(STATE_NAME_SOURCE, NameSource.DISCOVERED.name))
            hostName = state.getString(STATE_HOST_NAME)
        }
        buildScreen()
        appId?.let { id ->
            lifecycleScope.launch {
                maintenance.state(id).collect { state ->
                    renderMaintenance(state)
                }
            }
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        myPassStatus.refresh(myPassClient)
    }

    override fun onDestroy() {
        cancelDiscovery()
        lifecycleScope.launch(NonCancellable) { discovery?.close() }
        myPassClient.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_NAME_SOURCE, nameSource.name)
        outState.putString(STATE_HOST_NAME, hostName)
        outState.putBoolean(STATE_THIRD_PARTY, thirdPartyCookies.isChecked)
    }

    private fun buildScreen() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        column.addView(TextView(this).apply {
            setText(if (appId == null) R.string.editor_add_title else R.string.editor_edit_title)
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            textSize = 24f
            setPadding(0, 0, 0, dp(12))
        })
        loadError = TextView(this).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            id = R.id.editor_load_error
            setText(R.string.editor_load_failed)
            visibility = View.GONE
        }
        column.addView(loadError)
        clearingNotice = TextView(this).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            id = R.id.editor_clearing_notice
            setText(R.string.editor_clearing_notice)
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8))
        }
        column.addView(clearingNotice)

        urlField = TextInputEditText(this).apply {
            id = R.id.editor_url
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_NEXT
            maxLines = 3
            // Drop the platform's default single-line length cap: a truncated paste would be a
            // different URL, while overlong input must fail validation instead.
            filters = arrayOf()
        }
        urlLayout = TextInputLayout(this).apply {
            id = R.id.editor_url_layout
            hint = getString(R.string.editor_url)
            helperText = getString(R.string.editor_url_helper)
            addView(urlField)
        }
        column.addView(urlLayout)
        nameField = TextInputEditText(this).apply {
            id = R.id.editor_name
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }
        nameLayout = TextInputLayout(this).apply {
            id = R.id.editor_name_layout
            hint = getString(R.string.editor_name)
            addView(nameField)
        }
        column.addView(nameLayout)
        discoveryStatus = TextView(this).apply { id = R.id.editor_discovery_status; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(discoveryStatus)
        discoveryUrl = TextView(this)
        column.addView(discoveryUrl)
        iconPreview = ImageView(this).apply { id = R.id.editor_discovery_icon; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        column.addView(iconPreview, LinearLayout.LayoutParams(dp(48), dp(48)))
        suggestion = MaterialButton(this).apply {
            id = R.id.editor_suggested_name
            visibility = View.GONE
            setOnClickListener {
                nameSource = NameSource.DISCOVERED
                updateDiscoveryPreview()
            }
        }
        column.addView(suggestion)
        column.addView(MaterialButton(this).apply {
            id = R.id.editor_refresh_details
            setText(R.string.editor_refresh_details)
            setOnClickListener {
                val url = UrlPolicy.normalizeUserInput(urlField.text.toString()) ?: return@setOnClickListener
                cancelDiscovery()
                if (stages?.configuredUrl != url.serialized) stages = EditorDiscovery(saved, url.serialized)
                discoveryStatus.setText(R.string.editor_discovery_loading)
                discovery?.refresh(url.serialized)
            }
        })
        discoveryHost = FrameLayout(this)
        column.addView(discoveryHost, LinearLayout.LayoutParams(-1, dp(96)))
        prepareDiscovery()

        myPassStatus = MyPassStatusIndicator(this).apply {
            id = R.id.editor_mypass_status
            setPadding(0, dp(12), 0, dp(12))
        }
        column.addView(myPassStatus)

        thirdPartyCookies = SwitchMaterial(this).apply {
            id = R.id.editor_third_party_cookies
            setText(R.string.editor_third_party_cookies)
            // Restored manually so restoring never replays the acknowledgement flow.
            isSaveEnabled = false
            minHeight = dp(48)
        }
        column.addView(thirdPartyCookies)
        column.addView(TextView(this).apply {
            id = R.id.editor_third_party_cookies_note
            setText(R.string.editor_third_party_cookies_note)
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        buttons.addView(MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = R.id.editor_cancel
            setText(R.string.editor_cancel)
            setOnClickListener { finish() }
        })
        saveButton = MaterialButton(this).apply {
            id = R.id.editor_save
            setText(R.string.editor_save)
            isEnabled = false
            setOnClickListener { save() }
        }
        buttons.addView(saveButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        column.addView(buttons)
        maintenanceStatus = TextView(this).apply { id = R.id.editor_maintenance_status; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(maintenanceStatus)
        clearButton = MaterialButton(this).apply {
            id = R.id.editor_clear
            setText(R.string.editor_clear)
            visibility = if (appId == null) View.GONE else View.VISIBLE
            isEnabled = false
            setOnClickListener {
                AlertDialog.Builder(this@EditorActivity)
                    .setTitle(R.string.editor_clear)
                    .setMessage(R.string.editor_clear_confirmation)
                    .setNegativeButton(R.string.editor_cancel, null)
                    .setPositiveButton(R.string.editor_clear) { _, _ -> clearData() }
                    .show()
            }
        }
        column.addView(clearButton)
        retryButton = MaterialButton(this).apply {
            id = R.id.editor_clear_retry
            setText(R.string.editor_retry)
            visibility = View.GONE
            setOnClickListener { clearData() }
        }
        column.addView(retryButton)
        removeButton = MaterialButton(this).apply {
            id = R.id.editor_remove
            setText(R.string.editor_remove)
            visibility = if (appId == null) View.GONE else View.VISIBLE
            isEnabled = false
            setOnClickListener {
                AlertDialog.Builder(this@EditorActivity)
                    .setTitle(R.string.editor_remove)
                    .setMessage(R.string.editor_remove_confirmation)
                    .setNegativeButton(R.string.editor_cancel, null)
                    .setPositiveButton(R.string.editor_remove) { _, _ -> removeApp() }
                    .show()
            }
        }
        column.addView(removeButton)
        val scroll = ScrollView(this).apply {
            id = R.id.editor_scroll
            addView(column)
        }
        setContentView(android.widget.FrameLayout(this).apply {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                    androidx.core.view.WindowInsetsCompat.Type.ime())
                // Resize the viewport so ScrollView keeps the focused field above the keyboard.
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
            addView(scroll)
        })
    }

    /** Registered after view-state restoration, so restored text is not taken as user input. */
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        urlField.addTextChangedListener(watcher { onUrlChanged() })
        urlField.setOnFocusChangeListener { _, focused -> if (!focused) {
            showUrlError()
            discovery?.focusLost(urlField.text.toString())
        } }
        nameField.addTextChangedListener(watcher {
            if (!programmatic) {
                nameSource = NameSource.USER_EDITED
                hostName = null
                if (stages != null) updateDiscoveryPreview()
            }
            if (nameLayout.error != null) showNameError()
        })
        nameField.setOnFocusChangeListener { _, focused -> if (!focused) showNameError() }
        nameField.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }
        thirdPartyCookies.setOnCheckedChangeListener { _, checked ->
            if (checked && !programmatic) {
                setProgrammatically { thirdPartyCookies.isChecked = false }
                confirmThirdPartyCookies()
            }
        }
    }

    private fun load() {
        val id = appId
        lifecycleScope.launch {
            try {
                ShellServices.shared(this@EditorActivity).profiles.prepareConfiguration()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                loadError.visibility = View.VISIBLE
                return@launch
            }
            if (id == null) {
                populate(null)
                return@launch
            }
            pendingOriginChange = ShellServices.shared(this@EditorActivity).profiles.hasPendingOriginChange(id)
            if (pendingOriginChange) maintenanceStatus.setText(R.string.editor_origin_change_pending)
            repository.observe(id).collect { app ->
                if (app == null) {
                    if (!saving && !removing) {
                        Toast.makeText(this@EditorActivity, R.string.app_unavailable, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@collect
                }
                saved = app
                if (!loaded) populate(app)
                clearingNotice.visibility =
                    if (app.profileState == ProfileState.CLEARING) View.VISIBLE else View.GONE
                updateMaintenanceButtons()
                if (urlLayout.error != null) showUrlError()
            }
        }
    }

    private fun updateMaintenanceButtons() {
        clearButton.isEnabled = loaded && !saving && !maintaining && !removing &&
            saved?.profileState == ProfileState.ACTIVE
        removeButton.isEnabled = loaded && !saving && !removing
        val failed = maintenanceState.clearResult.let { it is ClearResult.Failed || it == ClearResult.Incompatible }
        retryButton.visibility = if (pendingOriginChange || saved?.profileState == ProfileState.CLEARING || failed)
            View.VISIBLE else View.GONE
        retryButton.isEnabled = !saving && !maintaining && !removing
        saveButton.isEnabled = loaded && !saving && !maintaining && !removing &&
            (!pendingOriginChange || saved?.profileState == ProfileState.CLEARING)
    }

    private fun renderMaintenance(state: EditorMaintenanceState) {
        if (state.clearResult == ClearResult.Cleared || state.clearResult == ClearResult.PendingRestart)
            pendingOriginChange = false
        updateMaintenanceButtons()
        val message = when {
            state.removalFailed -> R.string.editor_remove_failed
            state.clearing -> R.string.editor_clearing_notice
            else -> when (state.clearResult) {
                ClearResult.Cleared -> R.string.editor_clear_done
                ClearResult.PendingRestart -> R.string.editor_clear_deferred
                ClearResult.Incompatible -> R.string.editor_clear_incompatible
                ClearResult.MissingApp -> R.string.app_unavailable
                is ClearResult.Failed -> R.string.editor_clear_failed
                null -> null
            }
        }
        if (message != null) maintenanceStatus.setText(message)
        state.removalResult?.let { result ->
            Toast.makeText(applicationContext, when (result) {
                RemovalResult.Removed, RemovalResult.MissingApp -> R.string.editor_removed
                RemovalResult.ProfileDeletionPending -> R.string.editor_remove_pending
                is RemovalResult.NativeDeletionPending -> R.string.editor_remove_native_pending
            }, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun clearData() {
        val id = appId ?: return
        if (saving || maintaining || removing) return
        cancelDiscovery()
        discoveryStatus.setText(R.string.editor_discovery_stopped)
        maintenance.clear(id)
        updateMaintenanceButtons()
    }

    private fun removeApp() {
        val id = appId ?: return
        if (saving || removing) return
        cancelDiscovery()
        discoveryStatus.setText(R.string.editor_discovery_stopped)
        maintenance.remove(id)
        updateMaintenanceButtons()
    }

    /** Fills the fields once; after recreation the views already hold the draft. */
    private fun populate(app: ConfiguredApp?) {
        loaded = true
        val restored = restoredState
        setProgrammatically {
            if (restored != null) {
                thirdPartyCookies.isChecked = restored.getBoolean(STATE_THIRD_PARTY)
            } else if (app != null) {
                nameSource = app.nameSource
                nameField.setText(app.name)
                urlField.setText(app.configuredUrl)
                thirdPartyCookies.isChecked = app.allowThirdPartyCookies
            }
        }
        val url = UrlPolicy.normalizeUserInput(urlField.text.toString())
        stages = url?.let { EditorDiscovery(app, it.serialized) }
        discoveryUrl.text = stages?.resolved()?.launchUrl.orEmpty()
        showRetainedIcon()
        if (restored != null && url != null && app?.configuredUrl != url.serialized)
            discovery?.urlChanged(url.serialized)
        saveButton.isEnabled = true
    }

    /** Name follows the canonical host while it is empty or still host-derived (R§6.1). */
    private fun onUrlChanged() {
        if (programmatic) return
        val result = UrlPolicy.validateUserInput(urlField.text.toString())
        val canonical = (result as? UrlPolicy.UserInput.Valid)?.url?.serialized
        if (canonical != stages?.configuredUrl) {
            cancelDiscovery()
            stages = canonical?.let { EditorDiscovery(saved, it) }
            suggestion.visibility = View.GONE
            showRetainedIcon()
            discoveryUrl.text = canonical.orEmpty()
            if (canonical == null) discoveryStatus.text = ""
            else discoveryStatus.setText(R.string.editor_discovery_loading)
            discovery?.urlChanged(urlField.text.toString())
        }
        if (urlLayout.error != null) showUrlError()
        if (result !is UrlPolicy.UserInput.Valid || nameSource == NameSource.USER_EDITED) return
        val current = nameField.text.toString()
        if (current.isNotEmpty() && current != hostName) return
        val host = AppNames.normalize(result.url.host) ?: return
        setProgrammatically { nameField.setText(host) }
        hostName = host
        if (nameLayout.error != null) showNameError()
    }

    private fun urlError(): String? = when (val result = UrlPolicy.validateUserInput(urlField.text.toString())) {
        UrlPolicy.UserInput.Empty -> getString(R.string.editor_url_required)
        UrlPolicy.UserInput.HttpsRequired -> getString(R.string.editor_https_required)
        UrlPolicy.UserInput.TooLong -> getString(R.string.editor_url_too_long)
        UrlPolicy.UserInput.Invalid -> getString(R.string.editor_url_invalid)
        is UrlPolicy.UserInput.Valid -> when {
            !EditorDraft.changesOrigin(saved, result.url) -> null
            saved?.profileState == ProfileState.CLEARING -> getString(R.string.editor_origin_change_clearing)
            else -> null
        }
    }

    private fun nameError(): String? = when (AppNames.validate(nameField.text.toString())) {
        AppNames.Result.Empty -> getString(R.string.editor_name_required)
        AppNames.Result.TooLong -> getString(R.string.editor_name_too_long, AppNames.MAX_CODE_POINTS)
        AppNames.Result.InvalidCharacter -> getString(R.string.editor_name_invalid)
        is AppNames.Result.Valid -> null
    }

    private fun showUrlError(): Boolean {
        val error = urlError()
        urlLayout.error = error
        return error == null
    }

    private fun showNameError(): Boolean {
        val error = nameError()
        nameLayout.error = error
        return error == null
    }

    private fun confirmThirdPartyCookies() {
        var accepted = false
        AlertDialog.Builder(this)
            .setTitle(R.string.third_party_cookies_title)
            .setMessage(R.string.third_party_cookies_warning)
            .setPositiveButton(R.string.third_party_cookies_enable) { _, _ -> accepted = true }
            .setNegativeButton(R.string.editor_cancel, null)
            // Any dismissal other than Enable leaves the setting off.
            .setOnDismissListener {
                if (accepted && !isDestroyed) setProgrammatically { thirdPartyCookies.isChecked = true }
            }
            .show()
    }

    private fun save() {
        if (saving || maintaining || removing || !loaded ||
            (pendingOriginChange && saved?.profileState != ProfileState.CLEARING)) return
        val urlValid = showUrlError()
        val nameValid = showNameError()
        if (!urlValid || !nameValid) {
            (if (!urlValid) urlField else nameField).requestFocus()
            return
        }
        val url = (UrlPolicy.validateUserInput(urlField.text.toString()) as UrlPolicy.UserInput.Valid).url
        val name = AppNames.normalize(nameField.text.toString())!!
        val draft = EditorDraft(name, nameSource, url, thirdPartyCookies.isChecked)
        val now = System.currentTimeMillis()
        cancelDiscovery()
        discoveryStatus.setText(R.string.editor_discovery_stopped)
        val base = draft.record(saved, saved?.id ?: UUID.randomUUID().toString(), now)
        val exactStages = stages?.takeIf { it.configuredUrl == base.configuredUrl }
        val record = exactStages?.apply(base) ?: base
        val discoveredIcon = exactStages?.icon
        saving = true
        updateMaintenanceButtons()
        confirmSensitive(record, EditorConsent.pending(record)) { consented ->
            if (consented == null) {
                saving = false
                updateMaintenanceButtons()
                urlLayout.error = getString(R.string.editor_sensitive_declined)
                urlField.requestFocus()
            } else {
                val ready = EditorConsent.prune(consented)
                if (EditorDraft.changesOrigin(saved, url)) {
                    var accepted = false
                    AlertDialog.Builder(this)
                        .setMessage(R.string.editor_origin_change_confirmation)
                        .setPositiveButton(R.string.editor_save) { _, _ ->
                            accepted = true
                            persist(ready, discoveredIcon)
                        }
                        .setNegativeButton(R.string.editor_cancel, null)
                        .setOnDismissListener {
                            if (!accepted) { saving = false; updateMaintenanceButtons() }
                        }.show()
                } else persist(ready, discoveredIcon)
            }
        }
    }

    /** Asks for each pending URL in turn; [done] gets `null` when the typed URL is declined. */
    private fun confirmSensitive(record: ConfiguredApp, pending: List<String>,
                                 done: (ConfiguredApp?) -> Unit) {
        val url = pending.firstOrNull() ?: return done(record)
        SensitiveUrlWarning.show(this, CredentialParameters.scan(url), persisted = true) { accepted ->
            if (isDestroyed) return@show
            val next = EditorConsent.answer(record, url, accepted) ?: return@show done(null)
            confirmSensitive(next, EditorConsent.pending(next), done)
        }
    }

    private fun persist(record: ConfiguredApp, icon: SelectedIcon?) {
        val adding = saved == null
        lifecycleScope.launch {
            // Once Save starts, the write completes even if the activity is destroyed.
            val result = withContext(NonCancellable) {
                val stored = if (icon == null) record else withContext(Dispatchers.IO) {
                    record.copy(iconReference = runCatching { IconStore(this@EditorActivity)
                        .save(record.id, icon) }.getOrNull() ?: record.iconReference)
                }
                if (adding) repository.add(stored)
                else if (EditorDraft.changesOrigin(saved, UrlPolicy.parseHttps(stored.configuredUrl)!!))
                    ShellServices.shared(this@EditorActivity).profiles.changeOrigin(stored)
                else repository.update(stored)
            }
            if (isDestroyed) return@launch
            when (result) {
                ConfigurationWriteResult.Success -> finish()
                ConfigurationWriteResult.MissingApp -> {
                    Toast.makeText(this@EditorActivity, R.string.app_unavailable, Toast.LENGTH_LONG).show()
                    finish()
                }
                is ConfigurationWriteResult.PendingOriginChange -> {
                    pendingOriginChange = true
                    saving = false
                    maintenanceStatus.setText(R.string.editor_origin_change_pending)
                    updateMaintenanceButtons()
                }
                else -> {
                    saving = false
                    updateMaintenanceButtons()
                    Toast.makeText(this@EditorActivity, R.string.editor_save_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private inline fun setProgrammatically(block: () -> Unit) {
        programmatic = true
        try { block() } finally { programmatic = false }
    }

    private fun watcher(changed: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = changed()
    }

    companion object {
        const val EXTRA_APP_ID = "nu.staldal.mws.APP_ID"
        private const val STATE_NAME_SOURCE = "nameSource"
        private const val STATE_HOST_NAME = "hostName"
        private const val STATE_THIRD_PARTY = "allowThirdPartyCookies"

        fun addIntent(context: Context) = Intent(context, EditorActivity::class.java)

        fun editIntent(context: Context, appId: String) =
            Intent(context, EditorActivity::class.java).putExtra(EXTRA_APP_ID, appId)
    }
}
