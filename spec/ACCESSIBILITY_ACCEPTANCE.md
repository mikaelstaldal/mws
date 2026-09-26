# Native accessibility acceptance (R§14)

Run after installing debug and androidTest APKs. Install a single-app widget and a
multi-app widget first. The shell-error case needs a WebView provider supporting
multiple profiles; the test uses an isolated repository and a controlled HTTPS
connection failure. Widget tests fail explicitly if their prerequisite is missing.

```
gradle testDebugUnitTest lintDebug compileDebugAndroidTestKotlin
gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=nu.staldal.mws.AccessibilityInstrumentedTest
```

The Espresso suite explicitly runs the AccessibilityChecks root assertion, with
its default error severity, for empty/populated/reorder main screens, the editor,
the editor's actual cookie confirmation, provider/shell errors, and both widget
configuration screens. It supplies fixture apps for populated screens. Lint's
HardcodedText error checks resource strings; app names and URLs remain user data.

Repeat device acceptance in light and dark themes, with default and 200% text:

- With TalkBack, traverse each screen in visual order. Check headings, field hints,
  buttons, app names/URLs and maintenance states. Each app row should be one stop;
  reorder rows offer Move up/Move down. Decorative icons should not add stops.
- Confirm text remains readable, native text contrast is sufficient, and scrolling
  makes all controls reachable on a small screen and in landscape.
- Exercise URL/name validation, discovery, loading, save/clear failures, and shell
  Retry. Check status announcements occur as states change without repeating on
  every render, and that a visible error is still available when revisited.
- Open Clear/Remove, sensitive-URL and origin-change dialogs and check their full
  messages and action labels. Cancel must return focus to a sensible location.
- Check widget All/Selected modes and active/CLEARING choices, including long app
  names. Controls must have at least 48 dp touch targets.

Automated results do not replace these TalkBack, text-scaling and contrast checks.
Record device/provider, theme, text size and results in the issue before closure.
