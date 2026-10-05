# End-to-end acceptance (R§17)

This is the traceability and execution procedure for all 47 criteria in
[REQUIREMENTS.md](REQUIREMENTS.md). A test named below supplies repeatable partial
or complete evidence; the device checks cover behavior that a unit test or an
isolated component test cannot observe. Record **pass, fail, or not run** for each
number. A passing build alone does not establish device acceptance.

## Run and evidence

Use a clean committed revision, a device with a qualified WebView provider (A§15),
and the debug and androidTest APKs built from that revision. Use a shared debug
signing key when testing MyPass. The debug provider override can exercise an
unlisted provider, but its results do not qualify a release provider. Record the
source revision, APK build command, device/API, provider package and exact version,
AndroidX WebKit version, test date, installed MyPass version/signature, and whether
the override was enabled. Keep raw test reports and a numbered result log. Never
put actual credentials or sensitive URL values in the log or screenshots.
Install both widget types before the unfiltered instrumentation run; its widget
and accessibility tests require actual widget instances.
Store the result log and raw reports in a dated `acceptance-evidence-YYYYMMDD/`
directory committed with the tested source revision. Begin with all 47 rows
marked **not run**; change each row only when its automated and manual evidence
has been collected. For a bug fix or feature, run at least the rows and
procedures for the affected criteria.

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/check-security.py
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r nu.staldal.mws.test/androidx.test.runner.AndroidJUnitRunner
```

An unfiltered instrumentation scan may run `ProviderCompatibilitySuite` and its
members twice. For provider qualification, use the explicit selection and process
restart phases in [provider qualification](#provider-qualification), and
check the required counts and skips there. Run the [controlled device procedures](#controlled-device-procedures)
when their rows below call for them. A failure or required skip is not a pass.

Use two controlled HTTPS origins A and B with a manifest page, a page without a
manifest, a two-link navigation sequence, Basic and HTML login pages, a download
endpoint, and a server request log. The controlled CA is trusted only by the
debug test build. For manual MyPass tests, use disposable credentials that are
unique to this run, and a test MyPass installation signed with the shared key.
Restart means force-stop and reopen the app; reinstall only when a step says so.
Reset test apps, widget instances and provider test fixtures before the next run.

## Criterion traceability

`J` means JVM test, `D` means instrumented device test, `M` means the numbered
manual procedure below. Test names are the class names under `app/src/test` or
`app/src/androidTest`; method names identify the relevant assertion where a
class covers several behaviors.

| R§17 | Existing automated evidence | End-to-end check |
| --- | --- | --- |
| 1 | J `EditorActivityTest`, `MainScreenTest`; D `ScaffoldInstrumentedTest` | M1 |
| 2 | J `ShellPresentationTest.insetsProtectNavigationAndKeyboardEvenInFullscreen` | M1 |
| 3 | J `ShellActivityTest.backNavigatesHistoryThenClosesTheShell` | M1 |
| 4 | J `EditorActivityTest`, `MainScreenTest` | M1 |
| 5 | J `ManifestProcessorTest`, `EditorDiscoveryTest.manifestDefaultsAndColorsAreSavedAtomically` | M2 |
| 6 | J `EditorDiscoveryTest.nonManifestTitleAndCommitSurviveLaterStageFailure`, `IconPipelineTest` | M2 |
| 7 | J `IconPipelineTest.storeUsesNoBackupDirectoryAndReadsPublishedRendition`, `SingleWidgetTest.storedIconRendersOfflineAndUnavailableUsesDefault` | M2, M5 |
| 8 | D `ShellBasicAuthInstrumentedTest.topLevelChallengeLooksUpExactUrlAndRealmAndSuppliesOnce` | M3 |
| 9 | D `ShellBasicAuthInstrumentedTest.crossOriginRedirectCannotInheritTheNavigation`; J `BasicAuthGateTest`, `NativeDiscoveryClientTest.basicCredentialIsNotSentProactivelyToSameOriginRedirectTarget` | M3 |
| 10 | D `FormFillMessagingInstrumentedTest.exactOriginMessagingFillsOnlyTopLevelDocumentOnce` | M3 |
| 11 | D `FormFillMessagingInstrumentedTest`, `ShellBasicAuthInstrumentedTest.iframeChallengeIsCancelledWithoutAnotherNavigation`; J `FormFillStateTest` | M3 |
| 12 | D `MyPassClientInstrumentedTest.timeoutAndCancellationLeaveOnlyNonSecretIdentityState`; J `MyPassRequestStateTest`, `MaintenanceStoreTest.backupRulesIncludeOnlyConfigurationDatabase`; security source check | M3, M4 |
| 13 | D `WidgetAcceptanceInstrumentedTest`; J `MultiWidgetTest` | M5 |
| 14 | J `SingleWidgetTest`, `IconPipelineTest` | M5 |
| 15 | J `SingleWidgetTest.activeRemovedMissingAndClearingTargetsRenderAndRouteSafely`; D `WidgetAcceptanceInstrumentedTest` | M5 |
| 16 | J `ManifestProcessorTest.malformedManifestIsIgnored`, `EditorDiscoveryTest.changedUrlImmediatelyUsesFallbacksAndOfflineIsRecoverable`, `ShellActivityTest.rendererCrashRebindsSameProfileOnRetry` | M2, M6 |
| 17 | D `ScaffoldInstrumentedTest`, `ShellNavigationInstrumentedTest`; J `ShellActivityTest.tlsFailureCancelsHandshakeAndShowsLocalError`, `activeAppBindsItsActiveGenerationAndLoadsLaunchUrl` | M6 |
| 18 | D `ProfileIsolationInstrumentedTest.sameOriginProfilesShareNoStateAndPersistAcrossRelaunch` | M7 |
| 19 | D `ProfileIsolationInstrumentedTest.disposableDiscoveryDataNeverAppearsInRuntimeProfile` | M7 |
| 20 | D `ProfileIsolationInstrumentedTest.deletingOneProfileLeavesTheOtherUntouched`, `RestartQualificationInstrumentedTest`; J `ProfileManagerTest` | M7 |
| 21 | J `ProviderCompatibilityTest.multiProfileIsMandatory`, `MainScreenTest.incompatibleProviderHasErrorEntryAndBlocksRowLaunch`; D `RuntimeSelfTestInstrumentedTest` | M8 |
| 22 | D `ShellNavigationInstrumentedTest.realGesturesRouteHttpsGetInScopeAndDelegateOnlyOutOfScope`; J `NewWindowRoutingTest`, `ShellActivityTest.openInBrowserIsOfferedOnlyForAllowedHttpsLinksAndTransfersNothingElse` | M9 |
| 23 | J `FormFillEngineTest.lateCredentialsAfterNavigationAndUnavailableResultsDoNotFill` | M3 |
| 24 | D `ShellBasicAuthInstrumentedTest.failedProbeCancelsEveryChallenge`; J `ShellActivityTest.missingMyPassCancelsChallengeAndShowsAuthenticationUnavailable` | M3 |
| 25 | D `ShellBasicAuthInstrumentedTest.iframeAndSubresourceChallengesAreCancelled`; J `BasicAuthGateTest`, `FormFillStateTest` | M3, M4 |
| 26 | D `ShellNavigationInstrumentedTest.cleartextLinksFormsAndRedirectsTransmitZeroBytes`, `NativeMetadataInstrumentedTest.metadataCleartextRedirectNeverOpensHttpDestination`; J `UrlPolicyTest` | M6 |
| 27 | D `ShellNavigationInstrumentedTest.outOfScopeHttpsStaysInTheShellAndDownloadsAreNotDelegated`; J `ShellActivityTest.originReplacementClosesOldShellAndMyPassUsesOnlyNewUrl` | M9, M3 |
| 28 | J `ConfigurationRepositoryTest.reorderSurvivesDatabaseReopenAndRejectsInvalidLists`, `MainScreenTest.reorderAccessibleCancelSaveAndRestart` | M10 |
| 29 | J `MultiWidgetTest.instanceSelectionsKeepCanonicalOrderAndClearingRows`, `ConfigurationRepositoryTest.appendRemoveAndRenameKeepCanonicalOrder` | M10, M5 |
| 30 | J `ProfileManagerTest.clearClosesShellDeletesRetiringGenerationAndItsPermissions`, `EditorActivityTest.maintenanceRequiresConfirmationAndCancelPreservesRecord`; D `RestartQualificationInstrumentedTest` | M7 |
| 31 | D `ProfileIsolationInstrumentedTest.deletingOneProfileLeavesTheOtherUntouched` | M7 |
| 32 | D `ProfileIsolationInstrumentedTest.thirdPartyCookieSettingIsPerProfile`, `cookieTrafficHonorsCrossSiteToggleAndRetainsProfileCookies`; J `EditorActivityTest.thirdPartyCookiesRequireAcknowledgement` | M7 |
| 33 | D `ProfileIsolationInstrumentedTest.cookieTrafficHonorsCrossSiteToggleAndRetainsProfileCookies` | M7 |
| 34 | J `UrlPolicyTest.normalizesTypedDnsUrls`, `EditorActivityTest.httpUrlsFailInlineValidation` | M1 |
| 35 | J `EditorActivityTest.confirmedOriginChangeStoresNewUrlAndRetiresProfile`, `AppEditorTest.originChangeDropsOldIconButSameOriginKeepsIt`; D `RestartQualificationInstrumentedTest` | M2, M7 |
| 36 | D `MyPassClientInstrumentedTest`; J `MyPassRequestStateTest` | M3, M4 |
| 37 | J `ShellActivityTest.downloadsIncludingBlobAndDataAreUnsupportedAndNeverDelegated`; D `ShellNavigationInstrumentedTest.outOfScopeHttpsStaysInTheShellAndDownloadsAreNotDelegated` | M9 |
| 38 | J `ProfileManagerTest.missingSentinelNormalizesRestoreWithoutDeletingProfiles`, `ConfigurationRepositoryTest`; D `ConfigurationMigrationTest` | M11 |
| 39 | J `MainScreenTest.clearingRowKeepsPositionAndExplainsTap`, `MultiWidgetTest.instanceSelectionsKeepCanonicalOrderAndClearingRows`; D `WidgetAcceptanceInstrumentedTest` | M5, M7 |
| 40 | D `ShellTaskInstrumentedTest.eachAppHasOneRecentsTaskAndRelaunchBringsItForward` | M12 |
| 41 | J `ShellActivityTest.pullToRefreshIsBlockedUntilLoadedAndWheneverPageCanScrollUp`, `horizontalSwipeAtPageTopIsLeftToThePage`, `pullToRefreshFollowsTheRemainingFingerAndYieldsToAClaimingChild`, `explicitReloadPreparesFormFillButCannotRearmBasicAuthentication` | [pull-to-refresh device procedure](#pull-to-refresh-procedure-r92) |
| 42 | J `ShellActivityTest.allowlistedSchemeIsDelegatedOnlyAfterConfirmationEveryTime`, `bankIdAppLinkIsDelegatedAfterConfirmation`, `dangerousAndUnknownSchemesAreBlockedAndNeverPassedToAndroid`; `NavigationPolicyTest.bankIdAppLinkNeedsAnAutostartTokenAndMainFrame`; D `ShellNavigationInstrumentedTest.blockedAndUngesturedSchemesAreNeverPassedToAndroid` | M9; verify a real BankID login on a device, including a relying party with a non-null return URL (BankID may open the default browser instead of returning to MWS) |
| 43 | J `ShellPresentationTest.wcagReferenceValuesAndIconSelection`, `paintsManifestBackgroundBeforeWebViewAndDoesNotChangeNativeWindow` | M12 |
| 44 | D `DiscoverySessionInstrumentedTest.metaRefreshPageIsFollowedToItsSameOriginTarget`, `scriptRedirectPageIsFollowedToItsSameOriginTarget`; J `DiscoveryAttemptTest.redirectPageBeforeSnapshotIsFollowedToTheNextSameOriginCommit`, `followingIsBoundedAndNeverLeavesCrossOriginOrFailedDocuments`, `DiscoveryDomTest.onlyHttpsRefreshTargetIsKept`; script test `scripts/tests/discovery-dom.test.cjs` | M2 |
| 45 | J `MainScreenTest.quickLaunchValidatesAndOpensWithoutAddingAnApp`, `ShellActivityTest.adHocLaunchUsesDisposableProfileAndLeavesNoConfiguration`, `adHocIntentRejectsInvalidUrls`, `adHocLaunchNeverRequestsMyPass` | M1, M7 |
| 46 | J `ExternalLinkTargetTest`, `ShellActivityTest.externalLinkOpensMatchedTaskAndReusesItsWebView`, `externalLinkInitialLaunchAndExpiredToken`, `externalLinkOutsideTargetScopeFallsBackToBrowser`, `externalLinkTokenCannotBeUsedForAnotherApp`, `externalLinkWithSensitiveParameterNeedsConsentBeforeCreatingTask`, `externalLinkDuringRendererCrashLoadsInRecoveredProfile`; D manual device check | M9 |
| 47 | J `EditorActivityTest.currentPageUrlIsCopiedIntoDraftAndSavedOnlyOnSave`, `currentPageUrlActionRequiresLiveCommittedPage`, `currentPageUrlActionHidesWhenLivePageBecomesUnavailable`; `ShellActivityTest.currentUrlRequiresCommittedIdlePageAndBackMarksNavigationPending`; `LiveShellRegistryTest` | M1 |

## Manual device procedures

For each procedure, record the relevant R§17 numbers and observed result. Use
only disposable sites and accounts. If a step cannot be run, mark its criteria
**not run** and state why. Controlled fixture tests can supply the server log,
but they do not replace the real launcher, Recents, MyPass and backup observations.

### M1 — basic app flow (1–4, 34, 45, 47)

Add `example.com` with a name; confirm the stored URL is HTTPS. Try
`http://localhost:8080` and another HTTP URL; both must fail inline validation.
Add a controlled HTTPS page without a manifest, save it and launch from the main
list. Check full-screen content and the bottom system navigation inset in portrait
and landscape. Navigate twice in the page; Back returns one page at a time, then
closes the shell. Long-press the list row, edit its name, save, then reopen and
remove it. Confirm the row disappears.
Enter `example.com/path` in the main-screen URL field and open it without saving.
Confirm the shell loads HTTPS directly, Back works, no entry appears in the list,
and MyPass is never queried. HTTP input must show an error without opening a shell.
While a saved shell remains live, navigate to another HTTPS page and return to the
main screen. Long-press its row and choose **Use current page URL** in the editor.
Check that Cancel preserves the old URL, then repeat and Save to store the new one.
Repeat after a same-document History API URL change. The action must be absent
before a first page commit and after the shell closes.

