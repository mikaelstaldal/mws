package nu.staldal.mws

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
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
    private lateinit var saveButton: View

    private val appId: String? get() = intent.getStringExtra(EXTRA_APP_ID)
    /** The persisted record as last observed; `null` for a new app or before loading. */
    private var saved: ConfiguredApp? = null
    private var loaded = false
    private var saving = false
    private var nameSource = NameSource.DISCOVERED
    /** The host last copied into Name; it keeps following the URL until the user edits Name. */
    private var hostName: String? = null
    private var programmatic = false
    private var restoredState: Bundle? = null

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
        load()
    }

    override fun onResume() {
        super.onResume()
        myPassStatus.refresh(myPassClient)
    }

    override fun onDestroy() {
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
            textSize = 24f
            setPadding(0, 0, 0, dp(12))
        })
        loadError = TextView(this).apply {
            id = R.id.editor_load_error
            setText(R.string.editor_load_failed)
            visibility = View.GONE
        }
        column.addView(loadError)
        clearingNotice = TextView(this).apply {
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
            orientation = LinearLayout.HORIZONTAL
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
        buttons.addView(saveButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        column.addView(buttons)
        setContentView(ScrollView(this).apply {
            id = R.id.editor_scroll
            addView(column)
        })
    }

    /** Registered after view-state restoration, so restored text is not taken as user input. */
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        urlField.addTextChangedListener(watcher { onUrlChanged() })
        urlField.setOnFocusChangeListener { _, focused -> if (!focused) showUrlError() }
        nameField.addTextChangedListener(watcher {
            if (!programmatic) {
                nameSource = NameSource.USER_EDITED
                hostName = null
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
            repository.observe(id).collect { app ->
                if (app == null) {
                    if (!saving) {
                        Toast.makeText(this@EditorActivity, R.string.app_unavailable, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@collect
                }
                saved = app
                if (!loaded) populate(app)
                clearingNotice.visibility =
                    if (app.profileState == ProfileState.CLEARING) View.VISIBLE else View.GONE
                if (urlLayout.error != null) showUrlError()
            }
        }
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
        saveButton.isEnabled = true
    }

    /** Name follows the canonical host while it is empty or still host-derived (R§6.1). */
    private fun onUrlChanged() {
        val result = UrlPolicy.validateUserInput(urlField.text.toString())
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
            else -> getString(R.string.editor_origin_change_unavailable)
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
        if (saving || !loaded) return
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
        val record = draft.record(saved, saved?.id ?: UUID.randomUUID().toString(), now)
        saving = true
        saveButton.isEnabled = false
        confirmSensitive(record, EditorConsent.pending(record)) { consented ->
            if (consented == null) {
                saving = false
                saveButton.isEnabled = true
                urlLayout.error = getString(R.string.editor_sensitive_declined)
                urlField.requestFocus()
            } else persist(EditorConsent.prune(consented))
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

    private fun persist(record: ConfiguredApp) {
        val adding = saved == null
        lifecycleScope.launch {
            // Once Save starts, the write completes even if the activity is destroyed.
            val result = withContext(NonCancellable) {
                if (adding) repository.add(record) else repository.update(record)
            }
            if (isDestroyed) return@launch
            when (result) {
                ConfigurationWriteResult.Success -> finish()
                ConfigurationWriteResult.MissingApp -> {
                    Toast.makeText(this@EditorActivity, R.string.app_unavailable, Toast.LENGTH_LONG).show()
                    finish()
                }
                else -> {
                    saving = false
                    saveButton.isEnabled = true
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
