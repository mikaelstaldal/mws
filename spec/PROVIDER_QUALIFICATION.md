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
