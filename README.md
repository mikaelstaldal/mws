# Mobile Web Shell

Mobile Web Shell is an Android launcher for the web apps you choose. Save an HTTPS address, then open it in a full-screen Android System WebView without browser tabs, an address bar, or navigation controls. Each saved app has its own web data. Web App Manifest details improve its name, icon, colors, and launch address when available, but a manifest is not required.

See the [project website](https://mikaelstaldal.github.io/mws/) for features and [user instructions](https://mikaelstaldal.github.io/mws/#guide).

The website is published from `docs/` by [the Pages workflow](.github/workflows/pages.yml). Repository maintainers need to select **GitHub Actions** as the Pages build source in the repository settings before its first deployment.

## Install from source

There is currently no published APK or Play Store release. To build and install a debug APK, you need Android 12 or later on the device, an Android SDK with platform 36 and platform tools, JDK 21, and a system `gradle` command. This repository has no Gradle wrapper.

1. Clone the repository and set `sdk.dir` in an untracked `local.properties` file:

   ```properties
   sdk.dir=/absolute/path/to/Android/Sdk
   ```

2. Build the APK:

   ```sh
   gradle :app:assembleDebug
   ```

3. Enable USB debugging, connect the device, and install it:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

Open **Mobile Web Shell** from the Android launcher. The installed WebView provider must support isolated profiles and pass the app's compatibility check. If it does not, the app shows a reason and update options; saved apps cannot launch until compatibility is restored. The [provider compatibility matrix](provider-compatibility.md) records qualified versions.

### Optional MyPass integration

Mobile Web Shell can request credentials from [MyPass](https://github.com/mikaelstaldal/mypass-android) for eligible HTTP Basic challenges and conventional login forms. The app works without MyPass; manual login remains available. The two apps must share the appropriate signing identity for credential requests to work. See the [MyPass integration contract](https://github.com/mikaelstaldal/mypass-android/blob/main/INTEGRATION.md) and the signing settings in `app/build.gradle.kts`. A build that uses the default Android debug key is suitable for trying the shell, but it does not establish a shared signing identity with a separately signed MyPass installation.

## Use the app

Tap **Add web app**, enter an HTTPS URL and a name, review any discovered details, then tap **Save**. A site needs no manifest to be saved. Tap its row to open it; long-press to edit it. Use **Reorder** to change the list order. In an open web app, pull down from the top of the page to reload; Android Back moves through page history and then closes the shell.

The editor can clear one app's cookies and site data or remove the app. Both actions ask for confirmation. You can also add the multi-app list widget or a single-app launcher widget from your Android launcher's widget picker. The [user guide](https://mikaelstaldal.github.io/mws/#guide) covers these tasks and troubleshooting.

Only HTTPS sites can be saved or loaded, including local sites. Mobile Web Shell is intended for trusted web apps, and it does not manage downloads or act as a general browser. Sensitive-looking URL parameters require confirmation because saved URLs can be included in device backup or transfer.

## Develop and test

Run the local build, unit tests, lint, security source check, and JavaScript helper tests with:

```sh
python3 scripts/check-security.py
node --test scripts/tests/*.cjs
gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Instrumented tests require a connected device or emulator. `scripts/run-provider-qualification.sh` runs the WebView provider qualification on a connected device. The specification, acceptance procedures and test evidence are kept in a separate project administration repository.

## License

Copyright 2026 Mikael Ståldal.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
