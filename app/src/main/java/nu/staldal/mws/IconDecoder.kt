package nu.staldal.mws

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Xml
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.xmlpull.v1.XmlPullParser

object IconDecoder {
    const val MAX_ENCODED_BYTES = 5 * 1024 * 1024
    const val MAX_DIMENSION = 4096
    const val MAX_PIXELS = 16_000_000L

    fun decode(bytes: ByteArray, type: String? = null): Bitmap? {
        if (bytes.isEmpty() || bytes.size > MAX_ENCODED_BYTES) return null
        return try {
            when {
                looksLikeSvg(bytes, type) -> decodeSvg(bytes)
                bytes.size >= 6 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
                    bytes[2] == 1.toByte() && bytes[3] == 0.toByte() -> decodeIco(bytes)
                else -> decodeRaster(bytes)
            }
        } catch (_: Exception) {
            null // Source bytes, URL and parser errors must never enter diagnostics.
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    fun dimensionsAllowed(width: Int, height: Int): Boolean =
        width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION &&
            width.toLong() * height <= MAX_PIXELS

    private fun decodeRaster(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!dimensionsAllowed(bounds.outWidth, bounds.outHeight)) return null
        if (bounds.outMimeType !in setOf("image/png", "image/webp", "image/jpeg")) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (!dimensionsAllowed(bitmap.width, bitmap.height)) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun looksLikeSvg(bytes: ByteArray, type: String?): Boolean {
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
            )) return false
        val prefix = bytes.take(4096).toByteArray().toString(Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return prefix.startsWith('<') ||
            type?.substringBefore(';')?.trim()?.equals("image/svg+xml", true) == true
    }

    private fun decodeSvg(bytes: ByteArray): Bitmap? {
        if (!SvgSafety.isSafe(bytes)) return null
        val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
        val width = svg.documentWidth
        val height = svg.documentHeight
        val viewBox = svg.documentViewBox
        val aspect = when {
            width > 0 && height > 0 -> width / height
            viewBox != null && viewBox.width() > 0 && viewBox.height() > 0 ->
                viewBox.width() / viewBox.height()
            else -> 1f
        }
        if (!aspect.isFinite() || aspect <= 0f) return null
        val outWidth = if (aspect >= 1f) 1024 else (1024 * aspect).toInt().coerceAtLeast(1)
        val outHeight = if (aspect >= 1f) (1024 / aspect).toInt().coerceAtLeast(1) else 1024
        if (!dimensionsAllowed(outWidth, outHeight)) return null
        if (viewBox == null) {
            svg.setDocumentViewBox(0f, 0f, if (width > 0) width else 512f,
                if (height > 0) height else 512f)
        }
        svg.setDocumentWidth(outWidth.toFloat())
        svg.setDocumentHeight(outHeight.toFloat())
        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        return try {
            svg.renderToCanvas(Canvas(bitmap))
            bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    private fun decodeIco(bytes: ByteArray): Bitmap? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = b.getShort(4).toInt() and 0xffff
        if (count !in 1..256 || 6L + count * 16L > bytes.size) return null
        val entries = (0 until count).mapNotNull { index ->
            val at = 6 + index * 16
            val width = (bytes[at].toInt() and 255).let { if (it == 0) 256 else it }
            val height = (bytes[at + 1].toInt() and 255).let { if (it == 0) 256 else it }
            val length = b.getInt(at + 8)
            val offset = b.getInt(at + 12)
            if (!dimensionsAllowed(width, height) || length <= 0 || offset < 0 ||
                offset.toLong() + length > bytes.size) null
            else Triple(width * height, offset, length)
        }.sortedByDescending { it.first }
        for ((_, offset, length) in entries) {
            val payload = bytes.copyOfRange(offset, offset + length)
            val decoded = if (payload.size >= 8 && payload.copyOfRange(0, 8).contentEquals(
                    byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
                )) decodeRaster(payload) else decodeIcoDib(payload)
            if (decoded != null) return decoded
        }
        return null
    }

    /** ICO DIB uses a doubled height: colour rows followed by a 1-bit transparency mask. */
    private fun decodeIcoDib(bytes: ByteArray): Bitmap? {
        if (bytes.size < 40) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val header = b.getInt(0)
        val width = b.getInt(4)
        val doubledHeight = b.getInt(8)
        if (header != 40 || doubledHeight <= 0 || doubledHeight % 2 != 0 ||
            !dimensionsAllowed(width, doubledHeight / 2) ||
            b.getShort(12).toInt() != 1 || b.getShort(14).toInt() != 32 || b.getInt(16) != 0
        ) return null
        val height = doubledHeight / 2
        val colorEnd = header.toLong() + width.toLong() * height * 4
        val maskStride = ((width + 31) / 32) * 4
        if (colorEnd + maskStride.toLong() * height > bytes.size) return null
        val pixels = IntArray(width * height)
        var hasAlpha = false
        for (y in 0 until height) for (x in 0 until width) {
            val src = header + ((height - 1 - y) * width + x) * 4
            val alpha = bytes[src + 3].toInt() and 255
            if (alpha != 0) hasAlpha = true
            pixels[y * width + x] = (alpha shl 24) or
                ((bytes[src + 2].toInt() and 255) shl 16) or
                ((bytes[src + 1].toInt() and 255) shl 8) or
                (bytes[src].toInt() and 255)
        }
        for (y in 0 until height) for (x in 0 until width) {
            val maskAt = colorEnd.toInt() + (height - 1 - y) * maskStride + x / 8
            val masked = (bytes[maskAt].toInt() ushr (7 - x % 8)) and 1 != 0
            if (masked) pixels[y * width + x] = pixels[y * width + x] and 0x00ffffff
            else if (!hasAlpha) pixels[y * width + x] = pixels[y * width + x] or (0xff shl 24)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}

/** Rejects active and external SVG features before bytes reach the renderer. */
object SvgSafety {
    fun isSafe(bytes: ByteArray): Boolean {
        return try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setInput(ByteArrayInputStream(bytes), null)
            }
            var root = false
            var depth = 0
            var elements = 0
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF,
                    XmlPullParser.PROCESSING_INSTRUCTION -> return false
                    XmlPullParser.START_TAG -> {
                        depth++
                        elements++
                        if (depth > 64 || elements > 10_000) return false
                        val name = parser.name.lowercase()
                        if (!root) {
                            if (name != "svg") return false
                            root = true
                        }
                        if (name in forbiddenElements || name.startsWith("animate") ||
                            parser.namespace.orEmpty().let {
                                it.isNotEmpty() && it != "http://www.w3.org/2000/svg"
                            }) return false
                        for (i in 0 until parser.attributeCount) {
                            val key = parser.getAttributeName(i).lowercase()
                            val value = parser.getAttributeValue(i).trim()
                            if (key.startsWith("on") || key.contains("font") || key == "base" ||
                                key == "href" && !value.startsWith("#") ||
                                Regex("@import|expression\\s*\\(", RegexOption.IGNORE_CASE)
                                    .containsMatchIn(value) || !safeUrls(value)
                            ) return false
                        }
                    }
                    XmlPullParser.END_TAG -> depth--
                }
            }
            root
        } catch (_: Exception) {
            false
        }
    }

    private fun safeUrls(value: String): Boolean {
        val starts = Regex("url\\s*\\(", RegexOption.IGNORE_CASE).findAll(value).count()
        val matches = Regex("url\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE).findAll(value).toList()
        if (starts != matches.size) return false
        return matches.all {
            it.groupValues[1].trim().trim('"', '\'').matches(Regex("#[^\\s'\"()]+"))
        }
    }

    private val forbiddenElements = setOf("script", "image", "foreignobject", "iframe",
        "audio", "video", "style", "text", "tspan", "textpath", "font", "font-face",
        "set", "animate", "animatemotion", "animatetransform", "animatecolor", "discard",
        "use")
}
