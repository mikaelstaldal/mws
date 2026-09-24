package nu.staldal.mws

import org.junit.Assert.assertTrue
import org.junit.Test

class ScaffoldTest {
    @Test fun testInfrastructureRuns() {
        assertTrue(BuildConfig.APPLICATION_ID.isNotEmpty())
    }
}
