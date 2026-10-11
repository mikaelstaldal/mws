package nu.staldal.mws

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Portable configuration, without device-local identities, icons or profile state. */
internal object ConfigurationTransfer {
    const val MAX_BYTES = 8 * 1024 * 1024

    fun encode(apps: List<ConfiguredApp>): String = JSONObject().apply {
        put("format", "mobile-web-shell")
        put("version", 1)
        put("apps", JSONArray().apply {
            apps.forEach { app ->
                put(JSONObject().apply {
                    put("name", app.name)
                    put("nameSource", app.nameSource.name)
                    put("configuredUrl", app.configuredUrl)
                    put("finalUrl", app.finalUrl ?: JSONObject.NULL)
                    put("launchUrl", app.launchUrl)
                    put("scope", app.scope)
                    put("allowThirdPartyCookies", app.allowThirdPartyCookies)
                    put("manifestSnapshot", app.manifestSnapshot ?: JSONObject.NULL)
                    put("themeColor", app.themeColor ?: JSONObject.NULL)
                    put("backgroundColor", app.backgroundColor ?: JSONObject.NULL)
                })
            }
        })
    }.toString(2).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }

    fun decode(payload: String, now: Long = System.currentTimeMillis()): List<ConfiguredApp> {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val json = JSONObject(payload)
        require(json.get("format") == "mobile-web-shell" && json.get("version") == 1)
        val apps = json.getJSONArray("apps")
        require(apps.length() <= 1000)
        return (0 until apps.length()).map { index ->
            val entry = apps.getJSONObject(index)
            fun string(key: String): String = (entry.get(key) as? String)
                ?: throw IllegalArgumentException("Expected string")
            fun nullableString(key: String): String? =
                if (entry.isNull(key)) null else string(key)
            fun url(key: String): String = UrlPolicy.parseHttps(string(key))?.serialized
                ?: throw IllegalArgumentException("Invalid HTTPS URL")
            fun color(key: String): Int? {
                if (entry.isNull(key)) return null
                val value = entry.get(key)
                require(value is Int || value is Long)
                val number = (value as Number).toLong()
                require(number in Int.MIN_VALUE..Int.MAX_VALUE)
                return number.toInt()
            }
            val name = AppNames.normalize(string("name"))
                ?: throw IllegalArgumentException("Invalid name")
            val configured = url("configuredUrl")
            val final = nullableString("finalUrl")?.let {
                UrlPolicy.parseHttps(it)?.serialized ?: throw IllegalArgumentException("Invalid final URL")
            }
            val launch = url("launchUrl")
            val scope = url("scope")
            val origin = UrlPolicy.parseHttps(configured)!!.origin
            require(UrlPolicy.parseHttps(launch)!!.origin == origin)
            require(final == null || UrlPolicy.parseHttps(final)!!.origin == origin)
            val snapshot = nullableString("manifestSnapshot")
            if (snapshot != null) require(ManifestProcessor.restoreSnapshot(snapshot) != null)
            require(UrlPolicy.isWithinScope(UrlPolicy.parseHttps(launch)!!, UrlPolicy.parseHttps(scope)!!))
            val cookies = entry.get("allowThirdPartyCookies")
            require(cookies is Boolean)
            ConfiguredApp(id = UUID.randomUUID().toString(), name = name,
                nameSource = NameSource.valueOf(string("nameSource")),
                configuredUrl = configured, finalUrl = final, launchUrl = launch, scope = scope,
                allowThirdPartyCookies = cookies, manifestSnapshot = snapshot,
                themeColor = color("themeColor"), backgroundColor = color("backgroundColor"),
                sortPosition = index, createdAt = now, updatedAt = now)
        }
    }
}
