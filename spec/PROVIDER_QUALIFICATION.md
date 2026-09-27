# Provider qualification

`ProviderCompatibilitySuite` packages the existing provider-sensitive device tests.
It is an evidence entry point, not yet a complete qualification suite. A successful
run alone does not qualify a §15 matrix entry.

Use the explicit class selection below for evidence runs. An unfiltered test
scan may discover both this suite and its member classes, running those tests
twice; verify test counts before interpreting an unfiltered CI report.

Build the debug app and test APKs from a clean, committed revision. For an unlisted
provider, use the existing `DEBUG_PROVIDER_OVERRIDE=true` debug build option;
release builds never permit this override. Install both APKs on the test device,
then run:

```sh
adb shell am instrument -w -r \
  -e class nu.staldal.mws.ProviderCompatibilitySuite \
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
| Exact-origin form-fill transport | Runtime form-fill probe |
| Pre-dispatch popup routing | Strict runtime probe and trusted listener with zero destination requests |
| Attached disposable discovery and superseded callbacks | `DiscoverySessionInstrumentedTest` |

The shell auth tests include isolated iframe/image challenges and an overlapping
iframe-to-image navigation case. Keep the overlapping case in qualification:
waiting for the old challenge to finish would hide the provider's missing request
identity. A lookup for the new page with the old iframe's realm is a failure.
The cleartext listener counts received bytes separately from accepted TCP
connections; a speculative connection alone does not establish HTTP transmission.
Run all of `ShellNavigationInstrumentedTest`, including effective network policy,
loopback aliases, direct POST, POST-preserving 307 redirects, and the separate
non-loopback IPv4 flows. Native-socket calibration verifies listener reachability
before each address is tested. Record loopback and non-loopback results separately;
a skipped non-loopback test is missing evidence. The original 872-byte loopback
POST failure does not establish a global provider bypass.

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

Also require zero-byte loopback tests for `ws://` WebSockets and service-worker
HTTP POST fetches. WebView request interception does not cover those transports;
they rely on network policy. They remain open qualification items. Ungestured
`window.open` and `_blank` form passes prove refusal only, not gestured window
probe protection.

Before qualification, add device coverage for the remaining cases: gestured
cleartext popups and external-intent cleartext fallback; discovery cleartext
redirects; the four native metadata fetch classes' credentialless fallback and
ignored Set-Cookie behavior; third-party cookie decisions across same-site and
cross-site requests; and profile maintenance resumed after process death with
durable journals and the real provider. Host tests of native discovery and
maintenance support these checks but do not establish provider behavior.

If full-context discovery cookies remain unavailable, qualify the mandatory
credentialless fallback and keep that runtime feature disabled. Do not claim
full-context cookie support from ordinary CookieManager reads.

Only after every §15 flow has passing evidence, add a `PASS` matrix row. Run
`gradle :app:generateProviderCompatibilityTable :app:verifyReleaseProviderCompatibility`
and inspect the generated `GeneratedProviderFloors.kt` to confirm its package and
floor. Until then keep the matrix blocked and the release verification failing.
