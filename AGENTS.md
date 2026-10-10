# Mobile Web Shell

Native Android launcher and chromeless runtime for web applications.

## Project status

Initial development is complete. The app works and is being tested on real
devices. Work now consists of bug fixes, new features and general maintenance.
Keep changes focused. Do not break existing behavior, stored data (Room schema
and profile state) or the security invariants in the spec without an explicit
decision. A bug fix should include a regression test where one is practical.

## Build

No Gradle wrapper — uses the system `gradle` command. Point `sdk.dir` in
`local.properties` (or `ANDROID_HOME`) at the Android SDK.

Checks to run before committing:

```sh
gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```
