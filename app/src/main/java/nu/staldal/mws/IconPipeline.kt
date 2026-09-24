package nu.staldal.mws

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Implemented by the discovery native client, which owns redirects, timeouts and byte budgets. */
fun interface IconFetcher {
    fun fetch(candidate: IconCandidate): ByteArray?
}

data class SelectedIcon(val candidate: IconCandidate, val source: Bitmap)

object IconPipeline {
    val SIZES = intArrayOf(48, 72, 96, 144, 192, 288, 512)

    /** Run on a worker thread. A failed fetch or decode falls through to the next ranked URL. */
    fun select(candidates: List<IconCandidate>, fetcher: IconFetcher): SelectedIcon? {
        for (candidate in IconSelection.ranked(candidates)) {
            val uri = Uri.parse(candidate.url)
            if (uri.scheme != "https" || uri.host.isNullOrBlank() ||
                candidate.url.toByteArray(Charsets.UTF_8).size > 8192) continue
            val bytes = try { fetcher.fetch(candidate) } catch (_: Exception) { null } ?: continue
            val bitmap = IconDecoder.decode(bytes, candidate.type) ?: continue
            return SelectedIcon(candidate, bitmap)
        }
        return null
    }

    /** Center fit regular art; maskable art uses its central 80% safe zone. */
    fun render(source: Bitmap, size: Int, maskable: Boolean): Bitmap {
        require(size in 1..IconDecoder.MAX_DIMENSION)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (maskable) {
            val side = (minOf(source.width, source.height) * 0.8f).coerceAtLeast(1f)
            val left = (source.width - side) / 2f
            val top = (source.height - side) / 2f
            canvas.save()
            val mask = Path().apply { addRoundRect(RectF(0f, 0f, size.toFloat(),
                size.toFloat()), size * 0.2f, size * 0.2f, Path.Direction.CW) }
            canvas.clipPath(mask)
            canvas.drawBitmap(source, Rect(left.toInt(), top.toInt(), (left + side).toInt(),
                (top + side).toInt()), RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
            canvas.restore()
        } else {
            val scale = minOf(size.toFloat() / source.width, size.toFloat() / source.height)
            val width = source.width * scale
            val height = source.height * scale
            canvas.drawBitmap(source, null, RectF((size - width) / 2f, (size - height) / 2f,
                (size + width) / 2f, (size + height) / 2f), paint)
        }
        return result
    }
}

/** References are app-ID and generation pairs; the entire generation is published by rename. */
class IconStore(private val context: Context) {
    private val root get() = File(context.noBackupFilesDir, "icons")

    fun save(appId: String, selected: SelectedIcon): String {
        require(ID.matches(appId))
        val generation = UUID.randomUUID().toString()
        val appDirectory = File(root, appId).apply { mkdirs() }
        val pending = File(appDirectory, ".$generation").apply { mkdirs() }
        check(pending.isDirectory)
        try {
            for (size in IconPipeline.SIZES) {
                val rendition = IconPipeline.render(selected.source, size, selected.candidate.isMaskable)
                try { writePng(File(pending, "$size.png"), rendition) }
                finally { rendition.recycle() }
            }
            val highSize = minOf(4000,
                maxOf(selected.source.width, selected.source.height))
            if (highSize > 512) {
                val high = IconPipeline.render(selected.source, highSize,
                    selected.candidate.isMaskable)
                try { writePng(File(pending, "source.png"), high) }
                finally { high.recycle() }
            }
            check(pending.renameTo(File(appDirectory, generation)))
            return "$appId/$generation"
        } finally {
            if (pending.exists()) pending.deleteRecursively()
        }
    }

    fun load(reference: String?, size: Int): Bitmap? {
        if (size !in IconPipeline.SIZES || reference == null || !REFERENCE.matches(reference))
            return null
        val path = File(root, "$reference/$size.png")
        return if (path.isFile) BitmapFactorySafe.decode(path) else null
    }

    fun defaultIcon(): Drawable = ContextCompat.getDrawable(context, R.drawable.ic_launcher)!!

    fun delete(appId: String) {
        require(ID.matches(appId))
        File(root, appId).deleteRecursively()
    }

    fun prune(appId: String, retainedReference: String?) {
        require(ID.matches(appId))
        val generation = retainedReference?.takeIf { REFERENCE.matches(it) && it.startsWith("$appId/") }
            ?.substringAfter('/')
        File(root, appId).listFiles()?.filter {
            !it.name.startsWith('.') && it.name != generation
        }?.forEach(File::deleteRecursively)
    }

    private fun writePng(path: File, bitmap: Bitmap) {
        FileOutputStream(path).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.fd.sync()
        }
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val REFERENCE = Regex("[A-Za-z0-9_-]{1,128}/[0-9a-fA-F-]{36}")
    }
}

private object BitmapFactorySafe {
    fun decode(file: File): Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        if (!IconDecoder.dimensionsAllowed(bounds.outWidth, bounds.outHeight)) null
        else android.graphics.BitmapFactory.decodeFile(file.path)
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
}
