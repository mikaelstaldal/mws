# Provider qualification

`ProviderCompatibilitySuite` packages the existing provider-sensitive device tests.
Qualification requires all 37 suite tests plus the separate process-restart
phases below. A successful single-process run alone does not qualify a §15 entry.

Use the explicit class selection below for evidence runs. An unfiltered test
scan may discover both this suite and its member classes, running those tests
twice; verify test counts before interpreting an unfiltered CI report.

Build the debug app and test APKs from a clean, committed revision. For an unlisted
provider, use the existing `DEBUG_PROVIDER_OVERRIDE=true` debug build option;
release builds never permit this override. The operator can build, install and
execute the complete sequence with:

```sh
scripts/run-provider-qualification.sh
```

Set `ANDROID_HOME` or `local.properties` for the installed SDK. The runner enables
the debug override, captures build/device metadata and raw reports in a new
`provider-evidence-*` directory, stops the app before each phase, and aborts on a
failure, a required skip or an unexpected test count. It requires an attached
device and a clean tracked source revision. If a previous preparation failed,
run the explicit cleanup phase below before rerunning the runner.

The single-process suite can also be selected explicitly (this alone is insufficient):

```sh
adb shell am instrument -w -r \
  -e class nu.staldal.mws.ProviderCompatibilitySuite \
  -e sourceRevision SOURCE_COMMIT \
  nu.staldal.mws.test/androidx.test.runner.AndroidJUnitRunner
```

Retain the complete instrumentation output, including failures, assumption skips,
ignored tests and the final test count. No skipped or ignored required test can
count as a pass. Record the source commit, exact build command and override,
provider package/version, Android version/API, device/image, primary ABI, pinned
AndroidX WebKit version and test date with the raw reports in a version-controlled
evidence directory. The matrix must link to an immutable evidence revision.

Existing packaged coverage:

| Flow | Device tests |
| --- | --- |
| Profile isolation and deletion | `ProfileIsolationInstrumentedTest`, plus same-process isolation and fresh-process deletion probes in `RuntimeSelfTestInstrumentedTest` |
| Cleartext links, redirects, GET/POST forms and ungestured popup | `ShellNavigationInstrumentedTest` |
| Basic-auth request correlation and rejection | `ShellBasicAuthInstrumentedTest`, runtime callback probe |
| Exact-origin form-fill transport | Runtime form-fill probe and `FormFillMessagingInstrumentedTest` HTTPS top-level/same-origin/cross-origin frame checks |
| Pre-dispatch popup routing | Strict runtime probe and trusted listener with zero destination requests |
| Attached disposable discovery and superseded callbacks | `DiscoverySessionInstrumentedTest` |

The shell auth tests require initial top-level authentication and redirects, later
top-level/reload refusal, service-worker retirement across view replacement,
isolated iframe/image challenges and an overlapping
iframe-to-image navigation case. Keep the overlapping case in qualification:
waiting for the old challenge to finish would hide the provider's missing request
identity. A lookup for the new page with the old iframe's realm is a failure.
The cleartext listener counts received bytes separately from accepted TCP
connections; a speculative connection alone does not establish HTTP transmission.
Run all of `ShellNavigationInstrumentedTest`, including effective network policy,
loopback aliases, direct POST, POST-preserving 307 redirects, and the separate
non-loopback IPv4 flows. Native-socket calibration verifies listener reachability
before each address is tested. Record loopback and non-loopback results separately;
a skipped non-loopback test is missing evidence.

For a direct blocked POST, accept either the shell's "HTTPS required" error or
the unchanged, ready HTTPS fixture with no shell error. The provider can cancel
before the app's interception/error notification path. The JavaScript execution
marker remains mandatory, and both outcomes require zero listener bytes after
the observation period. A generic load error or a replaced document cannot count
as the retained-fixture outcome. This UI distinction does not waive any redirect
or transport assertion.

For a focused cleartext rerun:

