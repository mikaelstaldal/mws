package nu.staldal.mws

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeScreenInsetsTest {
    private fun insets(ime: Int = 0) = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 48))
        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(30, 0, 12, 0))
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
        .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
        .build()

    private fun assertPadding(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        assertEquals(left, view.paddingLeft)
        assertEquals(top, view.paddingTop)
        assertEquals(right, view.paddingRight)
        assertEquals(bottom, view.paddingBottom)
    }

    @Test fun editorViewportHandlesBarsCutoutAndKeyboardWithoutAccumulatingPadding() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).create()
        try {
            val scroll = controller.get().findViewById<ScrollView>(R.id.editor_scroll)
            val viewport = scroll.parent as View
            val form = scroll.getChildAt(0)
            val spacing = listOf(form.paddingLeft, form.paddingTop, form.paddingRight, form.paddingBottom)
            repeat(2) { ViewCompat.dispatchApplyWindowInsets(viewport, insets()) }
            assertPadding(viewport, 30, 24, 12, 48)
            ViewCompat.dispatchApplyWindowInsets(viewport, insets(300))
            assertPadding(viewport, 30, 24, 12, 300)
            // The form remains inside the scroll viewport, with its original spacing.
            assertSame(form, scroll.getChildAt(0))
            assertEquals(spacing, listOf(form.paddingLeft, form.paddingTop, form.paddingRight, form.paddingBottom))
            ViewCompat.dispatchApplyWindowInsets(viewport, insets())
            assertPadding(viewport, 30, 24, 12, 48)
            ViewCompat.dispatchApplyWindowInsets(viewport, WindowInsetsCompat.Builder().build())
            assertPadding(viewport, 0, 0, 0, 0)
        } finally {
            controller.destroy()
        }
    }

    @Test fun keyboardInsetsBringFocusedLowerFieldIntoView() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        try {
            val scroll = controller.get().findViewById<ScrollView>(R.id.editor_scroll)
            val viewport = scroll.parent as View
            scroll.isSmoothScrollingEnabled = false
            val form = scroll.getChildAt(0) as ViewGroup
            val field = android.widget.EditText(controller.get())
            form.addView(field)
            fun layout() {
                viewport.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                viewport.layout(0, 0, 600, 800)
            }
            layout()
            assertTrue(field.requestFocus())
            scroll.scrollTo(0, 0)
            ViewCompat.dispatchApplyWindowInsets(viewport, insets(300))
            layout()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertTrue("Focused field should be scrolled above keyboard", scroll.scrollY > 0)
            val bounds = android.graphics.Rect()
            field.getDrawingRect(bounds)
            scroll.offsetDescendantRectToMyCoords(field, bounds)
            assertTrue("bottom=${bounds.bottom}, y=${scroll.scrollY}, height=${scroll.height}, padding=${scroll.paddingBottom}",
                bounds.bottom <= scroll.scrollY + scroll.height - scroll.paddingBottom)
        } finally {
            controller.destroy()
        }
    }

    @Test fun providerErrorPreservesSpacingAndReplacesSafeAreaOnRedispatch() {
        val controller = Robolectric.buildActivity(ProviderErrorActivity::class.java).create()
        try {
            val content = controller.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            repeat(2) { ViewCompat.dispatchApplyWindowInsets(content, insets()) }
            assertPadding(content, 62, 56, 44, 80)
            ViewCompat.dispatchApplyWindowInsets(content, WindowInsetsCompat.Builder().build())
            assertPadding(content, 32, 32, 32, 32)
            assertNotNull(controller.get().findViewById<View>(R.id.provider_error_message))
        } finally {
            controller.destroy()
        }
    }
}
