package nu.staldal.mws

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LiveShellRegistryTest {
    @Test fun currentUrlSupplierIsRemovedWithItsShell() {
        val registry = LiveShellRegistry()
        val old = registry.register("app", currentUrl = { "https://example.com/old" }, close = {})
        val newer = registry.register("app", currentUrl = { "https://example.com/new" }, close = {})
        assertEquals("https://example.com/new", registry.currentUrl("app"))
        old()
        assertEquals("https://example.com/new", registry.currentUrl("app"))
        newer()
        assertNull(registry.currentUrl("app"))
    }

    @Test fun closeAllRemovesCurrentUrl() = runBlocking {
        val registry = LiveShellRegistry()
        registry.register("app", currentUrl = { "https://example.com/" }, close = {})
        registry.closeAll("app")
        assertNull(registry.currentUrl("app"))
    }
}
