package nu.staldal.mws

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeAccessibilityTest {
    @Test fun editorHasHeadingAndPoliteStatusRegionsWithVerticalActions() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).create()
        try {
            val activity = controller.get()
            val column = activity.findViewById<ScrollView>(R.id.editor_scroll).getChildAt(0) as ViewGroup
            assertTrue(ViewCompat.isAccessibilityHeading(column.getChildAt(0)))
            for (id in listOf(R.id.editor_load_error, R.id.editor_clearing_notice,
                R.id.editor_discovery_status, R.id.editor_maintenance_status)) {
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                    activity.findViewById<View>(id).accessibilityLiveRegion)
            }
            assertEquals(LinearLayout.VERTICAL,
                (activity.findViewById<View>(R.id.editor_save).parent as LinearLayout).orientation)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                activity.findViewById<View>(R.id.editor_discovery_icon).importantForAccessibility)
        } finally { controller.destroy() }
    }

    @Test fun providerErrorHasScrollablePoliteStatusRegion() {
        val controller = Robolectric.buildActivity(ProviderErrorActivity::class.java).create()
        try {
            val message = controller.get().findViewById<View>(R.id.provider_error_message)
            assertTrue(message.parent is ScrollView)
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, message.accessibilityLiveRegion)
        } finally { controller.destroy() }
    }
}
