package nu.staldal.mws

import org.junit.Assert.*
import org.junit.Test

class NewWindowRoutingTest {
    @Test fun onlyTheFirstGetMainFrameCallbackCanAuthorize() {
        val gate = WindowDestination()
        assertEquals("https://example.com/app/page", gate.beforeLoad("https://example.com/app/page", "GET", true))
        assertNull(gate.beforeLoad("https://other.example/", "GET", true))
    }
    @Test fun postDispatchObservationNeverAuthorizes() {
        val gate = WindowDestination()
        gate.afterDispatch()
        assertNull(gate.beforeLoad("https://example.com/", "GET", true))
    }
    @Test fun unsafeOrUndeterminableRequestsFailClosed() {
        for (url in listOf("about:blank", "", "http://example.com/", "javascript:alert(1)",
            "https://user:password@example.com/")) {
            assertNull(WindowDestination().beforeLoad(url, "GET", true))
        }
        assertNull(WindowDestination().beforeLoad("https://example.com/", "POST", true))
        assertNull(WindowDestination().beforeLoad("https://example.com/", "GET", false))
    }
    @Test fun rejectedFirstCallbackCannotBeReplacedByLaterGet() {
        val gate = WindowDestination()
        assertNull(gate.beforeLoad("about:blank", "GET", true))
        assertNull(gate.beforeLoad("https://example.com/", "GET", true))
    }
}
