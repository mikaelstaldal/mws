package nu.staldal.mws

import org.junit.runner.RunWith
import org.junit.runners.Suite

/** Device evidence entry point; qualification also requires the checks in PROVIDER_QUALIFICATION.md. */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    ProviderEvidenceInstrumentedTest::class,
    ProfileIsolationInstrumentedTest::class,
    RuntimeSelfTestInstrumentedTest::class,
    FormFillMessagingInstrumentedTest::class,
    ShellNavigationInstrumentedTest::class,
    ShellBasicAuthInstrumentedTest::class,
    DiscoverySessionInstrumentedTest::class,
    NativeMetadataInstrumentedTest::class,
)
class ProviderCompatibilitySuite
