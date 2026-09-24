package nu.staldal.mws

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyPassRequestStateTest {
    @Test fun onlyOneOutstandingRequestAndMatchingIdentityCanConsumeIt() {
        val state = MyPassRequestState()
        val identity = MyPassRequestState.Identity("shell", "navigation-1", "document-1")
        val request = state.begin(identity)!!
        assertNull(state.begin(identity))
        assertFalse(state.consume("stale", identity))
        assertFalse(state.consume(request.requestId, identity.copy(navigation = "navigation-2")))
        assertNotNull(state.pending)
        assertTrue(state.consume(request.requestId, identity))
        assertFalse(state.consume(request.requestId, identity))
        assertNull(state.pending)
    }

    @Test fun cancelInvalidatesLateResult() {
        val state = MyPassRequestState()
        val identity = MyPassRequestState.Identity("discovery", "attempt-1")
        val request = state.begin(identity)!!
        state.cancel()
        assertFalse(state.consume(request.requestId, identity))
        assertNotNull(state.begin(identity))
    }
}