### M2 — discovery, icons and failures (5–7, 16, 35, 44)

Add the manifest fixture and inspect proposed name, start URL, scope, colors and
selected icon before saving. Add a page without a manifest: check title and
favicon; repeat with no favicon and check the default icon. Disconnect network
and verify saved icons on the main list and both widget types. Try a malformed
manifest, missing favicon and offline Refresh; the editor stays usable and a
saved app keeps its prior icon. Change only the path and confirm icon and web
session remain; change the origin, accept confirmation and verify old icon and
session are gone. If discovery finds a new icon, verify it replaces the fallback.
Add a site whose start page is a zero-delay meta refresh to a same-origin page
(for example `https://my.charge.space/`) and check that the proposed name and
icon come from the target page.

### M3 — authentication behavior (8–11, 23–25, 27, 36)

Use the controlled server request log and a MyPass test installation that records
lookup URL and realm without recording returned secrets. Give the Basic page a
unique credential. Open it: expect one lookup with the exact top-level URL and
realm and one answer to that challenge. Inspect server requests before the
challenge and after a cross-origin redirect: no proactive Authorization header
or inherited credential. Trigger an iframe challenge, TLS failure and HTTP page;
expect no lookup or credential transmission. Remove the Basic entry from MyPass:
expect a cancelled challenge and an authentication error; another app still runs.
On a secure HTML login page, verify the exact committed URL is queried, fields
are filled once and the form is not submitted. Navigate or reload before a delayed
result returns, and try an iframe login: neither may receive the stale credential.
Remove the form entry: manual typing and submission must still work. Exercise
MyPass absence, uninstall during request, permission failure, cancellation,
malformed response and timeout using the test MyPass controls; each must leave a
usable or clearly failed page without a crash. For an out-of-scope HTTPS link
that stays in the shell, trigger a later challenge and check the destination's
exact URL in the lookup.

