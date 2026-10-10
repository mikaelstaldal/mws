package nu.staldal.mws

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/** Widget tests require manually configured widgets belonging to the test variant. */
internal fun requireWidgetTestsEnabled() {
    assumeTrue(
        "Widget tests are opt-in: set the runWidgetTests instrumentation argument to true",
        InstrumentationRegistry.getArguments().getString("runWidgetTests") == "true"
    )
}
