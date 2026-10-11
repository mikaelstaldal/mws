package nu.staldal.mws

import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** Refreshes one imported record in a disposable profile, without opening its saved profile. */
internal class ImportSiteRefresh(private val host: FrameLayout, private val profiles: ProfileManager) {
    suspend fun refresh(app: ConfiguredApp): ConfiguredApp = coroutineScope {
        val stages = EditorDiscovery(app, app.configuredUrl)
        val completed = CompletableDeferred<Unit>()
        val bytes = AtomicLong(0)
        var client: NativeDiscoveryClient? = null
        var metadata: Job? = null

        fun fetch(snapshot: DiscoveryDomSnapshot?, terminal: Boolean) {
            metadata?.cancel()
            client?.cancel()
            val document = stages.finalUrl ?: app.configuredUrl
            val current = NativeDiscoveryClient(document, UrlPolicy.parseHttps(document)!!.origin,
                attemptBytes = bytes)
            client = current
            metadata = launch {
                refreshImportMetadata(stages, snapshot, current)
                if (terminal) completed.complete(Unit)
            }
        }

        val session = DiscoverySession(host, profiles, this,
            onDocument = { _, result ->
                stages.document(result)
                if (result.crossOrigin) completed.complete(Unit)
                else fetch(null, result.failure != null)
            },
            onOverallTimeout = { completed.complete(Unit) },
            onBlocked = { fetch(null, true) },
            onSnapshot = { _, snapshot -> fetch(snapshot, true) })
        try {
            // Also bounds profile creation and DOM extraction, before/after the document timer.
            withTimeoutOrNull(35_000) {
                session.refresh(app.configuredUrl)
                completed.await()
            }
        } finally {
            client?.cancel()
            metadata?.cancel()
            withContext(NonCancellable) {
                metadata?.join()
                session.close()
            }
        }
        val consented = applyImportDiscovery(app, stages)
        val icon = stages.icon
        if (icon == null) consented else withContext(Dispatchers.IO) {
            val reference = try { IconStore(host.context).save(app.id, icon) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { consented.iconReference }
            consented.copy(iconReference = reference)
        }
    }
}

/** The same staged fallbacks used by Refresh Site Details, with native work off the UI thread. */
internal suspend fun refreshImportMetadata(
    stages: EditorDiscovery, snapshot: DiscoveryDomSnapshot?, client: NativeDiscoveryClient,
) {
    if (snapshot != null) stages.title = snapshot.title
    val link = snapshot?.selectedManifest
    val response = withContext(Dispatchers.IO) {
        link?.takeIf { it.usable }?.url?.let {
            client.fetchManifest(it, link.crossorigin,
                link.referrerPolicy?.takeIf(String::isNotBlank) ?: "no-referrer")
        }
    }
    if (snapshot != null && link == null) stages.manifestCompleted(null)
    else if (response != null) {
        ManifestProcessor.process(response.body.toString(Charsets.UTF_8), response.url,
            stages.finalUrl, stages.configuredUrl, stages.title).snapshot?.let(stages::manifestCompleted)
    }
    if (response == null) stages.manifest?.let { client.retainManifestSource(it.manifestUrl) }
    val candidates = mutableListOf<IconCandidate>()
    if (snapshot != null) candidates += stages.manifest?.icons.orEmpty()
    snapshot?.icons?.filter { it.usable && it.mediaMatches }?.forEachIndexed { index, link ->
        candidates += IconCandidate(link.url!!, IconCandidate.Source.PAGE,
            link.sizes, link.type, null, index, link.referrerPolicy)
    }
    val document = stages.finalUrl ?: stages.configuredUrl
    candidates += IconCandidate(UrlPolicy.parseHttps(document)!!.origin + "/favicon.ico",
        IconCandidate.Source.FAVICON)
    client.setReferrerPolicies("no-referrer", "no-referrer")
    val icon = withContext(Dispatchers.IO) { IconPipeline.select(candidates, client) }
    stages.icon = icon ?: stages.icon
}

internal fun applyImportDiscovery(app: ConfiguredApp, stages: EditorDiscovery): ConfiguredApp {
    val refreshed = stages.apply(app).let {
        if (app.nameSource == NameSource.USER_EDITED) it
        else if (stages.title != null) it.copy(name = stages.suggestedName()) else it
    }
    return EditorConsent.pending(refreshed).fold(refreshed) { record, url ->
        EditorConsent.answer(record, url, false) ?: app
    }.let(EditorConsent::prune)
}
