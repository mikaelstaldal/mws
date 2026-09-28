# Mobile Web Shell

Native Android launcher and chromeless runtime for web applications.

## Project status

Initial development is complete. The app works and is being tested on real
devices. Work now consists of bug fixes, new features and general maintenance.
Keep changes focused. Do not break existing behavior, stored data (Room schema
and profile state) or the security invariants in the spec without an explicit
decision. A bug fix should include a regression test where one is practical.

## Specification

- `spec/REQUIREMENTS.md`: functional requirements and acceptance criteria (what the app must do).
- `spec/ARCHITECTURE.md`: technical design, constraints, data model, test strategy and the provider compatibility matrix (how it is built).
- `spec/ACCEPTANCE.md`: criterion-to-test traceability, device acceptance procedures and WebView provider qualification.

These documents describe the current behavior of the app. When a bug fix or
feature changes requirements or architecture, update them in the same commit.
A new acceptance criterion needs a traceability row in `ACCEPTANCE.md`.

## Issues

Bugs, features and maintenance tasks are tracked as issues in AWB workspace
`mws`. Issues reference the relevant spec sections (R§ = REQUIREMENTS.md,
A§ = ARCHITECTURE.md). The tests listed in an issue are part of its definition of
done.

## Build

No Gradle wrapper — uses the system `gradle` command. Point `sdk.dir` in
`local.properties` (or `ANDROID_HOME`) at the Android SDK.

Checks to run before committing:

```sh
python3 scripts/check-security.py
node --test scripts/tests/*.cjs
gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

## Testing

You don't have access to test on a device or emulator. Ask the user to do that
if necessary. A change that affects WebView provider behavior or bumps the
AndroidX WebKit version needs a new provider qualification run before release
(see "Provider qualification" in `spec/ACCEPTANCE.md`).
