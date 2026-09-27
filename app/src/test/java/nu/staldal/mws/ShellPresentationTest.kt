package nu.staldal.mws

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ShellPresentationTest {
    @Test fun wcagReferenceValuesAndIconSelection() {
        assertEquals(21.0, ShellColors.contrast(Color.BLACK, true), 0.0001)
        assertEquals(21.0, ShellColors.contrast(Color.WHITE, false), 0.0001)
        assertFalse(ShellColors.bar(Color.BLACK, Color.WHITE, Color.BLUE).darkIcons)
        assertTrue(ShellColors.bar(Color.WHITE, Color.BLACK, Color.BLUE).darkIcons)
        for (gray in 0..255) {
            val bar = ShellColors.bar(Color.rgb(gray, gray, gray), Color.WHITE, Color.BLACK)
            assertTrue(ShellColors.contrast(bar.color, !bar.darkIcons) >= 4.5)
        }
    }
    @Test fun alphaUsesActiveSurfaceAndMissingManifestFallsBack() {
        assertEquals(Color.BLACK, ShellColors.bar(Color.TRANSPARENT, Color.BLACK, Color.BLUE).color)
        assertEquals(Color.WHITE, ShellColors.bar(Color.TRANSPARENT, Color.WHITE, Color.BLUE).color)
        assertEquals(Color.BLUE, ShellColors.bar(null, Color.BLACK, Color.BLUE).color)
    }
    @Test @Config(qualifiers = "night") fun darkThemeBackgroundFallbackUsesActiveSurface() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        activity.setTheme(R.style.Theme_MobileWebShell)
        val content = FrameLayout(activity)
        activity.setContentView(content)
        ShellPresentation(activity, content).apply(null)
        val value = android.util.TypedValue()
        activity.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, value, true)
        val expected = if (value.resourceId != 0) activity.getColor(value.resourceId) else value.data
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        activity.window.decorView.background.setBounds(0, 0, 10, 10)
        activity.window.decorView.background.draw(Canvas(bitmap))
        assertEquals(expected, bitmap.getPixel(5, 5))
        assertTrue(ShellColors.contrast(expected, true) > ShellColors.contrast(expected, false))
    }
    @Test fun insetsProtectNavigationAndKeyboardEvenInFullscreen() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        activity.setTheme(R.style.Theme_MobileWebShell)
        val content = FrameLayout(activity)
        activity.setContentView(content)
        val presentation = ShellPresentation(activity, content)
        val app = ConfiguredApp("id", "App", NameSource.DISCOVERED, "https://example.com", null,
            "https://example.com", "https://example.com", manifestSnapshot = "{\"display\":\"fullscreen\"}",
            createdAt = 1, updatedAt = 1)
        presentation.apply(app)
        val insets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.of(0, 0, 0, 30))
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, 200))
            .build()
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(content, insets)
        assertEquals(200, content.paddingBottom)
        assertEquals(0, content.paddingTop)
        presentation.apply(app.copy(manifestSnapshot = "{\"display\":\"browser\"}"))
        val normal = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.of(0, 24, 30, 0))
            .build()
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(content, normal)
        assertEquals(24, content.paddingTop)
        assertEquals(30, content.paddingRight)
    }
    @Test fun paintsManifestBackgroundBeforeWebViewAndDoesNotChangeNativeWindow() {
        val native = Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        val original = native.window.decorView.background
        val shell = Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        shell.setTheme(R.style.Theme_MobileWebShell)
        val content = FrameLayout(shell)
        shell.setContentView(content)
        val presentation = ShellPresentation(shell, content)
        val app = ConfiguredApp("id", "App", NameSource.DISCOVERED, "https://example.com", null,
            "https://example.com", "https://example.com", themeColor = Color.BLACK,
            backgroundColor = Color.RED, createdAt = 1, updatedAt = 1)
        presentation.apply(app)
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        shell.window.decorView.background.setBounds(0, 0, 10, 10)
        shell.window.decorView.background.draw(Canvas(bitmap))
        assertEquals(Color.RED, bitmap.getPixel(5, 5))
        assertEquals(Color.BLACK, presentation.taskColor)
        assertSame(original, native.window.decorView.background)
        presentation.apply(app.copy(backgroundColor = Color.TRANSPARENT))
        shell.window.decorView.background.draw(Canvas(bitmap))
        assertNotEquals(Color.TRANSPARENT, bitmap.getPixel(5, 5))
    }
}