```sh
adb shell am instrument -w -r \
  -e class nu.staldal.mws.ShellNavigationInstrumentedTest \
  nu.staldal.mws.test/androidx.test.runner.AndroidJUnitRunner
```

If effective policy permits any loopback alias (including the full 127/8 range or
bracketed IPv6), or a POST-preserving redirect sends bytes, treat the provider as
incompatible. Do not waive the failure or qualify it using initial-request
interception alone: redirect hops require a working transport policy.

The suite also checks actual gestured HTTPS GET routing in scope and out of scope,
gestured HTTP popups and prohibited `intent:` cleartext
fallback (actual touch input and user-activation marker), loopback WebSocket and
service-worker POST attempts, discovery HTTP redirects, same-site/cross-site
cookie traffic with enable/disable and retention, and all four native metadata
fetch classes' credentialless fallback and ignored Set-Cookie behavior. A native
fetch success is required alongside empty Cookie headers and an unused
unqualified cookie adapter. WebSocket/worker attempts rely on mixed-content and
network policy because WebView request interception does not cover them.

## Process-restart evidence

`RestartQualificationInstrumentedTest` is intentionally outside the suite.
Run its four methods separately with force-stop between them. Ordinary unfiltered
discovery skips these phase methods to avoid consuming the fixture in one process.
Explicit phase invocations must pass with no skips. Preparation registers
an actual worker at a saved local HTTPS port, flushes cookies in dedicated
profiles, interrupts production clear after its durable generation reservation,
and leaves a production origin-change journal at cursor zero. It also exercises
real loaded-profile clear/remove deferral, durable tombstones and an orphaned
discovery profile. The second phase deletes one retiring profile through production
clear and interrupts before its final configuration commit, then the process is
killed again. A 12-second
registry-write wait mirrors the existing deletion probe. Verification must use
a different process identity, reopen the same server port/origin, observe a
request from the persisted worker without registering it again, cancel the real
top-level auth challenge without MyPass lookup, and resume both maintenance
records through the production manager. Recovery requires deleted old profiles,
ACTIVE generation 1 (no renewed reservation) and absent old cookies after
recreating the deleted generation-0 names. Verification includes a worker-free
auth control that must attempt a lookup, samples retirement at the actual persisted
worker's network request, and confirms cancellation of its top-level handler. An unrelated
control profile must retain its flushed cookie across process death.

The fixture uses UUID profile names and an isolated configuration/maintenance
store under `noBackupFilesDir/qualification-restart`; it does not change saved
user apps. Run these phases before the ordinary suite, which may perform other
profile maintenance. If a phase fails, preserve its report, force-stop and run
cleanup before starting a new preparation. Cleanup of a failed run supplies no
qualification evidence. A saved port that cannot be rebound is a test failure;
do not silently substitute a new worker origin.

`scripts/run-provider-qualification.sh` is the reference for the exact phase
order and expected test counts. To clean up after a failed preparation:

```sh
adb shell am force-stop nu.staldal.mws
adb shell am instrument -w -r \
  -e class nu.staldal.mws.RestartQualificationInstrumentedTest#cleanupRestartFixture \
  nu.staldal.mws.test/androidx.test.runner.AndroidJUnitRunner
```

Review each report independently; a successful final invocation cannot erase
an earlier failure. Retain all five reports. The suite emits provider package,
exact version, model, Android/API, ABIs, pinned WebKit version and override metadata. `sourceRevision`
is a caller-supplied label, so also retain the build log and verify it matches
the APKs actually installed. The reported AndroidX WebKit version must match
`gradle/libs.versions.toml`.

If full-context discovery cookies remain unavailable, qualify the mandatory
credentialless fallback and keep that runtime feature disabled. Do not claim
full-context cookie support from ordinary CookieManager reads.

Only after every §15 flow has passing evidence, add a `PASS` matrix row. Run
`gradle :app:generateProviderCompatibilityTable :app:verifyReleaseProviderCompatibility`
and inspect the generated `GeneratedProviderFloors.kt` to confirm its package and
floor. A new entry is required whenever the pinned AndroidX WebKit version changes
(A§15).
