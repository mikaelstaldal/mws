package nu.staldal.mws

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** A local response prevents HTTP transmission, including POSTs that bypass URL overriding. */
internal object CleartextRequestBlocker {
    fun response(url: Uri): WebResourceResponse? {
        if (!url.scheme.equals("http", ignoreCase = true)) return null
        return WebResourceResponse("text/plain", "UTF-8", 403, "HTTPS required",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
    }
}
