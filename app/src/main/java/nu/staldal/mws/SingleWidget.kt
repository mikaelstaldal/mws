package nu.staldal.mws

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*

object SingleWidget {
    fun installed(context: Context): Set<Int> = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, SingleWidgetProvider::class.java)).toSet()

    fun reconcile(context: Context) {
        MaintenanceStore(context).use { it.pruneWidgets(installed(context), WidgetType.SINGLE_APP) }
        refresh(context)
    }

    @Synchronized fun refresh(context: Context) {
        val ids = installed(context)
        if (ids.isEmpty()) return
        val apps = runBlocking { ConfigurationRepository(ConfigurationDatabase.shared(context)).ordered() }
        MaintenanceStore(context).use { store ->
            ids.forEach { id ->
                try {
                    AppWidgetManager.getInstance(context).updateAppWidget(id,
                        views(context, id, target(apps, store.widget(id))))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { android.util.Log.w("SingleWidget", "Widget update failed", failure) }
            }
        }
    }

    internal fun target(apps: List<ConfiguredApp>, record: WidgetRecord?): ConfiguredApp? =
        if (record?.widgetType == WidgetType.SINGLE_APP)
            apps.find { it.id == record.targetAppId } else null

    internal fun clickIntent(context: Context, id: Int, app: ConfiguredApp?): Intent =
        if (app?.profileState == ProfileState.ACTIVE) ShellActivity.intent(context, app.id)
        else Intent(context, MainActivity::class.java)
            .setData(Uri.parse("mws-single-main:$id"))

    internal fun views(context: Context, id: Int, app: ConfiguredApp?): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_single).apply {
            val label = when {
                app == null -> context.getString(R.string.widget_unavailable)
                app.profileState == ProfileState.CLEARING -> context.getString(R.string.widget_maintenance)
                else -> app.name
            }
            setTextViewText(R.id.widget_single_name, label)
            val bitmap = app?.let { IconStore(context).load(it.iconReference, 72) }
            if (bitmap != null) setImageViewBitmap(R.id.widget_single_icon, bitmap)
            else setImageViewResource(R.id.widget_single_icon, R.drawable.ic_launcher)
            setOnClickPendingIntent(R.id.widget_single, PendingIntent.getActivity(context, id,
                clickIntent(context, id, app), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
}

class SingleWidgetProvider : AppWidgetProvider() {
    private fun update(context: Context, reconcile: Boolean = false) {
        val result = goAsync()
        MultiWidget.launch {
            try {
                if (reconcile) SingleWidget.reconcile(context) else SingleWidget.refresh(context)
            } finally { result.finish() }
        }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager,
        appWidgetId: Int, newOptions: Bundle) = update(context)
    override fun onDeleted(context: Context, ids: IntArray) {
        MaintenanceStore(context).use { store -> ids.forEach(store::deleteWidget) }
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            update(context, true)
    }
}

class SingleWidgetConfigureActivity : AppCompatActivity() {
    private var selectedAppId: String? = null

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selectedAppId", selectedAppId)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_CONFIGURE || id !in SingleWidget.installed(this)) {
            finish(); return
        }
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                ConfigurationRepository(ConfigurationDatabase.shared(this@SingleWidgetConfigureActivity)).ordered()
            }
            val prior = savedInstanceState?.getString("selectedAppId") ?: withContext(Dispatchers.IO) {
                MaintenanceStore(this@SingleWidgetConfigureActivity).use { it.widget(id)?.targetAppId }
            }
            val root = LinearLayout(this@SingleWidgetConfigureActivity).apply { orientation = LinearLayout.VERTICAL }
            val spacing = (16 * resources.displayMetrics.density).toInt()
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(spacing + bars.left, spacing + bars.top, spacing + bars.right, spacing + bars.bottom)
                insets
            }
            root.addView(TextView(this@SingleWidgetConfigureActivity).apply { setText(R.string.widget_pick_app); textSize = 20f })
            val choices = RadioGroup(this@SingleWidgetConfigureActivity)
            val buttons = apps.map { app ->
                app.id to RadioButton(this@SingleWidgetConfigureActivity).apply {
                    this.id = android.view.View.generateViewId()
                    text = if (app.profileState == ProfileState.CLEARING)
                        "${app.name} — ${getString(R.string.widget_maintenance)}" else app.name
                    isEnabled = app.profileState == ProfileState.ACTIVE
                    choices.addView(this)
                    if (isEnabled && app.id == prior) choices.check(this.id)
                }
            }
            root.addView(ScrollView(this@SingleWidgetConfigureActivity).apply { addView(choices) }, LinearLayout.LayoutParams(-1, 0, 1f))
            val save = Button(this@SingleWidgetConfigureActivity).apply {
                setText(R.string.widget_save)
                isEnabled = choices.checkedRadioButtonId != -1
                setOnClickListener {
                    val selected = buttons.firstOrNull { it.second.id == choices.checkedRadioButtonId }?.first
                        ?: return@setOnClickListener
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                check(id in SingleWidget.installed(this@SingleWidgetConfigureActivity))
                                val current = ConfigurationRepository(ConfigurationDatabase.shared(this@SingleWidgetConfigureActivity)).ordered()
                                check(current.any { it.id == selected && it.profileState == ProfileState.ACTIVE })
                                MaintenanceStore(this@SingleWidgetConfigureActivity).use {
                                    it.putWidget(WidgetRecord(id, WidgetType.SINGLE_APP, targetAppId = selected))
                                }
                                SingleWidget.refresh(this@SingleWidgetConfigureActivity)
                            }
                            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                            finish()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { Toast.makeText(this@SingleWidgetConfigureActivity, R.string.widget_save_failed, Toast.LENGTH_LONG).show() }
                    }
                }
            }
            fun selectionChanged() {
                selectedAppId = buttons.firstOrNull { it.second.id == choices.checkedRadioButtonId && it.second.isEnabled }?.first
                save.isEnabled = selectedAppId != null
            }
            choices.setOnCheckedChangeListener { _, _ -> selectionChanged() }
            selectionChanged()
            root.addView(save)
            setContentView(root)
            ViewCompat.requestApplyInsets(root)
        }
    }
}
