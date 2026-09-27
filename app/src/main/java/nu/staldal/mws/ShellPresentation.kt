package nu.staldal.mws

import android.app.Activity
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.ColorFilter
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.pow

/** WCAG 2.1 contrast after compositing over the current opaque surface. */
internal object ShellColors {
    data class Bar(val color: Int, val darkIcons: Boolean)
    fun contrast(color: Int, white: Boolean): Double {
        fun linear(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val luminance = 0.2126 * linear(Color.red(color)) +
            0.7152 * linear(Color.green(color)) + 0.0722 * linear(Color.blue(color))
        return if (white) 1.05 / (luminance + 0.05) else (luminance + 0.05) / 0.05
    }
    fun bar(manifest: Int?, surface: Int, fallback: Int): Bar {
        fun choose(color: Int) = Bar(color, contrast(color, false) > contrast(color, true))
        val candidate = manifest?.let { ColorUtils.compositeColors(it, surface) }?.let(::choose)
        return candidate?.takeIf { contrast(it.color, !it.darkIcons) >= 4.5 }
            ?: choose(ColorUtils.compositeColors(fallback, surface))
    }
}

/** Only shell windows receive manifest presentation. The drawable paints the inset bar areas. */
internal class ShellPresentation(private val activity: Activity, private val content: View) {
    private val backdrop = Backdrop()
    private fun themeColor(attribute: Int): Int {
        val value = TypedValue()
        activity.theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) activity.getColor(value.resourceId) else value.data
    }
    private val surface get() = themeColor(com.google.android.material.R.attr.colorSurface)
    private val fallback get() = themeColor(com.google.android.material.R.attr.colorPrimary)
    val backgroundColor: Int get() = backdrop.page
    var taskColor: Int = Color.BLACK
        private set

    init {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.setBackgroundDrawable(backdrop)
        @Suppress("DEPRECATION")
        activity.window.apply {
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            attributes = attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            isStatusBarContrastEnforced = false
            isNavigationBarContrastEnforced = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            backdrop.left = bars.left
            backdrop.right = bars.right
            backdrop.top = bars.top
            backdrop.bottom = bars.bottom
            backdrop.invalidateSelf()
            WindowInsetsCompat.CONSUMED
        }
        apply(null)
        ViewCompat.requestApplyInsets(content)
    }

    fun apply(app: ConfiguredApp?) {
        val status = ShellColors.bar(app?.themeColor, surface, fallback)
        val navigation = ShellColors.bar(app?.themeColor, surface, fallback)
        taskColor = status.color
        backdrop.status = status.color
        backdrop.navigation = navigation.color
        backdrop.page = app?.backgroundColor?.takeIf { Color.alpha(it) != 0 }
            ?.let { ColorUtils.compositeColors(it, surface) } ?: surface
        backdrop.invalidateSelf()
        WindowCompat.getInsetsController(activity.window, content).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = status.darkIcons
            isAppearanceLightNavigationBars = navigation.darkIcons
            show(WindowInsetsCompat.Type.navigationBars())
            if (app?.manifestSnapshot?.let { runCatching { org.json.JSONObject(it).optString("display") }.getOrNull() } == "fullscreen") hide(WindowInsetsCompat.Type.statusBars())
            else show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private class Backdrop : Drawable() {
        var left = 0
        var right = 0
        var top = 0
        var bottom = 0
        var status = Color.BLACK
        var navigation = Color.BLACK
        var page = Color.WHITE
        private val paint = Paint()
        override fun draw(canvas: Canvas) {
            canvas.drawColor(page)
            paint.color = status
            canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), (bounds.top + top).toFloat(), paint)
            paint.color = navigation
            canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), (bounds.left + left).toFloat(), bounds.bottom.toFloat(), paint)
            canvas.drawRect((bounds.right - right).toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), paint)
            canvas.drawRect(bounds.left.toFloat(), (bounds.bottom - bottom).toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }
}
