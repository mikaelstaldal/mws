package nu.staldal.mws

import android.content.Context
import android.os.Build
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** The key invalidates a self-test whenever the provider or device environment changes. */
data class ProviderKey(
    val packageName: String,
    val version: String,
    val apiLevel: Int,
    val primaryAbi: String,
)

/** Supplied by the runtime self-test. A missing or stale result always fails closed. */
data class ProviderSelfTestResult(
    val key: ProviderKey,
    val profileIsolation: Boolean,
    val profileDeletion: Boolean,
    val basicAuth: Boolean,
    val newWindowRouting: Boolean,
    val formFill: Boolean,
    val discoveryCookies: Boolean,
)

fun interface ProviderSelfTestStore {
    fun resultFor(key: ProviderKey): ProviderSelfTestResult?
}

data class ProviderSnapshot(val key: ProviderKey?, val multiProfile: Boolean)

fun interface ProviderSnapshotSource {
    fun snapshot(): ProviderSnapshot
}

/** This source is queried for every decision, so an installed provider change takes effect immediately. */
class AndroidProviderSnapshotSource(private val context: Context) : ProviderSnapshotSource {
    override fun snapshot(): ProviderSnapshot {
        val provider = try {
            WebViewCompat.getCurrentWebViewPackage(context)
        } catch (_: RuntimeException) {
            null
        }
        val multiProfile = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        } catch (_: RuntimeException) {
            false
        }
        return ProviderSnapshot(
            provider?.let {
                ProviderKey(it.packageName, it.versionName.orEmpty(), Build.VERSION.SDK_INT,
                    Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            },
            multiProfile,
        )
    }
}

enum class ProviderBlockReason { NO_PROVIDER, UNLISTED_PROVIDER, INVALID_VERSION, BELOW_FLOOR,
    NO_MULTI_PROFILE, SELF_TEST_PENDING, ISOLATION_FAILED, DELETION_FAILED }

data class ProviderFeatures(
    val profileIsolation: Boolean,
    val basicAuth: Boolean,
    val newWindowRouting: Boolean,
    val formFill: Boolean,
    val discoveryCookies: Boolean,
)

data class ProviderDecision(
    val key: ProviderKey?,
    val reason: ProviderBlockReason?,
    val features: ProviderFeatures,
) {
    val compatible: Boolean get() = reason == null
}

enum class WebOperation { LAUNCH, DISCOVERY, CLEAR }

/** Central gate for every operation that could touch a WebView profile. */
class ProviderCompatibility(
    private val source: ProviderSnapshotSource,
    private val selfTests: ProviderSelfTestStore,
    private val floors: Map<String, List<Int>> = GeneratedProviderFloors.values,
    private val debugOverride: Boolean = false,
) {
    fun current(): ProviderDecision {
        val snapshot = source.snapshot()
        val key = snapshot.key ?: return blocked(null, ProviderBlockReason.NO_PROVIDER)
        val floor = floors[key.packageName]
        if (floor == null && !debugOverride) return blocked(key, ProviderBlockReason.UNLISTED_PROVIDER)
        val version = parseVersion(key.version)
            ?: return blocked(key, ProviderBlockReason.INVALID_VERSION)
        if (floor != null && compare(version, floor) < 0 && !debugOverride) {
            return blocked(key, ProviderBlockReason.BELOW_FLOOR)
        }
        if (!snapshot.multiProfile) return blocked(key, ProviderBlockReason.NO_MULTI_PROFILE)
        val test = selfTests.resultFor(key)
        if (test == null || test.key != key) {
            if (!debugOverride) return blocked(key, ProviderBlockReason.SELF_TEST_PENDING)
        } else {
            if (!test.profileIsolation) return blocked(key, ProviderBlockReason.ISOLATION_FAILED)
            if (!test.profileDeletion) return blocked(key, ProviderBlockReason.DELETION_FAILED)
        }
        // The override enables development before the self-test task lands. It never bypasses
        // MULTI_PROFILE or an explicitly failed isolation/deletion probe.
        return ProviderDecision(key, null, ProviderFeatures(
            profileIsolation = true,
            basicAuth = test?.basicAuth == true,
            newWindowRouting = test?.newWindowRouting == true,
            formFill = test?.formFill == true,
            discoveryCookies = test?.discoveryCookies == true,
        ))
    }

    fun allowed(operation: WebOperation): Boolean = when (operation) {
        WebOperation.LAUNCH, WebOperation.DISCOVERY, WebOperation.CLEAR -> current().compatible
    }

    /** Native record removal is always available; profile deletion must use [mayAccessProfile]. */
    fun mayRemoveConfiguration(): Boolean = true

    /** Also gate deferred cleanup performed after a native record has been removed. */
    fun mayAccessProfile(): Boolean = current().compatible

    private fun blocked(key: ProviderKey?, reason: ProviderBlockReason) = ProviderDecision(
        key, reason, ProviderFeatures(false, false, false, false, false))

    private fun parseVersion(value: String): List<Int>? {
        if (!value.matches(Regex("[0-9]+(\\.[0-9]+)*"))) return null
        return value.split('.').map { it.toIntOrNull() ?: return null }
    }

    private fun compare(left: List<Int>, right: List<Int>): Int {
        for (i in 0 until maxOf(left.size, right.size)) {
            val part = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (part != 0) return part
        }
        return 0
    }
}
