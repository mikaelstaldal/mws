package nu.staldal.mws

import org.junit.runner.RunWith
import org.junit.runners.Suite

/** Device evidence entry point; qualification also requires the checks in PROVIDER_QUALIFICATION.md. */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    ProfileIsolationInstrumentedTest::class,
    RuntimeSelfTestInstrumentedTest::class,
    ShellNavigationInstrumentedTest::class,
    ShellBasicAuthInstrumentedTest::class,
    DiscoverySessionInstrumentedTest::class,
)
class ProviderCompatibilitySuite
