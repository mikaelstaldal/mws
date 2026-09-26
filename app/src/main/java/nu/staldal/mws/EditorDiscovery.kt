package nu.staldal.mws

/** Completed stages only; an unchanged saved URL supplies the independent stage fallbacks. */
class EditorDiscovery(private val saved: ConfiguredApp?, val configuredUrl: String) {
    private val retained = saved?.takeIf { it.configuredUrl == configuredUrl }
    var finalUrl: String? = retained?.finalUrl
        private set
    var manifest: ManifestSnapshot? = retained?.manifestSnapshot?.let(ManifestProcessor::restoreSnapshot)
        private set
    private var nameStageCompleted = false
    var title: String? = null
        set(value) { field = value; nameStageCompleted = true }
    var icon: SelectedIcon? = null
    private var documentCompleted = false

    fun document(result: DiscoveryDocumentResult) {
        if (result.failure != null || result.crossOrigin) return
        documentCompleted = true
        finalUrl = result.finalUrl
        title = null
        nameStageCompleted = false
        icon = null
    }

    fun manifestCompleted(snapshot: ManifestSnapshot?) { manifest = snapshot }

    fun resolved(): ManifestResult = manifest?.let {
        ManifestProcessor.applySnapshot(it, finalUrl, configuredUrl, title)
    } ?: ManifestProcessor.process(null, null, finalUrl, configuredUrl, title)

    fun suggestedName(): String = sequenceOf(manifest?.shortName, manifest?.name, title,
        retained?.name?.takeIf { !nameStageCompleted && retained.nameSource == NameSource.DISCOVERED },
        UrlPolicy.parseHttps(finalUrl ?: configuredUrl)?.host)
        .mapNotNull { it?.let(AppNames::normalize) }.first()

    fun apply(record: ConfiguredApp): ConfiguredApp {
        if (!documentCompleted && retained != null) return record
        val result = resolved()
        return record.copy(finalUrl = finalUrl, launchUrl = result.launchUrl, scope = result.scope,
            manifestSnapshot = manifest?.toJson(), themeColor = manifest?.themeColor,
            backgroundColor = manifest?.backgroundColor)
    }
}
