package nu.staldal.mws

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
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The list observes the canonical order; configuration changes appear without restarting. */
class MainActivity : AppCompatActivity() {
    private lateinit var myPassClient: MyPassClient
    private lateinit var database: ConfigurationDatabase
    private lateinit var repository: ConfigurationRepository
    private lateinit var compatibility: ProviderCompatibility
    private lateinit var rows: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var listError: TextView
    private lateinit var compatibilityEntry: View
    private lateinit var iconStore: IconStore
    private var observation: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        myPassClient = MyPassClient(this)
        myPassClient.restoreIdentity(savedInstanceState)
        database = ConfigurationDatabase.shared(this)
        repository = ConfigurationRepository(database)
        iconStore = IconStore(this)
        compatibility = ProviderCompatibility(
            AndroidProviderSnapshotSource(this), ProviderSelfTestStore { null },
            debugOverride = BuildConfig.DEBUG_PROVIDER_OVERRIDE,
        )
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        compatibilityEntry.visibility = if (compatibility.current().compatible) View.GONE else View.VISIBLE
        observation = lifecycleScope.launch {
            repository.apps.catch { listError.visibility = View.VISIBLE }
                .collect { apps ->
                    listError.visibility = View.GONE
                    render(apps)
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
        myPassClient.saveIdentity(outState)
        super.onSaveInstanceState(outState)
    }

    private fun buildScreen() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = FrameLayout(this)
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
            textSize = 24f
            setPadding(0, 0, 0, dp(12))
        }
        column.addView(heading)
        compatibilityEntry = TextView(this).apply {
            id = R.id.provider_error_entry
            setText(R.string.provider_error_entry)
            contentDescription = getString(R.string.provider_error_entry)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, ProviderErrorActivity::class.java)) }
        }
        column.addView(compatibilityEntry)
        listError = TextView(this).apply {
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
                setOnClickListener { openPlaceholder(PlaceholderActivity.ACTION_ADD, null) }
            })
        }
        column.addView(emptyState, LinearLayout.LayoutParams(-1, 0, 1f))
        val add = ExtendedFloatingActionButton(this).apply {
            id = R.id.add_web_app
            setText(R.string.add_web_app)
            setIconResource(android.R.drawable.ic_input_add)
            contentDescription = getString(R.string.add_web_app)
            setOnClickListener { openPlaceholder(PlaceholderActivity.ACTION_ADD, null) }
        }
        root.addView(add, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(16), dp(16), dp(16), dp(16))
        })
        setContentView(root)
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
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (clearing) Toast.makeText(this@MainActivity,
                    R.string.clearing_explanation, Toast.LENGTH_LONG).show()
                else if (!compatibility.current().compatible) {
                    startActivity(Intent(this@MainActivity, ProviderErrorActivity::class.java))
                } else openPlaceholder(PlaceholderActivity.ACTION_SHELL, app.id)
            }
            setOnLongClickListener {
                openPlaceholder(PlaceholderActivity.ACTION_EDIT, app.id)
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
            maxLines = 1
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
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        row.contentDescription = if (clearing) getString(R.string.clearing_row_description, app.name)
            else getString(R.string.app_row_description, app.name, app.launchUrl)
        if (clearing) ViewCompat.setStateDescription(row, getString(R.string.clearing_state))
        return row
    }

    private fun openPlaceholder(action: String, id: String?) {
        startActivity(Intent(this, PlaceholderActivity::class.java).apply {
            this.action = action
            if (id != null) putExtra(PlaceholderActivity.EXTRA_APP_ID, id)
        })
    }
}
