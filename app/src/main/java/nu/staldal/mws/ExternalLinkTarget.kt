package nu.staldal.mws

/** Pick the most specific saved scope; configured order breaks equally specific ties. */
internal object ExternalLinkTarget {
    fun find(url: UrlPolicy.HttpsUrl, sourceId: String, apps: List<ConfiguredApp>): ConfiguredApp? =
        apps.asSequence()
            .filter { it.id != sourceId && it.profileState == ProfileState.ACTIVE }
            .mapNotNull { app ->
                val scope = UrlPolicy.parseHttps(app.scope) ?: return@mapNotNull null
                if (UrlPolicy.isWithinScope(url, scope)) app to scope.path.length else null
            }
            .maxByOrNull { it.second }?.first
}
