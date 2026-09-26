package nu.staldal.mws

/** Name rules shared by typed and discovered names (R§6.1). */
object AppNames {
    const val MAX_CODE_POINTS = 128

    sealed interface Result {
        data class Valid(val name: String) : Result
        data object Empty : Result
        data object TooLong : Result
        data object InvalidCharacter : Result
    }

    /** Discovered names use this too; `null` falls through to the next name source. */
    fun normalize(raw: String): String? = (validate(raw) as? Result.Valid)?.name

    fun validate(raw: String): Result {
        var start = 0
        var end = raw.length
        while (start < end && isUnicodeWhitespace(raw.codePointAt(start)))
            start += Character.charCount(raw.codePointAt(start))
        while (end > start && isUnicodeWhitespace(raw.codePointBefore(end)))
            end -= Character.charCount(raw.codePointBefore(end))
        val name = raw.substring(start, end)
        if (name.isEmpty()) return Result.Empty
        var index = 0
        var visible = false
        while (index < name.length) {
            val codePoint = name.codePointAt(index)
            if (isForbidden(codePoint)) return Result.InvalidCharacter
            if (!isUnicodeWhitespace(codePoint) && !isInvisible(codePoint)) visible = true
            index += Character.charCount(codePoint)
        }
        if (!visible) return Result.Empty
        return if (name.codePointCount(0, name.length) > MAX_CODE_POINTS) Result.TooLong
        else Result.Valid(name)
    }

    private fun isUnicodeWhitespace(codePoint: Int) =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    /**
     * NUL and other controls, lone surrogates, line/paragraph separators, and non-printing
     * format characters (including bidi controls and zero-width spaces). Joiners and emoji tag
     * characters stay allowed because they shape visible text such as emoji sequences.
     */
    private fun isForbidden(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.CONTROL.toInt(), Character.SURROGATE.toInt(),
        Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
        Character.FORMAT.toInt() -> !isAllowedFormat(codePoint)
        else -> false
    }

    private fun isAllowedFormat(codePoint: Int) =
        codePoint == 0x200C || codePoint == 0x200D || codePoint in 0xE0020..0xE007F

    /** Allowed code points that draw nothing on their own; a name needs one that does. */
    private fun isInvisible(codePoint: Int) = when (Character.getType(codePoint)) {
        Character.FORMAT.toInt(), Character.NON_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }
}

/**
 * The editor's validated draft (R§6.1). [record] builds what Save persists, before
 * sensitive-URL consent is applied.
 */
data class EditorDraft(
    val name: String,
    val nameSource: NameSource,
    val url: UrlPolicy.HttpsUrl,
    val allowThirdPartyCookies: Boolean,
) {
    /**
     * A new app starts with the configured URL as launch URL and its origin as scope. An edit
     * keeps derived metadata for an unchanged URL; a same-origin change invalidates what was
     * derived from the old URL but keeps the stored icon (R§6.2).
     */
    fun record(saved: ConfiguredApp?, id: String, now: Long): ConfiguredApp {
        val configured = url.serialized
        val base = saved ?: ConfiguredApp(id = id, name = name, nameSource = nameSource,
            configuredUrl = configured, finalUrl = null, launchUrl = configured,
            scope = originScope(url), createdAt = now, updatedAt = now)
        val derived = if (saved == null || saved.configuredUrl == configured) base
            else base.copy(finalUrl = null, launchUrl = configured, scope = originScope(url),
                manifestSnapshot = null, themeColor = null, backgroundColor = null)
        return derived.copy(name = name, nameSource = nameSource, configuredUrl = configured,
            allowThirdPartyCookies = allowThirdPartyCookies, updatedAt = now)
    }

    companion object {
        fun originScope(url: UrlPolicy.HttpsUrl) = url.origin + "/"

        /** True when saving [url] would move [saved] to another origin (R§6.2). */
        fun changesOrigin(saved: ConfiguredApp?, url: UrlPolicy.HttpsUrl): Boolean {
            if (saved == null) return false
            val old = UrlPolicy.parseHttps(saved.configuredUrl) ?: return true
            return !UrlPolicy.sameOrigin(old, url)
        }
    }
}

/** Sensitive-URL consent before persisting (R§4.1). */
object EditorConsent {
    /** The URLs [record] would persist that still need consent, configured URL first. */
    fun pending(record: ConfiguredApp): List<String> = SensitiveUrlConsent.pending(
        listOf(record.configuredUrl, record.finalUrl, record.launchUrl),
        SensitiveUrlConsent.parse(record.sensitiveUrlConsent))

    /**
     * Applies the answer for [url]. Declining the typed configured URL blocks Save (`null`);
     * a declined discovered final or launch URL is discarded for the configured URL. A
     * discarded launch URL also drops the manifest it came from, and the scope falls back to the
     * configured URL's origin so the launch URL stays in scope.
     */
    fun answer(record: ConfiguredApp, url: String, accepted: Boolean): ConfiguredApp? {
        if (accepted) {
            val consent = SensitiveUrlConsent.parse(record.sensitiveUrlConsent).grant(url)
            return record.copy(sensitiveUrlConsent = consent.serialize())
        }
        if (url == record.configuredUrl) return null
        val withoutFinal = record.copy(finalUrl = record.finalUrl.takeUnless { it == url })
        if (record.launchUrl != url) return withoutFinal
        val configured = UrlPolicy.parseHttps(record.configuredUrl) ?: return null
        return withoutFinal.copy(launchUrl = configured.serialized,
            scope = EditorDraft.originScope(configured), manifestSnapshot = null,
            themeColor = null, backgroundColor = null)
    }

    /** Drops digests of URLs the record no longer stores. */
    fun prune(record: ConfiguredApp): ConfiguredApp = record.copy(sensitiveUrlConsent =
        SensitiveUrlConsent.parse(record.sensitiveUrlConsent)
            .retainOnly(listOf(record.configuredUrl, record.finalUrl, record.launchUrl)).serialize())
}
