package nu.staldal.mws

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CleartextRequestBlockerTest {
    @Test fun blocksHttpForLoopbackAndOtherHostsWithAnEmptyUncacheableResponse() {
        for (url in listOf("http://127.0.0.1/submit", "http://[::1]/submit", "HTTP://example.com/")) {
            val response = checkNotNull(CleartextRequestBlocker.response(Uri.parse(url)))
            assertEquals(403, response.statusCode)
            assertEquals("no-store", response.responseHeaders["Cache-Control"])
            assertEquals(-1, response.data.read())
        }
    }

    @Test fun leavesHttpsAndLocalDocumentsToTheirExistingPolicies() {
        for (url in listOf("https://example.com/", "about:blank", "data:text/plain,x"))
            assertNull(CleartextRequestBlocker.response(Uri.parse(url)))
    }
}
