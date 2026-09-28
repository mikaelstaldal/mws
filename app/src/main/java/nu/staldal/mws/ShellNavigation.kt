package nu.staldal.mws

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.view.ContextMenu
import android.webkit.WebView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import java.util.Locale

/** External destinations (R§9.3), each named by its destination type. */
enum class ExternalScheme(val scheme: String, @param:StringRes val prompt: Int) {
    MAILTO("mailto", R.string.external_prompt_mailto),
    TEL("tel", R.string.external_prompt_tel),
    SMS("sms", R.string.external_prompt_sms),
    GEO("geo", R.string.external_prompt_geo),
    MARKET("market", R.string.external_prompt_market),
    BANKID("bankid", R.string.external_prompt_bankid);

    companion object {
        fun of(scheme: String): ExternalScheme? = entries.firstOrNull { it.scheme == scheme }
    }
}

sealed interface NavigationDecision {
    /** An HTTPS destination, in or out of scope, loads in the shell. */
    data object Stay : NavigationDecision
    /** An allowlisted scheme may be delegated after confirmation; the navigation itself is cancelled. */
    data class Confirm(val url: String, val scheme: ExternalScheme) : NavigationDecision
    /** Cancelled; [message] is the non-fatal notice, or null for a silent subframe block. */
    data class Block(@param:StringRes val message: Int?) : NavigationDecision
}

/** `shouldOverrideUrlLoading` scheme and transport policy (R§9.3, A§9.4). */
object NavigationPolicy {
    private val schemeSyntax = Regex("[a-z][a-z0-9+.-]*")
    /** Subframe content schemes that are never handed to another app. */
    private val subframeContent = setOf("about", "data", "blob")

    /**
     * [onDisallowedScheme] receives the lowercased scheme (null when it is not valid scheme syntax)
     * of a URL refused only for its scheme, not for its transport, frame or gesture.
     */
    /** The lowercased scheme, or null when [url] does not start with valid scheme syntax. */
    fun scheme(url: String): String? =
        url.substringBefore(':', "").lowercase(Locale.ROOT).takeIf(schemeSyntax::matches)

    fun decide(
        url: String, mainFrame: Boolean, redirect: Boolean, gesture: Boolean,
        onDisallowedScheme: (String?) -> Unit = {},
    ): NavigationDecision {
        val scheme = scheme(url)
        fun block(message: Int) = NavigationDecision.Block(if (mainFrame) message else null)
        return when {
            scheme == "https" -> {
                val parsed = UrlPolicy.parseNavigation(url)
                when {
                    parsed == null -> block(R.string.shell_url_rejected)
                    isBankIdAppLink(parsed) ->
                        if (mainFrame) NavigationDecision.Confirm(url, ExternalScheme.BANKID)
                        else block(R.string.shell_external_blocked)
                    else -> NavigationDecision.Stay
                }
            }
            scheme == "http" -> block(R.string.shell_https_required)
            !mainFrame && scheme in subframeContent -> NavigationDecision.Stay
            scheme != null && ExternalScheme.of(scheme) != null ->
                if (mainFrame && (scheme == "bankid" || (!redirect && gesture)))
                    NavigationDecision.Confirm(url, ExternalScheme.of(scheme)!!)
                else block(R.string.shell_external_blocked)
            // javascript:, data:, file:, content:, intent: and every other scheme.
            else -> {
                onDisallowedScheme(scheme)
                block(R.string.shell_scheme_blocked)
            }
        }
    }

    /** BankID's Android app link, limited to the documented autostart endpoint. */
    private fun isBankIdAppLink(url: UrlPolicy.HttpsUrl): Boolean =
        url.host == "app.bankid.com" && url.path == "/" &&
            url.serialized.substringBefore('#').substringAfter('?', "").split('&').any { parameter ->
                parameter.startsWith("autostarttoken=") && parameter.length > "autostarttoken=".length
            }
}

/**
 * Info-level record of a navigation refused for its scheme. The full URL may carry credentials or
 * other page data, so only a debug build includes it; a release build logs just the scheme.
 */
object DisallowedSchemeLog {
    private const val TAG = "ShellNavigation"

    fun message(scheme: String?, url: String, debug: Boolean): String {
        val name = scheme ?: "<invalid>"
        return if (debug) "Blocked navigation with disallowed scheme $name: $url"
            else "Blocked navigation with disallowed scheme $name"
    }

    fun log(scheme: String?, url: String, debug: Boolean = BuildConfig.DEBUG) {
        Log.i(TAG, message(scheme, url, debug))
    }