### M4 — secret lifetime inspection (12, 25, 36)

Use a fresh high-entropy marker as username/password, never a real account. Run
separate successful, cancelled, timed-out, navigation-race, TLS-error and close
flows for both Basic auth and form fill. After each, leave the page, force-stop
MWS and inspect `logcat`, clipboard content, recent tasks, widget state, saved
instance state after rotation/recreation, outgoing intents, and the app's backup
payload. Search only in a protected local evidence workspace for the exact marker,
its URL-encoded form, and base64 of the exact `username:password` pair used for
Basic authentication (including the value in an Authorization header);
record presence/absence and inspected artifact names, then delete the artifacts.
The marker may appear at the controlled HTTPS server and transiently inside
MyPass or the active page during the successful flow; it must not appear in MWS
logs, clipboard, intents, widgets, saved state, configuration or backup. Use
`run-as nu.staldal.mws` on the debug build to inspect the configuration database,
backup-eligible files, and all MWS private files including `app_webview*` profile
directories after each flow. Record inaccessible paths as **not run**, not pass.
Classify any provider-managed HTTP-auth cache separately under the A§14 exception;
do not silently count an unexplained hit as a pass.
`MyPassClientInstrumentedTest` and the security source check cover the code paths;
this run verifies the whole installed-app boundary.

