package nu.staldal.mws

import android.content.ClipData
import android.view.DragEvent
import android.view.MotionEvent
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The list observes the canonical order; configuration changes appear without restarting. */
class MainActivity : AppCompatActivity() {
    private lateinit var myPassClient: MyPassClient
    private lateinit var repository: ConfigurationRepository
    private lateinit var compatibility: ProviderCompatibility
    private lateinit var rows: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var listError: TextView
    private lateinit var compatibilityEntry: View
    private lateinit var progress: TextView
    private lateinit var iconStore: IconStore
    private var observation: Job? = null
    private var currentApps: List<ConfiguredApp> = emptyList()
    private var draft: MutableList<String>? = null
    private var saving = false
    private val reorderBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { cancelOrder() }
    }
    private lateinit var reorder: View
    private lateinit var done: View
    private lateinit var cancel: View
    private lateinit var addButton: View


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        myPassClient = MyPassClient(this)
        myPassClient.restoreIdentity(savedInstanceState)
        val services = ShellServices.shared(this)
        repository = services.repository
        iconStore = services.icons
        compatibility = services.compatibility
        onBackPressedDispatcher.addCallback(this, reorderBack)
        draft = savedInstanceState?.getStringArrayList("reorderDraft")?.toMutableList()
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        compatibilityEntry.visibility = View.GONE
        lifecycleScope.launch {
            progress.setText(R.string.provider_self_test_running)
            progress.visibility = View.VISIBLE
            var failed = false
            try { ShellServices.shared(this@MainActivity).awaitStartup() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            progress.visibility = if (failed) View.VISIBLE else View.GONE
            if (failed) progress.setText(R.string.provider_check_failed)
            compatibilityEntry.visibility = if (compatibility.current().compatible) View.GONE else View.VISIBLE
        }
        observation = lifecycleScope.launch {
            try { ShellServices.shared(this@MainActivity).awaitStartup() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                listError.visibility = View.VISIBLE
                return@launch
            }
            repository.apps.catch { listError.visibility = View.VISIBLE }
                .collect { apps ->
                    listError.visibility = View.GONE
                    draft?.let { order ->
                        val ids = apps.map { it.id }
                        order.retainAll(ids.toSet())
                        order.addAll(ids.filter { it !in order })
                    }
                    currentApps = apps
                    if (draft != null) rows.removeAllViews()
                    renderDisplayed()

                }
        }
    }

    override fun onPause() {
        observation?.cancel()
        observation = null
        super.onPause()
    }

    override fun onDestroy() {
        myPassClient.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        draft?.let { outState.putStringArrayList("reorderDraft", ArrayList(it)) }
        myPassClient.saveIdentity(outState)
        super.onSaveInstanceState(outState)
    }

    private fun buildScreen() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = FrameLayout(this)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(88))
        }
        val scroll = ScrollView(this).apply {
            id = R.id.app_list_scroll
            isFillViewport = true
            addView(column)
        }
        root.addView(scroll)
        val heading = TextView(this).apply {
            setText(R.string.app_name)
            ViewCompat.setAccessibilityHeading(this, true)
            textSize = 24f
            setPadding(0, 0, 0, dp(12))
        }
        column.addView(heading)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun button(viewId: Int, label: Int, action: () -> Unit): View =
            com.google.android.material.button.MaterialButton(this).apply {
                id = viewId
                setText(label)
                setOnClickListener { action() }
                controls.addView(this)
            }
        reorder = button(R.id.reorder_apps, R.string.reorder_apps) {
            draft = currentApps.map { it.id }.toMutableList()
            rows.removeAllViews()
            renderDisplayed()
        }
        done = button(R.id.reorder_done, R.string.reorder_done) { saveOrder() }
        cancel = button(R.id.reorder_cancel, R.string.reorder_cancel) { cancelOrder() }
        column.addView(controls)

        compatibilityEntry = com.google.android.material.button.MaterialButton(this).apply {
            minimumHeight = dp(48)
            id = R.id.provider_error_entry
            setText(R.string.provider_error_entry)
            contentDescription = getString(R.string.provider_error_entry)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, ProviderErrorActivity::class.java)) }
        }
        column.addView(compatibilityEntry)
        progress = TextView(this).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setText(R.string.provider_self_test_running)
            visibility = View.GONE
        }
        column.addView(progress)
        listError = TextView(this).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            id = R.id.list_error
            setText(R.string.list_error)
            visibility = View.GONE
        }
        column.addView(listError)
        rows = LinearLayout(this).apply {
            id = R.id.app_rows
            orientation = LinearLayout.VERTICAL
        }
        column.addView(rows)
        emptyState = LinearLayout(this).apply {
            id = R.id.empty_state
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(TextView(this@MainActivity).apply {
                setText(R.string.empty_state)
                textSize = 18f
            })
            addView(com.google.android.material.button.MaterialButton(this@MainActivity).apply {
                id = R.id.empty_add
                setText(R.string.add_web_app)
                setOnClickListener { startActivity(EditorActivity.addIntent(this@MainActivity)) }
            })
        }
        column.addView(emptyState, LinearLayout.LayoutParams(-1, 0, 1f))
        val add = ExtendedFloatingActionButton(this).apply {
            id = R.id.add_web_app
            setText(R.string.add_web_app)
            setIconResource(android.R.drawable.ic_input_add)
            contentDescription = getString(R.string.add_web_app)
            setOnClickListener { startActivity(EditorActivity.addIntent(this@MainActivity)) }
        }
        addButton = add
        root.addView(add, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(16), dp(16), dp(16), dp(16))
        })
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        renderDisplayed()
    }

    private fun renderDisplayed() {
        val editing = draft != null
        reorderBack.isEnabled = editing
        reorder.visibility = if (editing || currentApps.isEmpty()) View.GONE else View.VISIBLE
        done.visibility = if (editing) View.VISIBLE else View.GONE
        cancel.visibility = done.visibility
        done.isEnabled = !saving
        cancel.isEnabled = !saving
        addButton.visibility = if (editing) View.GONE else View.VISIBLE
        val byId = currentApps.associateBy { it.id }
        val displayed = draft?.mapNotNull { byId[it] } ?: currentApps
        render(displayed)
    }

    private fun cancelOrder() {
        if (saving) return
        draft = null
        rows.removeAllViews()
        renderDisplayed()
    }

    private fun saveOrder() {
        val ids = draft?.toList() ?: return
        if (saving) return
        saving = true
        renderDisplayed()
        lifecycleScope.launch {
            // Once Done starts, commit and its widget notifications survive activity destruction.
            val result = withContext(NonCancellable) {
                repository.reorder(ids).also {
                    if (it == ConfigurationWriteResult.Success) {
                        MultiWidget.refresh(applicationContext)
                        SingleWidget.refresh(applicationContext)
                    }
                }
            }
            if (isDestroyed) return@launch
            saving = false
            if (result == ConfigurationWriteResult.Success) {
                currentApps = repository.ordered()
                draft = null
            } else {
                Toast.makeText(this@MainActivity, R.string.reorder_failed, Toast.LENGTH_LONG).show()
            }
            rows.removeAllViews()
            renderDisplayed()
        }
    }

    internal fun moveDraft(id: String, target: Int): Boolean {
        val order = draft ?: return false
        val from = order.indexOf(id)
        if (saving || from < 0 || target !in order.indices || from == target) return false
        order.add(target, order.removeAt(from))
        rows.removeAllViews()
        renderDisplayed()
        rows.findViewWithTag<View>(id)?.let { moved ->
            moved.requestFocus()
            moved.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            moved.announceForAccessibility(getString(R.string.reorder_position, target + 1, order.size))
        }
        return true
    }

    private fun render(apps: List<ConfiguredApp>) {
        emptyState.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        val existing = (0 until rows.childCount).associate { index ->
            val child = rows.getChildAt(index)
            child.tag as String to child
        }
        apps.forEachIndexed { index, app ->
            val old = existing[app.id]
            val view = if (old?.getTag(R.id.row_snapshot) == app) old else row(app)
            if (rows.getChildAt(index) !== view) {
                if (view.parent === rows) rows.removeView(view)
                rows.addView(view, index)
            }
        }
        while (rows.childCount > apps.size) rows.removeViewAt(rows.childCount - 1)
    }

    private fun row(app: ConfiguredApp): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val clearing = app.profileState == ProfileState.CLEARING
        val row = LinearLayout(this).apply {
            tag = app.id
            setTag(R.id.row_snapshot, app)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            isClickable = draft == null
            isFocusable = true
            setOnClickListener {
                if (draft != null) return@setOnClickListener
                if (clearing) Toast.makeText(this@MainActivity,
                    R.string.clearing_explanation, Toast.LENGTH_LONG).show()
                else if (!compatibility.current().compatible) {
                    startActivity(Intent(this@MainActivity, ProviderErrorActivity::class.java))
                } else startActivity(ShellActivity.intent(this@MainActivity, app.id))
            }
            setOnLongClickListener {
                if (draft != null) return@setOnLongClickListener true
                startActivity(EditorActivity.editIntent(this@MainActivity, app.id))
                true
            }
        }
        val icon = ImageView(this).apply {
            id = R.id.app_icon
            setImageDrawable(iconStore.defaultIcon())
            contentDescription = getString(R.string.app_icon_description, app.name)
        }
        if (app.iconReference != null) row.post {
            lifecycleScope.launch {
                val size = IconPipeline.SIZES.firstOrNull { it >= dp(48) }
                    ?: IconPipeline.SIZES.last()
                val bitmap = withContext(Dispatchers.IO) { iconStore.load(app.iconReference, size) }
                if (row.parent === rows && bitmap != null) icon.setImageBitmap(bitmap)
            }
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(16) })
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(this).apply {
            id = R.id.app_name_text
            this.text = app.name
            textSize = 17f
            maxLines = Int.MAX_VALUE
        })
        text.addView(TextView(this).apply {
            id = R.id.app_url_text
            this.text = app.launchUrl
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        if (clearing) text.addView(TextView(this).apply {
            id = R.id.clearing_label
            setText(R.string.clearing_label)
        })
        text.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        row.contentDescription = if (clearing) getString(R.string.clearing_row_description, app.name)
            else getString(R.string.app_row_description, app.name, app.launchUrl)
        if (draft != null) {
            row.contentDescription = getString(R.string.reorder_row, app.name)
            val handle = TextView(this).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                id = R.id.drag_handle
                setText(R.string.drag_handle_symbol)
                textSize = 24f
                gravity = Gravity.CENTER
                contentDescription = getString(R.string.drag_app, app.name)
                setOnTouchListener { view, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && !saving) {
                        view.startDragAndDrop(ClipData.newPlainText("app", app.id),
                            View.DragShadowBuilder(row), app.id, 0)
                        true
                    } else false
                }
            }
            row.addView(handle, LinearLayout.LayoutParams(dp(48), dp(48)))
            row.setOnDragListener { _, event ->
                val id = event.localState as? String
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> id != null && draft?.contains(id) == true
                    DragEvent.ACTION_DROP -> id != null && !saving &&
                        (id == app.id || moveDraft(id, draft?.indexOf(app.id) ?: -1))
                    DragEvent.ACTION_DRAG_LOCATION -> {
                        val scroll = findViewById<ScrollView>(R.id.app_list_scroll)
                        val location = IntArray(2)
                        row.getLocationOnScreen(location)
                        val pointerY = location[1] + event.y
                        scroll.getLocationOnScreen(location)
                        val margin = dp(64)
                        if (pointerY < location[1] + margin) scroll.smoothScrollBy(0, -dp(32))
                        else if (pointerY > location[1] + scroll.height - margin) scroll.smoothScrollBy(0, dp(32))
                        true
                    }
                    DragEvent.ACTION_DRAG_ENTERED,
                    DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> true
                    else -> false
                }
            }
            val position = draft!!.indexOf(app.id)
            if (position > 0) ViewCompat.addAccessibilityAction(row, getString(R.string.move_up)) { _, _ ->
                moveDraft(app.id, (draft?.indexOf(app.id) ?: 0) - 1)
            }
            if (position < draft!!.lastIndex) ViewCompat.addAccessibilityAction(row, getString(R.string.move_down)) { _, _ ->
                moveDraft(app.id, (draft?.indexOf(app.id) ?: -1) + 1)
            }
        }
        if (clearing) ViewCompat.setStateDescription(row, getString(R.string.clearing_state))
        return row
    }
}
