package nu.staldal.mws

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/** All requests are serialized, including different origins, to avoid overlapping native dialogs. */
internal class WebPermissions(
    private val activity: ShellActivity,
    savedState: android.os.Bundle? = null,
    private val app: () -> ConfiguredApp?,
) : WebChromeClient() {
    private val store = MaintenanceStore(activity)
    private var disposed = false
    private data class Pending(val token: Any, val run: (() -> Unit) -> Unit, val deny: () -> Unit)
    private val queue = ArrayDeque<Pending>()
    private var active: Pending? = null
    private var dialog: AlertDialog? = null
    private var runtimeCallback: ((Boolean) -> Unit)? = null
    private var pickerCallback: ValueCallback<Array<Uri>>? = null
    private var pickerOutstanding = savedState?.getBoolean("web_picker_outstanding") == true
    private var runtimeOutstanding = savedState?.getBoolean("web_runtime_outstanding") == true
    private val asked = activity.getSharedPreferences("android_permission_denials", 0)
    private val runtime = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!runtimeOutstanding) return@registerForActivityResult
        runtimeOutstanding = false
        val callback = runtimeCallback
        runtimeCallback = null
        it.forEach { (permission, granted) ->
            asked.edit().putBoolean(permission, !granted &&
                !activity.shouldShowRequestPermissionRationale(permission)).apply()
        }
        callback?.invoke(it.values.any { granted -> granted })
        pump()
    }
    private val picker = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!pickerOutstanding) return@registerForActivityResult
        pickerOutstanding = false
        val callback = pickerCallback
        pickerCallback = null
        // Use only document-provider URIs, never arbitrary file/intent/data URLs.
        val uris = if (it.resultCode == android.app.Activity.RESULT_OK) {
            val data = it.data
            val selected = data?.clipData?.let { clip ->
                (0 until clip.itemCount).map { index -> clip.getItemAt(index).uri }
            } ?: listOfNotNull(data?.data)
            selected.takeIf { values -> values.isNotEmpty() && values.all { uri -> uri.scheme == "content" } }
                ?.toTypedArray()
        } else null
        callback?.onReceiveValue(uris)
        if (callback != null) finishActive() else pump()
    }

    private fun enqueue(token: Any, deny: () -> Unit, run: (() -> Unit) -> Unit) {
        if (disposed || app() == null || activity.isFinishing || activity.isDestroyed) { deny(); return }
        queue.addLast(Pending(token, run, deny))
        pump()
    }

    private fun pump() {
        if (active != null || runtimeOutstanding || pickerOutstanding || queue.isEmpty()) return
        active = queue.removeFirst()
        active?.run?.invoke(::finishActive)
    }

    private fun finishActive() { active = null; pump() }

    private fun confirm(origin: String, capability: Int, canceled: (() -> Unit)? = null, answer: (Boolean) -> Unit) {
        dialog = AlertDialog.Builder(activity).setTitle(R.string.web_permission_title)
            .setMessage(if (capability == R.string.web_permission_files)
                activity.getString(R.string.web_files_question, origin)
                else activity.getString(R.string.web_permission_question, origin, activity.getString(capability)))
            .setPositiveButton(R.string.web_permission_allow) { _, _ -> dialog = null; answer(true) }
            .setNegativeButton(R.string.web_permission_deny) { _, _ -> dialog = null; answer(false) }
            .setOnCancelListener { dialog = null; if (canceled != null) canceled() else answer(false) }.show()
    }

    private fun androidPermission(permissions: Array<String>, answer: (Boolean) -> Unit) {
        if (permissions.any { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
            permissions.forEach { asked.edit().remove(it).apply() }
            answer(true); return
        }
        if (permissions.all { asked.getBoolean(it, false) && !activity.shouldShowRequestPermissionRationale(it) }) {
            dialog = AlertDialog.Builder(activity).setTitle(R.string.web_permission_android_denied)
                .setMessage(R.string.web_permission_settings_explanation)
                .setPositiveButton(R.string.web_permission_settings) { _, _ ->
                    dialog = null
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${activity.packageName}")))
                    answer(false)
                }.setNegativeButton(R.string.shell_close) { _, _ -> dialog = null; answer(false) }
                .setOnCancelListener { dialog = null; answer(false) }.show()
            return
        }
        runtimeCallback = answer
        runtimeOutstanding = true
        try { runtime.launch(permissions) } catch (_: RuntimeException) {
            runtimeOutstanding = false; runtimeCallback = null; answer(false)
        }
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        val canonical = UrlPolicy.parseHttps(origin)?.origin
        val record = app()
        if (canonical == null || record == null) { callback.invoke(origin, false, false); return }
        enqueue(callback, { callback.invoke(origin, false, false) }) { done ->
            fun result(allow: Boolean) { callback.invoke(origin, allow, false); done() }
            fun site(allow: Boolean) {
                if (!allow) result(false) else androidPermission(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), ::result)
            }
            when (store.permissionDecision(record.id, record.profileGeneration, canonical,
                PermissionCapability.GEOLOCATION)?.decision) {
                SiteDecision.SITE_ALLOW -> site(true)
                SiteDecision.SITE_DENY -> site(false)
                null -> confirm(canonical, R.string.web_permission_location, canceled = { result(false) }) { allow ->
                    store.putPermissionDecision(PermissionDecision(record.id, record.profileGeneration,
                        canonical, PermissionCapability.GEOLOCATION,
                        if (allow) SiteDecision.SITE_ALLOW else SiteDecision.SITE_DENY))
                    site(allow)
                }
            }
        }
    }

    override fun onGeolocationPermissionsHidePrompt() {
        cancel { it is GeolocationPermissions.Callback }
    }

    /** WebView canceled the request already; do not complete it again or disturb unrelated work. */
    private fun cancel(matches: (Any) -> Boolean) {
        queue.removeAll { matches(it.token) }
        if (active?.token?.let(matches) == true) {
            dialog?.dismiss(); dialog = null
            runtimeCallback = null
            active = null
        }
        pump()
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val origin = UrlPolicy.parseHttps(request.origin.toString())?.origin
        val resources = request.resources
        if (origin == null || resources.isEmpty() || resources.any { it !in SUPPORTED }) { request.deny(); return }
        enqueue(request, request::deny) { done ->
            val granted = mutableListOf<String>()
            fun next(index: Int) {
                if (index == resources.size) {
                    if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
                    done(); return
                }
                val resource = resources[index]
                val camera = resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                confirm(origin, if (camera) R.string.web_permission_camera else R.string.web_permission_microphone) { allow ->
                    if (!allow) next(index + 1) else androidPermission(arrayOf(
                        if (camera) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO)) { allowed ->
                        if (allowed) granted += resource
                        next(index + 1)
                    }
                }
            }
            next(0)
        }
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        cancel { it === request }
    }

    override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>,
        params: FileChooserParams): Boolean {
        val origin = webView.url?.let(UrlPolicy::parseHttps)?.origin
        if (origin == null) { callback.onReceiveValue(null); return true }
        enqueue(callback, { callback.onReceiveValue(null) }) { done ->
            // WebView exposes no trustworthy chooser gesture flag. Require a fresh native gesture.
            confirm(origin, R.string.web_permission_files) { allow ->
                if (!allow) { callback.onReceiveValue(null); done() } else {
                    val types = params.acceptTypes.filter { it.contains('/') && !it.contains(',') }.toTypedArray()
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(types.singleOrNull() ?: "*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    if (types.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types)
                    pickerCallback = callback
                    pickerOutstanding = true
                    try { picker.launch(intent) } catch (_: RuntimeException) {
                        pickerCallback = null; pickerOutstanding = false
                        callback.onReceiveValue(null); done()
                    }
                }
            }
        }
        return true
    }

    /** Cancel on navigation, destruction, profile retirement and rotation; late results are ignored. */
    fun close() {
        dialog?.dismiss(); dialog = null
        runtimeCallback = null
        pickerCallback = null
        val pending = listOfNotNull(active) + queue.toList()
        active = null; queue.clear()
        pending.forEach { it.deny() }
    }

    fun saveState(state: android.os.Bundle) {
        state.putBoolean("web_picker_outstanding", pickerOutstanding)
        state.putBoolean("web_runtime_outstanding", runtimeOutstanding)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        close()
        store.close()
    }

    companion object {
        private val SUPPORTED = setOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE)
    }
}