    /** For callers that accept HTTPS only: logs any refused scheme other than HTTP(S). */
    fun logUnlessHttp(url: String) {
        val scheme = NavigationPolicy.scheme(url)
        if (scheme != "https" && scheme != "http") log(scheme, url)
    }
}

/**
 * Delegation to other apps from the shell (R§9.3): confirmed external schemes, long-press
 * **Open in browser**, and the download refusal (R§9.5). Intents carry only the destination URI,
 * never headers, referrer extras, credentials or web data, and a confirmation grants nothing
 * beyond that one navigation. Owned by the activity; [close] dismisses any pending dialog.
 */
class ShellHandoff(private val context: Context) {
    private var dialog: AlertDialog? = null
    private var closed = false

    fun notice(@StringRes message: Int) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
    }

    /** One pending dialog at a time; a request arriving meanwhile is dropped. */
    fun confirmExternal(decision: NavigationDecision.Confirm) {
        if (closed || dialog != null) return
        val message = if (decision.scheme == ExternalScheme.BANKID) {
            val scan = CredentialParameters.scan(decision.url)
            val otherKeys = CredentialParameterScan(
                scan.keys.filterNot { it.equals("autostarttoken", ignoreCase = true) }, scan.unparseable)
            context.getString(decision.scheme.prompt) +
                if (otherKeys.isSensitive) "\n\n" + SensitiveUrlWarning.message(context, otherKeys, persisted = false)
                else ""
        } else context.getString(decision.scheme.prompt)
        var answered = false
        dialog = AlertDialog.Builder(context)
            .setMessage(message)
            .setPositiveButton(R.string.external_open) { _, _ ->
                // Click and dismiss are posted separately, so a fast double tap can arrive twice.
                if (answered) return@setPositiveButton
                answered = true
                dialog = null
                if (decision.scheme == ExternalScheme.BANKID) {
                    // This prompt covers the sign-in link and any extra sensitive parameters.
                    start(externalIntent(decision.url), R.string.shell_no_handler)
                } else {
                    withConsent(decision.url) { start(externalIntent(decision.url), R.string.shell_no_handler) }
                }
            }
            .setNegativeButton(R.string.sensitive_url_cancel, null)
            .setOnDismissListener { if (!answered) dialog = null }
            .show()
    }

    /** Adds **Open in browser** only for a plain link to an allowed HTTPS URL. */
    fun populateContextMenu(menu: ContextMenu, webView: WebView) {
        val hit = webView.hitTestResult
        if (hit.type != WebView.HitTestResult.SRC_ANCHOR_TYPE) return
        val url = UrlPolicy.parseNavigation(hit.extra ?: return)?.serialized ?: return
        menu.add(R.string.open_in_browser).setOnMenuItemClickListener {
            openInBrowser(url)
            true
        }
    }

    fun openInBrowser(url: String) {
        if (closed || dialog != null) return
        val allowed = UrlPolicy.parseNavigation(url)?.serialized
        if (allowed == null) {
            notice(if (url.startsWith("http:", ignoreCase = true)) R.string.shell_https_required
                else R.string.shell_url_rejected)
            return
        }
        withConsent(allowed) {
            val intent = browserIntent(context.packageManager, allowed)
            if (intent == null) notice(R.string.shell_no_browser) else start(intent, R.string.shell_no_browser)
        }
    }

    fun close() {
        closed = true
        dialog?.dismiss()
        dialog = null
    }

    /** R§4.1: a credential-like parameter needs the redacted warning and fresh consent every time. */
    private fun withConsent(url: String, proceed: () -> Unit) {
        if (closed) return
        val scan = CredentialParameters.scan(url)
        if (!scan.isSensitive) return proceed()
        dialog?.dismiss()
        var shown: AlertDialog? = null
        shown = SensitiveUrlWarning.show(context, scan, persisted = false) { accepted ->
            if (dialog === shown) dialog = null
            if (accepted && !closed) proceed()
        }
        dialog = shown
    }

    private fun start(intent: Intent, @StringRes missing: Int) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            notice(missing)
        } catch (_: SecurityException) {
            notice(missing)
        }
    }

    companion object {
        /** Only a browsable handler may receive it, in a task of its own. */
        fun externalIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Targets the default browser, so an app claiming the link cannot receive it; with no
         * default the user chooses among handlers. Null when no browser is installed.
         */
        fun browserIntent(packageManager: PackageManager, url: String): Intent? {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE)
            val browsers = packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }.toSet()
            if (browsers.isEmpty()) return null
            // With no default this resolves to the system resolver, whose package is not a browser.
            val default = packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
            return externalIntent(url).apply { if (default in browsers) setPackage(default) }
        }
    }
}
