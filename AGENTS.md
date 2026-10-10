# Mobile Web Shell

Native Android launcher and chromeless runtime for web applications.

## Project status

Initial development is complete. The app works and is being tested on real
devices. Work now consists of bug fixes, new features and general maintenance.
Keep changes focused. Do not break existing behavior, stored data (Room schema
and profile state) or the security invariants in the spec without an explicit
decision. A bug fix should include a regression test where one is practical.

## Specification and issues

The specification (requirements, architecture and acceptance procedures), test
evidence and process rules are kept in a separate steward repository (currently
the `steward/` directory). Code comments reference its sections as R§
(REQUIREMENTS.md) and A§ (ARCHITECTURE.md). Bugs, features and maintenance tasks
are tracked as issues in AWB workspace `mws`; the tests listed in an issue are
part of its definition of done.

`provider-compatibility.md` is the WebView provider compatibility matrix.
`scripts/generate-provider-floors.py` generates the provider floors in
`GeneratedProviderFloors.kt` from its `PASS` rows; rerun it and commit the result
after changing the matrix or the AndroidX WebKit version.

## Build

No Gradle wrapper — uses the system `gradle` command. Point `sdk.dir` in
`local.properties` (or `ANDROID_HOME`) at the Android SDK.

Checks to run before committing:

```sh
python3 scripts/check-security.py
python3 scripts/generate-provider-floors.py --check
node --test scripts/tests/*.cjs
gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

## Testing

You don't have access to test on a device or emulator. Ask the user to do that
if necessary. A change that affects WebView provider behavior or bumps the
AndroidX WebKit version needs a new provider qualification run before release
(see "Provider qualification" in the steward repository's `spec/ACCEPTANCE.md`).
