package nu.staldal.mws

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** One collection instance has one backup-excluded selection record. */
object MultiWidget {
    private val updates = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal fun launch(block: suspend () -> Unit) { updates.launch {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Log.w("MultiWidget", "Widget update failed", failure) }
    } }
    private fun component(context: Context) = ComponentName(context, MultiWidgetProvider::class.java)

    fun installed(context: Context): Set<Int> =
        AppWidgetManager.getInstance(context).getAppWidgetIds(component(context)).toSet()

    fun reconcile(context: Context) {
        MaintenanceStore(context).use { it.pruneWidgets(installed(context), WidgetType.MULTI_APP) }
        refresh(context)
    }

    @Synchronized fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = installed(context)
        if (ids.isEmpty()) return
        for (id in ids) {
            val views = baseViews(context, id)
            val adapter = Intent(context, MultiWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(android.net.Uri.parse("mws-widget:$id"))
            views.setRemoteAdapter(R.id.widget_list, adapter)
            // Android collections merge each row's app ID into this explicit template.
            views.setPendingIntentTemplate(R.id.widget_list, PendingIntent.getActivity(
                context, id, Intent(context, ShellActivity::class.java)
                    .setAction(ShellActivity.ACTION_OPEN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT),
                PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag()))
            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        }
    }

    internal fun baseViews(context: Context, id: Int): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_multi).apply {
            setEmptyView(R.id.widget_list, R.id.widget_empty)
            setOnClickPendingIntent(R.id.widget_header, mainIntent(context, id))
            setOnClickPendingIntent(R.id.widget_empty, mainIntent(context, id))
        }

    internal fun select(apps: List<ConfiguredApp>, record: WidgetRecord?): List<ConfiguredApp> =
        if (record?.widgetType == WidgetType.MULTI_APP &&
            record.selectionMode == SelectionMode.SELECTED_SUBSET) {
            apps.filter { it.id in record.selectedAppIds }
        } else apps

    internal fun rowIntent(app: ConfiguredApp): Intent = if (app.profileState == ProfileState.ACTIVE)
        Intent().setData(Uri.parse("${ShellActivity.SCHEME}:${app.id}")) else Intent()

    internal fun rowViews(context: Context, app: ConfiguredApp): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_row).apply {
            setTextViewText(R.id.widget_row_name, app.name)
            val clearing = app.profileState == ProfileState.CLEARING
            setViewVisibility(R.id.widget_row_state, if (clearing) View.VISIBLE else View.GONE)
            setBoolean(R.id.widget_row, "setEnabled", !clearing)
            setTextColor(R.id.widget_row_name,
                if (clearing) 0xff5f6368.toInt() else 0xff202124.toInt())
            val bitmap = IconStore(context).load(app.iconReference, 48)
            if (bitmap != null) setImageViewBitmap(R.id.widget_row_icon, bitmap)
            else setImageViewResource(R.id.widget_row_icon, R.drawable.ic_launcher)
            setOnClickFillInIntent(R.id.widget_row, rowIntent(app))
        }

    private fun mainIntent(context: Context, id: Int): PendingIntent = PendingIntent.getActivity(
        context, id, Intent(context, MainActivity::class.java).setAction(ACTION_MAIN)
            .setData(android.net.Uri.parse("mws-widget-main:$id")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun mutableFlag() = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
    private const val ACTION_MAIN = "nu.staldal.mws.action.WIDGET_MAIN"
}

class MultiWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val result = goAsync()
        MultiWidget.launch { try { MultiWidget.refresh(context) } finally { result.finish() } }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager,
        appWidgetId: Int, newOptions: Bundle) {
        val result = goAsync()
        MultiWidget.launch { try { MultiWidget.refresh(context) } finally { result.finish() } }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        MaintenanceStore(context).use { store -> ids.forEach(store::deleteWidget) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val result = goAsync()
            MultiWidget.launch { try { MultiWidget.reconcile(context) } finally { result.finish() } }
        }
    }
}

class MultiWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(
        applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID))

    internal class Factory(private val context: Context, private val widgetId: Int) : RemoteViewsFactory {
        private var apps: List<ConfiguredApp> = emptyList()

        override fun onCreate() = onDataSetChanged()
        override fun onDataSetChanged() {
            if (widgetId !in MultiWidget.installed(context)) { apps = emptyList(); return }
            val ordered = try { runBlocking {
                ShellServices.shared(context).profiles.prepareConfiguration()
                ConfigurationRepository(ConfigurationDatabase.shared(context)).ordered()
            }
            } catch (_: Exception) { apps = emptyList(); return }
            MaintenanceStore(context).use { store -> apps = MultiWidget.select(ordered, store.widget(widgetId)) }
        }
        override fun onDestroy() { apps = emptyList() }
        override fun getCount() = apps.size
        override fun getViewAt(position: Int): RemoteViews? {
            val app = apps.getOrNull(position) ?: return null
            return MultiWidget.rowViews(context, app)
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds() = false
    }
}

class MultiWidgetConfigureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(Activity.RESULT_CANCELED)
        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_CONFIGURE ||
            id == AppWidgetManager.INVALID_APPWIDGET_ID || id !in MultiWidget.installed(this)) {
            finish(); return
        }
        lifecycleScope.launch {
            try { ShellServices.shared(this@MultiWidgetConfigureActivity).profiles.prepareConfiguration() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { finish(); return@launch }
            val apps = withContext(Dispatchers.IO) {
                ConfigurationRepository(ConfigurationDatabase.shared(this@MultiWidgetConfigureActivity)).ordered()
            }
            val prior = withContext(Dispatchers.IO) {
                MaintenanceStore(this@MultiWidgetConfigureActivity).use { it.widget(id) }
            }
            val root = LinearLayout(this@MultiWidgetConfigureActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val spacing = (16 * resources.displayMetrics.density).toInt()
            root.setPadding(spacing, spacing, spacing, spacing)
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout())
                view.setPadding(spacing + bars.left, spacing + bars.top,
                    spacing + bars.right, spacing + bars.bottom)
                insets
            }
            root.addView(TextView(this@MultiWidgetConfigureActivity).apply {
                setText(R.string.widget_configure); textSize = 20f
            })
            val modes = RadioGroup(this@MultiWidgetConfigureActivity)
            val all = RadioButton(this@MultiWidgetConfigureActivity).apply {
                setText(R.string.widget_all_apps); this.id = View.generateViewId()
            }
            val subset = RadioButton(this@MultiWidgetConfigureActivity).apply {
                setText(R.string.widget_selected_apps); this.id = View.generateViewId()
            }
            modes.addView(all); modes.addView(subset); root.addView(modes)
            val choices = LinearLayout(this@MultiWidgetConfigureActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val boxes = apps.map { app ->
                app.id to CheckBox(this@MultiWidgetConfigureActivity).apply {
                    text = if (app.profileState == ProfileState.CLEARING)
                        "${app.name} — ${getString(R.string.clearing_label)}" else app.name
                    isEnabled = app.profileState == ProfileState.ACTIVE
                    isChecked = isEnabled && app.id in (prior?.selectedAppIds ?: emptySet())
                    choices.addView(this)
                }
            }
            modes.setOnCheckedChangeListener { _, checked ->
                choices.visibility = if (checked == subset.id) View.VISIBLE else View.GONE
            }
            modes.check(if (prior?.selectionMode == SelectionMode.SELECTED_SUBSET) subset.id else all.id)
            root.addView(ScrollView(this@MultiWidgetConfigureActivity).apply { addView(choices) },
                LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(Button(this@MultiWidgetConfigureActivity).apply {
                setText(R.string.widget_save)
                setOnClickListener {
                    val selected = if (modes.checkedRadioButtonId == subset.id)
                        boxes.filter { it.second.isChecked && it.second.isEnabled }.map { it.first }.toSet()
                    else emptySet()
                    val mode = if (modes.checkedRadioButtonId == subset.id)
                        SelectionMode.SELECTED_SUBSET else SelectionMode.ALL_APPS
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                MaintenanceStore(this@MultiWidgetConfigureActivity).use { store ->
                                    store.putWidget(WidgetRecord(id, WidgetType.MULTI_APP, mode, selected))
                                }
                                MultiWidget.refresh(this@MultiWidgetConfigureActivity)
                            }
                            setResult(Activity.RESULT_OK, Intent().putExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                            finish()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Toast.makeText(this@MultiWidgetConfigureActivity,
                                R.string.widget_save_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            })
            setContentView(root)
            ViewCompat.requestApplyInsets(root)
        }
    }
}
