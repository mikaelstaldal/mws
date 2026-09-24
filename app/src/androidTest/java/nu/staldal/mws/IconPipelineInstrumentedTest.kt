package nu.staldal.mws

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android's XML parser and renderer, rather than Robolectric's desktop substitutes. */
@RunWith(AndroidJUnit4::class)
class IconPipelineInstrumentedTest {
    @Test fun safeSvgRendersAtFullSizeAndActiveSvgIsRejected() {
        val safe = "<svg xmlns='http://www.w3.org/2000/svg' width='32' height='32'>" +
            "<rect width='32' height='32' fill='#ff0000'/></svg>"
        assertTrue(SvgSafety.isSafe(safe.toByteArray()))
        val icon = IconDecoder.decode(safe.toByteArray(), "image/svg+xml")
        assertNotNull(icon)
        assertEquals(Color.RED, icon!!.getPixel(1000, 1000))
        assertFalse(SvgSafety.isSafe("<svg><script>alert(1)</script></svg>".toByteArray()))
        assertFalse(SvgSafety.isSafe(("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" +
            "<svg/>").toByteArray()))
    }
}
