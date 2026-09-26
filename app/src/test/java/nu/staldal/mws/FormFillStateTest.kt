package nu.staldal.mws

import org.junit.Assert.*
import org.junit.Test

class FormFillStateTest {
    @Test fun boundariesAndNonceAreCheckedBeforeConsuming() {
        val state = FormFillState()
        val document = state.commit("https://example.com/login?next=%2Fhome#login")!!
        assertEquals("https://example.com/login?next=%2Fhome#login", document.url)
        assertNull(state.accept(document.epoch, document.origin, false, document.nonce))
        assertNull(state.accept(document.epoch, "https://other.example", true, document.nonce))
        assertNull(state.accept("stale", document.origin, true, document.nonce))
        assertNull(state.accept(document.epoch, document.origin, true, "wrong"))
        assertSame(document, state.accept(document.epoch, document.origin, true, document.nonce))
        assertNull(state.accept(document.epoch, document.origin, true, document.nonce))
    }
    @Test fun navigationErrorsAndReloadInvalidateOldDocuments() {
        val state = FormFillState()
        val first = state.commit("https://example.com/login")!!
        val next = state.commit(first.url)!!
        assertNotEquals(first.nonce, next.nonce)
        assertNotEquals(first.epoch, next.epoch)
        assertFalse(state.current(first))
        state.invalidate()
        assertFalse(state.current(next))
        assertNull(state.accept(next.epoch, next.origin, true, next.nonce))
    }
    @Test fun insecureAndUserinfoDocumentsCannotRequestCredentials() {
        val state = FormFillState()
        for (url in listOf("http://example.com", "https://user:secret@example.com", "data:text/html,login")) {
            assertNull(state.commit(url))
            assertNull(state.document)
        }
    }
}