### M5 — widgets (7, 13–15, 29, 39)

Install one resizable multi-app widget and two single-app instances; select
different apps. Resize, tap each visible row, rename and reorder an app, remove
one, then reboot and replace the package. Check names/icons, canonical ordering,
retained subsets and launch targets after each change. Disconnect network to
check saved icons. Tap a stale single-app widget after removal: it must show the
main screen unavailable state, not launch the old URL. Run the controlled
[widget acceptance procedure](#widget-procedure), including its optional
launcher hold, to verify a CLEARING row stays in place and cannot launch.

### M6 — failures and transport (16, 17, 26)

Use controlled endpoints for a network failure, renderer termination, bad TLS
certificate, mixed active content, and HTTP link/redirect/form/discovery URL.
Confirm a recoverable shell or editor error and Retry where offered. Confirm
"HTTPS required" for blocked top-level HTTP attempts and zero HTTP request
bytes in the listener. For production settings, configure the shared release
signing identity described in `app/build.gradle.kts`, run
`gradle :app:assembleRelease`, install that signed APK on a separate acceptance
device, and repeat the TLS and mixed-content cases. On a desktop attached to
that device, check `chrome://inspect`: the release WebView must not be offered
for remote debugging. Load a provider-supported Safe Browsing threat-test URL,
record the exact test URL and provider response, and verify the page is blocked
when Safe Browsing is available. Check mixed active content with a controlled
HTTP listener and require zero request bytes. Record provider and API behavior;
an unsigned/uninstalled release build is **not run** for these checks. Use the
[provider qualification](#provider-qualification) procedure for
POST, WebSocket, service-worker and non-loopback transport cases.

### M7 — isolation and maintenance (18–20, 30–33, 35, 39, 45)

Create entries A1 and A2 for the *same* controlled origin and open both at once.
In A1 set a login cookie, local/session storage, IndexedDB, Cache Storage, a
service worker, HTTP cache entry and a site permission; verify each is absent in
A2, including server-observed Cookie/Authorization headers. Set distinct values
in A2, close and relaunch each, and verify its own values persist. Run discovery
with a disposable marker before first launch; verify the marker is absent in its
runtime profile. Enable third-party cookies for A1 only after acknowledging the
warning; use cross-site server logs to confirm A2 remains blocked. Disable again:
future third-party requests must be blocked, the UI must explain retention, and
stored cookies remain until clear. Clear A1 after confirmation: its next launch
has none of the old state; A2 retains all its values. Verify A1's metadata, icon,
order, widgets and MyPass entry are unchanged. Put fresh state in A1, then
remove it: it disappears at once and A2 remains intact. Inspect its retired
profile directory through `run-as` and confirm it has been deleted; re-add the
same origin as a new app and verify it has no prior cookies or storage. Open an
ad-hoc shell for the same origin: it must see neither app's state, and its
profile must be retired after closing its shell. Repeat clear/removal after a
provider-compatibility failure
using the restart qualification fixture; native removal is immediate and pending
web-data deletion completes after a compatible restart. Inspect the retired
profile directory again after recovery and verify the other profile still has
its state. Capture the CLEARING row
position and disabled state in main list and widget before completion.

### M8 — incompatible provider (21)

On a test device or controlled debug provider override lacking `MULTI_PROFILE`
or failing the isolation probe, attempt to launch a configured app. Expect an
incompatibility explanation and no bound shared WebView. Do not use this device
for the other acceptance checks; record provider identity and failed probe.

### M9 — navigation and delegation (22, 26–27, 37, 42, 46)

From a controlled HTTPS page, tap in-scope and out-of-scope `target="_blank"`
links. The former opens in the shell; an unmatched latter opens in the browser with no
MyPass credential or app data in its intent. Run an ungestured `window.open()`:
no window opens. Long-press an allowed HTTPS link and check **Open in browser**;
it must be absent for HTTP or blocked schemes. Follow an ordinary out-of-scope
HTTPS link and verify it stays in the shell. Try HTTPS, `blob:` and `data:`
downloads: each reports unsupported with no external app launch. Try `mailto:`,
`tel:`, `sms:`, `geo:` and `market:`: each requires fresh confirmation before
delegation. Cancel each once. `intent:` must be blocked, including fallback URLs.
Use an intent interceptor or activity log to check no extra data or credentials
leave MWS. Controlled server logs must show no HTTP request bytes.
Configure a second app with a scope matching an out-of-scope new-window link.
Open it while its shell is already running, then from a fresh task. Both should
load the link in the second app's profile; the first should retain its live
WebView and authentication. A link outside every configured scope still uses the
browser. Long-press **Open in browser** on a matching link still uses the browser.

### M10 — order (28–29)

With at least four apps and two multi-app widget subsets, move an app by drag
and by the accessible Move actions. Verify list and widget order, cancel a
reorder and check it rolls back, then save a reorder. Restart, rename/edit an
app and add another: order survives and the new app is appended. Each widget
retains its selected IDs while showing them in canonical order.

### M11 — restore (38)

Back up a debug fixture containing ordered apps, icons, widgets and site data.
Restore onto a clean install with a supported provider. Before opening any app,
inspect records through a debug/test build: all are ACTIVE with fresh profile
generations and default icons. Launch and verify no previous cookie, storage or
site permission remains. Authentication must use the currently available MyPass
entry; removing it before launch must not restore a credential from backup.
Record backup/restore commands and results without storing secrets.

### M12 — tasks and presentation (40, 43)

Open two apps simultaneously and inspect Recents: each has its own entry.
Relaunch one from the list and verify its live page comes forward without
reload. On a manifest page with distinct `theme_color` and
`background_color`, inspect system bars, task description and icon contrast in
light/dark mode and the background before first paint. Return to the main and
editor screens; neither takes the web app's colors.

## Controlled device procedures

### Widget procedure

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

### Accessibility procedure (R§14)

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

### Pull-to-refresh procedure (R§9.2)

Run on a supported provider with a configured HTTPS app containing a long page and a login form.

1. At scroll top, drag downward and release past the refresh threshold. Verify the current page reloads and Back history is unchanged.
2. Scroll down, then drag downward. Verify the page scrolls normally without reloading. A gesture starting below the top must not become a refresh when it reaches the top; begin a new gesture there.
3. At the top, try an upward drag, a horizontal drag, a short downward drag, and a cancelled gesture. Verify none reloads.
4. On a page at scroll top with a horizontally scrolling element (carousel, side-scrolling app), swipe sideways with some downward drift. Verify the element scrolls as smoothly as in Chrome on the same device and the refresh indicator never appears. Repeat steps 1 and 3 on that page to confirm refresh still works where the page does not claim the gesture.
5. Complete a form-fill attempt, then refresh. Verify the reloaded document permits a fresh attempt and stale credential results cannot fill it.
6. For an HTTP Basic-auth site, refresh and verify no new MyPass Basic-auth lookup is initiated. Cached authentication may continue to work; a new challenge is cancelled.
7. Verify refresh is unavailable during initial loading, on an error overlay, and during Retry recovery. Rotate a loaded shell and verify pull-to-refresh still works without an automatic reload.

JVM coverage in `ShellActivityTest` verifies scroll-top gating, gesture interception, horizontal axis locking, multi-touch pointer handoff, honouring a child's disallow-intercept request, reload dispatch, form-fill preparation, and Basic-auth retirement on reload. Actual WebView scrolling and provider/network behavior require this device pass.

## Provider qualification

A release accepts a WebView provider package only at or above a version with a
`PASS` row in the A§15 matrix. The build derives these floors from the matrix,
and it fails if a `PASS` row's AndroidX WebKit version differs from the pinned one.
Requalify when:

- the AndroidX WebKit version in `gradle/libs.versions.toml` changes;
- a new provider package, or a version below the current floor, must be supported;
- a change affects profile handling, transport policy, Basic auth, form-fill
  messaging, new-window routing or discovery in a way a device could observe
  differently from JVM tests.

Newer versions of a qualified package need no new run.

### Run

On a device with the target provider, from a clean committed revision, with
`sdk.dir` or `ANDROID_HOME` set:

```sh
scripts/run-provider-qualification.sh
```

The runner builds with the debug-only `DEBUG_PROVIDER_OVERRIDE=true` so that an
unlisted provider can run (release builds never permit it). It records build
and device metadata, runs the four `RestartQualificationInstrumentedTest` phases
and then `ProviderCompatibilitySuite` (37 tests), with a force-stop before each.
It aborts on a failure, a required skip or an unexpected test count. Reports go
to a new `provider-evidence-*` directory. An unfiltered instrumentation run can
execute the suite and its member classes twice and skips the restart phases, so
it does not count as qualification.

If a phase fails, keep its report, then run cleanup before starting again:

```sh
adb shell am force-stop nu.staldal.mws
adb shell am instrument -w -r \
  -e class nu.staldal.mws.RestartQualificationInstrumentedTest#cleanupRestartFixture \
  nu.staldal.mws.test/androidx.test.runner.AndroidJUnitRunner
```

### Adding a matrix row

- Review every report independently; a later success does not erase an earlier
  failure. A skipped or ignored required test is missing evidence, not a pass.
  This includes the non-loopback cleartext tests.
- The suite reports the provider package and version, device, Android/API, ABIs,
  WebKit version and override. `sourceRevision` is only a label, so check the
  build log against the installed APKs. The WebKit version must match
  `gradle/libs.versions.toml`.
- Any cleartext bytes reaching a listener, including through a loopback alias or
  a POST-preserving redirect, make the provider incompatible. Do not waive it.
- Full-context discovery cookies stay disabled unless separately qualified. The
  credentialless fallback is what the suite qualifies.
- Commit the evidence directory, then add the A§15 row linking to that commit.
  Run `gradle :app:generateProviderCompatibilityTable :app:verifyReleaseProviderCompatibility`
  and check the package and floor in the generated `GeneratedProviderFloors.kt`.
