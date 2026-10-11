package nu.staldal.mws

import org.junit.Assert.*
import org.junit.Test

class DiscoveryAttemptTest {
    private val configured = requireNotNull(UrlPolicy.parseHttps("https://example.com/start"))

    @Test fun firstCommitWinsAndCrossOriginDoesNotBecomeFinal() {
        val state = DiscoveryAttempt(1, configured, DiscoveryTrigger.REFRESH)
        state.start(100)
        assertTrue(state.commit("https://login.example.net/"))
        assertEquals(DiscoveryDocumentResult(null, true, redirectedUrl = "https://login.example.net/"), state.documentResult)
        assertFalse(state.commit("https://example.com/late"))
        assertEquals(DiscoveryDocumentResult(null, true, redirectedUrl = "https://login.example.net/"), state.documentResult)
    }

    @Test fun redirectDoesNotResetInitialDeadlineAndNoCommitHasNoFinalUrl() {
        val state = DiscoveryAttempt(2, configured, DiscoveryTrigger.DEBOUNCE)
        state.start(100)
        assertEquals(1L, state.remainingInitial(15_099))
        assertEquals(0L, state.remainingInitial(15_100))
        assertTrue(state.failDocument())
        assertNull(state.remainingInitial(15_101))
        assertEquals(DiscoveryDocumentResult(null, false, DiscoveryDocumentFailure.LOAD_ERROR),
            state.documentResult)
        assertFalse(state.commit("https://example.com/redirect-target"))
        assertEquals(14_999L, state.remainingOverall(15_101))
    }

    @Test fun pauseExcludesMyPassTimeFromBothDeadlines() {
        val state = DiscoveryAttempt(3, configured, DiscoveryTrigger.FOCUS_LOSS)
        state.start(0)
        state.pause(5_000)
        assertEquals(10_000L, state.remainingInitial(60_000))
        assertEquals(25_000L, state.remainingOverall(60_000))
        state.resume(65_000)
        assertEquals(10_000L, state.remainingInitial(65_000))
        assertEquals(25_000L, state.remainingOverall(65_000))
        assertTrue(state.commit("https://example.com/final"))
        assertEquals("https://example.com/final", state.documentResult?.finalUrl)
        assertNull(state.remainingInitial(65_000))
        assertEquals(0L, state.remainingOverall(90_000))
        state.finish()
        assertNull(state.remainingOverall(90_000))
    }

    @Test fun failureAndCancellationRejectLateCommit() {
        val failed = DiscoveryAttempt(4, configured, DiscoveryTrigger.REFRESH)
        failed.start(0)
        assertTrue(failed.failDocument(DiscoveryDocumentFailure.HTTPS_REQUIRED))
        assertFalse(failed.commit("https://example.com/late"))
        assertEquals(DiscoveryDocumentFailure.HTTPS_REQUIRED, failed.documentResult?.failure)
        val cancelled = DiscoveryAttempt(5, configured, DiscoveryTrigger.REFRESH)
        cancelled.start(0)
        cancelled.finish()
        assertFalse(cancelled.commit("https://example.com/late"))
        assertNull(cancelled.documentResult)
    }

    @Test fun redirectPageBeforeSnapshotIsFollowedToTheNextSameOriginCommit() {
        val state = DiscoveryAttempt(6, configured, DiscoveryTrigger.REFRESH)
        state.start(0)
        assertFalse(state.follow("https://example.com/userapp/"))
        assertTrue(state.commit("https://example.com/start"))
        assertFalse(state.follow("https://example.com/start#top"))
        assertFalse(state.follow("https://login.example.net/"))
        assertTrue(state.follow("https://example.com/userapp/"))
        assertNull(state.documentResult)
        assertFalse(state.acceptSnapshot())
        assertEquals(1_000L, state.remainingInitial(14_000))
        assertTrue(state.commit("https://example.com/userapp/"))
        assertEquals("https://example.com/userapp/", state.documentResult?.finalUrl)
        assertTrue(state.acceptSnapshot())
        assertFalse(state.follow("https://example.com/later"))
        assertEquals("https://example.com/userapp/", state.documentResult?.finalUrl)
    }

    @Test fun followingIsBoundedAndNeverLeavesCrossOriginOrFailedDocuments() {
        val state = DiscoveryAttempt(7, configured, DiscoveryTrigger.REFRESH)
        state.start(0)
        repeat(DiscoveryAttempt.MAX_FOLLOWS) {
            assertTrue(state.commit("https://example.com/hop$it"))
            assertTrue(state.follow("https://example.com/hop${it + 1}"))
        }
        assertTrue(state.commit("https://example.com/last"))
        assertFalse(state.follow("https://example.com/again"))
        val crossOrigin = DiscoveryAttempt(8, configured, DiscoveryTrigger.REFRESH)
        crossOrigin.start(0)
        crossOrigin.commit("https://login.example.net/")
        assertFalse(crossOrigin.follow("https://example.com/back"))
        assertFalse(crossOrigin.acceptSnapshot())
        val failed = DiscoveryAttempt(9, configured, DiscoveryTrigger.REFRESH)
        failed.start(0)
        failed.failDocument()
        assertFalse(failed.follow("https://example.com/next"))
        val finished = DiscoveryAttempt(10, configured, DiscoveryTrigger.REFRESH)
        finished.start(0)
        finished.commit("https://example.com/start")
        finished.finish()
        assertFalse(finished.follow("https://example.com/next"))
    }
}
