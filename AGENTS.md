# Mobile Web Shell

Native Android launcher and chromeless runtime for web applications.

## Build

No Gradle wrapper — uses the system `gradle` command. Point `sdk.dir` in
`local.properties` (or `ANDROID_HOME`) at the Android SDK.

Checks to run before committing:

```sh
gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```
