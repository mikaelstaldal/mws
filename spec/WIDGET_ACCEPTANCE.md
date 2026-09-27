# Controlled widget acceptance

Use this procedure for the states that are hard to observe during normal device testing
(R§12.1, R§12.3, A§13 and A§14). It supplements the manual instance, resize, reorder,
edit/removal, offline, reboot and package-replacement checks.

Build the app and instrumentation APKs with the development provider override:

```bash
DEBUG_PROVIDER_OVERRIDE=true gradle :app:assembleDebug :app:assembleDebugAndroidTest
```

Use a device whose runtime isolation/deletion probes pass. Keep at least one multi-app
widget installed. The test fails rather than skipping if there is no installed widget
or the runtime provider gate blocks access.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e holdSeconds 60 \
  -e class nu.staldal.mws.WidgetAcceptanceInstrumentedTest \
  nu.staldal.mws.test/androidx.test.runner.AndroidJUnitRunner
```

The suite has two test methods covering these cases:

- Missing records: temporarily delete the selection record for the installed multi-app
  widget with the lowest ID and check that its production collection items contain
  all configured apps in canonical order. Restore the original record on completion.
- Maintenance and configuration: append three temporary apps, reserve a new generation
  for the middle one without completing the clear, and select only those apps in the
  same widget. Check row ordering, the disabled maintenance label, absence of a launch
  ID, rejection by the production profile-attachment gate, and the disabled, unchecked
  configuration choice. This shares the first test method with missing-record fallback.
- Record cleanup: use an isolated maintenance database and in-memory configuration
  database. Check provider `onDeleted`, the real app-removal journal's selection pruning,
  native startup pruning of dangling selections, and widget reconciliation against the
  real installed widget IDs. Check that reconciliation retains installed widget records
  and leaves records belonging to the other provider untouched.

When the terminal prints **Fixture ready**, press Home and find the widget showing:

1. Widget test: before
2. Widget test: clearing — Clearing site data…
3. Widget test: after

During the 60-second hold, confirm the middle row stays in place and shows the label.
Tap it and confirm no web app opens. Return to Home if needed. The hold is optional:
without `holdSeconds` the suite runs only its automatic assertions. The accepted range
is 0–120 seconds.

Do not edit apps or widget selections during this test. Its `finally` cleanup removes
only its temporary apps, restores the chosen widget's prior selection record (or its
absence), refreshes the widgets, and deletes the isolated database files. Let the run
finish so cleanup can complete. A snapshot containing only widget/app IDs and selection
metadata is saved atomically in backup-excluded storage before changing the live fixture.
If instrumentation is interrupted, rerunning the first test restores that snapshot and
removes leftover fixture apps before starting another fixture. The fixture's clear state is synthetic; no user app's
profile state or site data is changed.

Report the instrumentation output (expect **OK (2 tests)**), the observed maintenance
row behavior in One UI, and whether the original widget selection was restored. Automatic
assertions check the device collection items, rendered rows, configuration activity and
storage paths; only the manual hold checks the actual launcher's rendering and tap handling.
Neither these tests nor the development override qualify a release WebView provider.
