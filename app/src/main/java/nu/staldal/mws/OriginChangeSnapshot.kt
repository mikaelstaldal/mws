package nu.staldal.mws

import org.json.JSONObject

/** Backup-excluded replay payload; contains configuration only, never MyPass credentials. */
internal object OriginChangeSnapshot {
    fun encode(app: ConfiguredApp): String = JSONObject().apply {
        put("id", app.id); put("name", app.name); put("nameSource", app.nameSource.name)
        put("configuredUrl", app.configuredUrl); put("finalUrl", app.finalUrl)
        put("launchUrl", app.launchUrl); put("scope", app.scope)
        put("allowThirdPartyCookies", app.allowThirdPartyCookies)
        put("manifestSnapshot", app.manifestSnapshot); put("iconReference", app.iconReference)
        put("themeColor", app.themeColor); put("backgroundColor", app.backgroundColor)
        put("sensitiveUrlConsent", app.sensitiveUrlConsent)
        put("createdAt", app.createdAt); put("updatedAt", app.updatedAt)
    }.toString()

    fun decode(payload: String): ConfiguredApp = JSONObject(payload).let { json ->
        fun string(key: String) = if (json.isNull(key)) null else json.getString(key)
        fun color(key: String) = if (json.isNull(key)) null else json.getInt(key)
        ConfiguredApp(id = json.getString("id"), name = json.getString("name"),
            nameSource = NameSource.valueOf(json.getString("nameSource")),
            configuredUrl = json.getString("configuredUrl"), finalUrl = string("finalUrl"),
            launchUrl = json.getString("launchUrl"), scope = json.getString("scope"),
            allowThirdPartyCookies = json.getBoolean("allowThirdPartyCookies"),
            manifestSnapshot = string("manifestSnapshot"), iconReference = string("iconReference"),
            themeColor = color("themeColor"), backgroundColor = color("backgroundColor"),
            sensitiveUrlConsent = string("sensitiveUrlConsent"),
            createdAt = json.getLong("createdAt"), updatedAt = json.getLong("updatedAt"))
    }
}
