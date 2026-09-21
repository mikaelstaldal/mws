# Mobile Web Shell — Product and Technical Specification

Status: Draft 1  
Platform: Native Android  
Minimum supported Android version: Android 8.0 (API 26)  
Application name: Mobile Web Shell

## 1. Purpose

Mobile Web Shell is a native Android launcher and full-screen runtime for web applications. It lets a user save web applications by URL and open them in the Android system WebView without normal browser controls such as an address bar, tabs, menus, or navigation buttons.

The application accepts HTTPS URLs subject to this specification's security policy and the capabilities of the installed Android System WebView. It accepts plain HTTP only on a loopback/localhost origin. A Web Application Manifest is optional. When one is available, Mobile Web Shell uses its metadata to improve the saved app's name, icon, theme, launch URL, scope, and display experience.

Mobile Web Shell is not a general-purpose browser. It is a user-configured container for trusted web applications.

## 2. Goals

- Provide a simple list from which configured web apps can be launched.
- Display each web app in a dedicated, chromeless system WebView.
- Support Android system Back navigation within the web app.
- Discover and locally retain web app metadata and icons.
- Optionally supply credentials for HTTP Basic authentication or a conventional HTML login form.
- Expose both a multi-app list widget and a single-app launcher widget.
- Keep credentials protected at rest and prevent their transmission in insecure contexts.
- Behave predictably for sites that do not provide PWA metadata.

## 3. Non-goals

- General browser features, including editable address bars, tabs, bookmarks, browsing history UI, downloads management, or incognito mode.
- Synchronization of configurations or credentials between devices.
- Acting as the Android default browser or handling arbitrary links globally.
- Bypassing CAPTCHAs, multi-factor authentication, passkeys, federated identity flows, or site-specific anti-automation controls.
- Guaranteeing automatic form login on every website. Form login is best-effort because HTML forms and client-side applications vary widely.
- Installing Progressive Web Apps as Android packages.
- Implementing a separate browser or web rendering engine.

## 4. Terminology

- **Configured app**: A web application entry saved by the user.
- **Shell**: The chromeless Android screen containing a WebView.
- **Resolved launch URL**: The URL actually opened after configuration metadata is applied. This is the effective manifest `start_url` when it is valid and within the app scope; otherwise it is the final discovery URL when available, then the configured URL. Section 7.1 states the normative resolution order and scope-containment rule.
- **Secure context**: An HTTPS origin with a valid TLS connection, or the loopback origin `http://localhost` (including `localhost.` after canonicalization). Redirects to other origins are evaluated independently.
- **App scope**: The effective manifest scope, including the Web Manifest default-scope algorithm when no explicit valid `scope` is present. If no usable manifest applies, it is the resolved launch URL's origin.
- **User-initiated / explicit user action**: A navigation or operation directly caused by a trusted WebView user gesture as reported by the provider, with no app-defined grace period that would allow later script-generated actions to inherit that status.
- **Allowed HTTP(S) URL**: A syntactically valid, userinfo-free HTTPS URL, or a plain HTTP URL whose host passes the exact loopback classification in section 9.
- **Credential-like URL parameter**: A case-insensitive query or fragment key equal to `access_token`, `auth`, `authorization`, `code`, `credential`, `jwt`, `key`, `password`, `secret`, `session`, `sessionid`, `sig`, `signature`, `ticket`, or `token`, or beginning/ending with one of those words separated by `_`, `-`, or `.`. This heuristic is used only for warnings and never as proof that a URL is safe.

## 5. Core user experience

### 5.1 Main screen

The main screen displays all configured apps as a vertically scrollable list. Each row contains:

- the stored icon, or the default Mobile Web Shell web-app icon;
- the configured name;
- the resolved launch URL as secondary text, elided in the middle when too long so that the scheme and host always remain visible.

Interactions:

- Tapping a row launches that configured app.
- Long-pressing a row opens its edit screen.
- A Reorder action enters ordering mode. In ordering mode, the user can drag rows by a visible handle and can move the focused row up or down through equivalent accessibility actions. Done saves the order; Cancel restores the order that existed before entering the mode.
- A clearly visible Add action opens a new-app configuration screen. The preferred presentation is a Material floating action button with an accessibility label of “Add web app.”
- An empty state explains that no web apps are configured and offers the same Add action.

The manually arranged order is persisted as the single canonical ordering for configured apps. Newly added apps are appended to the end. Removing an app closes the gap without otherwise changing the order; editing or renaming an app does not move it. Duplicate names and duplicate URLs are allowed because configurations may use different accounts or settings.

Canonical order is a stable total order: no two records share a position and persisted positions are contiguous. Add, remove, and reorder operations update all affected positions in one database transaction. A stable app ID is the deterministic tie-breaker while repairing legacy or corrupted ordering. Cancel discards the in-memory reorder draft without writing it.

### 5.2 Add and edit screen

The screen contains:

- **Name**: required before saving, but may initially be populated from discovered metadata. Leading and trailing Unicode whitespace is removed; the result must contain at least one non-whitespace character, must not contain NUL, bidi-control, or non-printing control characters, and is limited to 128 Unicode code points. Discovered names are normalized by the same rule and an invalid result falls through to the next name source.
- **URL**: required; accepts HTTPS, plus HTTP only for exact `localhost` (or `localhost.` after canonicalization). Leading and trailing ASCII whitespace is removed. Input carrying no scheme is recognized before parsing as an authority followed optionally by a path, query, or fragment; the authority must contain a valid DNS or internationalized domain name and optional decimal port. One scheme is prepended before standards-based parsing: `http://` when the canonicalized host is exactly `localhost`, and `https://` otherwise. Thus `localhost:8080/path` becomes `http://localhost:8080/path`, `example.com/path` becomes `https://example.com/path`, and an internationalized host is converted to its canonical ASCII form. Inputs with embedded whitespace, userinfo, an empty host, an invalid or out-of-range port, an ambiguous colon form, or a malformed authority fail validation instead of being guessed. Any non-localhost HTTP URL fails inline validation and cannot be saved.
- **Username**: optional.
- **Password**: optional, masked by default, with a temporary reveal control.
- **Login method**:
  - Automatic (default);
  - HTTP Basic only;
  - Login form only.
- **Allow third-party cookies**: off by default. Enabling it requires acknowledging a privacy warning that embedded content from other sites may store/read cookies and use them for cross-site tracking. The setting applies only to this configured app's isolated WebView profile.
- **Refresh site details**: reloads the URL and repeats metadata/icon discovery.
- **Use suggested name**: offered only when discovery proposes a name that differs from a user-edited Name value. Selecting it replaces the Name field with the suggestion and marks the name as discovered again.
- **Save** and **Cancel** actions.
- **Clear cookies and site data** when editing an existing entry. This clears only that configured app's isolated WebView profile as specified below.
- **Remove** action when editing an existing entry. Removal requires a confirmation dialog and immediately makes the entry unlaunchable, finishes its live shell task, deletes its native configuration, metadata, cached icon, native permission decisions, and stored credentials, and updates widgets. Before those native records are discarded, a durable backup-excluded tombstone captures only the opaque retiring profile name/generation and creation time. Profile deletion is attempted immediately when compatible profile APIs are available and otherwise remains pending under section 6.5 until compatibility returns; failure never restores the removed native data. A single-app widget for the removed entry changes to an “App unavailable” state: it no longer launches the web app, and tapping it opens the Mobile Web Shell main screen, until the widget is reconfigured or removed by Android. Multi-app widgets remove the deleted ID from fixed subsets and no longer show a row for it.

Credentials are absent only when both Username and Password are empty. Otherwise both must be non-empty; Username is limited to 512 Unicode code points and Password to 4096 Unicode code points, neither may contain NUL, and neither value is trimmed or Unicode-normalized. Inline validation must explain any violation and prevent saving.

Saving an edited URL whose canonicalized origin differs from the previously saved configured URL's origin also discards that app's web data. Save requires a confirmation explaining that the app will be signed out of the previous site and its site data removed; on confirmation the record enters `CLEARING` and follows exactly the profile lifecycle below, so the next launch starts from a fresh, uniquely named, empty profile. Derived metadata from the previously saved URL is invalidated when the draft URL changes. If discovery of the draft URL completed successfully, its fresh final URL, launch URL, scope, manifest metadata, colors, and icon are saved; otherwise the fallback launch URL and scope are saved and the bundled default icon is used until discovery succeeds. A same-origin change of path, query, or fragment keeps both the profile and the previously stored icon unless successful discovery supplies a replacement.

Selecting **Clear cookies and site data** requires confirmation. The confirmation explains that the web app will be signed out and its offline/site state will be lost, then lists the affected categories: cookies, DOM storage, IndexedDB/Web SQL, Cache Storage and HTTP caches, service workers, HTTP-auth state, permissions, form data, browsing history, and all other data in that app's isolated WebView profile.

On confirmation, Mobile Web Shell follows the transaction ordering in section 6.5: it prevents new launches, records the retiring generation and reserves the incremented generation atomically, closes any active WebView using the retiring profile, and then requests deletion of that profile. A durable maintenance state makes the operation atomic from the user's perspective: the old profile is never launchable after clearing begins, and an interruption is resumed before launch. The UI distinguishes an operation that has started (`CLEARING`), successful completion (`ACTIVE` with the new generation), and a recoverable failure that requires Retry. It preserves the configured app record, name, URL, icon, manifest metadata, saved username/password, login settings, order, and widget assignments. It never clears or changes another configured app's profile.

### 5.3 Metadata preview and configuration flow

After a syntactically valid URL remains unchanged for a 500 ms debounce or the URL field loses focus, and whenever Refresh site details is selected, the app loads the page in a controlled WebView or equivalent WebView-backed discovery flow and shows progress. Save cancels any in-flight attempt and uses only the most recent completed result for the current draft URL; it never waits indefinitely for discovery. It then:

1. follows normal HTTP redirects, subject to the security rules in section 9;
2. records the final URL;
3. locates and fetches a linked Web Application Manifest if present;
4. resolves the manifest fields and icon candidates;
5. if no usable manifest icon exists, locates a linked page icon such as `rel="icon"`;
6. if no linked icon is usable, requests `/favicon.ico` from the final page origin;
7. falls back to the bundled default icon;
8. proposes a name using, in order: manifest `short_name`, manifest `name`, document title, final host;
9. displays the resulting name, URL, and icon for review before Save.

Discovered values never overwrite a name the user has manually edited unless the user explicitly selects **Use suggested name**.

The Name field tracks whether its current value is discovered or user-edited. Refresh always replaces draft derived final URL, manifest, scope, colors, and icon values with the new discovery result. It displays any newly suggested name separately; a user-edited name changes only when the user selects **Use suggested name**. Changing the configured URL immediately invalidates the draft's previous final URL, launch URL, scope, manifest snapshot, and credential binding before new discovery begins. These changes remain editor-draft state until Save; Cancel leaves the persisted record and credential binding unchanged.

A discovery failure does not prevent saving if the URL itself is valid. The UI reports the failure and uses available fallbacks. Network discovery has a finite timeout and can be cancelled by leaving the screen.

When credentials are configured, discovery may answer an eligible reactive HTTP Basic challenge inside its disposable profile so that metadata behind an authenticating server or proxy can be reached. This is permitted only for a top-level discovery navigation in a secure context whose canonicalized origin equals the origin of the configured URL as entered by the user; a challenge arriving after a cross-origin redirect, or for a subresource or iframe, is cancelled. Discovery never fills login forms and never offers clipboard assistance. Because this can send credentials to an origin before the section 8.1 binding is confirmed, the eligible origin is exactly the origin the user typed, and a successful authenticated discovery does not by itself set `CONFIRMED`; credential binding still follows section 8.1.

If discovery has never succeeded, `finalUrl` is null, `launchUrl` is the normalized configured URL, and `scope` is that URL's origin. A later successful discovery replaces all three derived values atomically. If a refresh fails for an existing entry whose configured URL has not changed, the failed attempt is shown but the last successful derived metadata remains. Saving a changed configured URL stores a completed successful discovery result for that exact draft URL when one exists; otherwise it stores the preceding fallback values and discards metadata derived from the old URL.

Default discovery limits are: 15 seconds for the initial document, 10 seconds per manifest or icon resource, 30 seconds overall, 10 redirects per resource chain, 1 MiB decoded manifest text, 5 MiB encoded icon input, and 20 MiB aggregate response bodies per attempt. The initial-document timer starts immediately before the discovery WebView's `loadUrl`, spans redirects without resetting, and stops on the first main-frame visible commit or terminal main-frame error. Its expiry stops loading and begins the normal fallback chain. The overall timer starts with that same `loadUrl`, covers the document and every subsequent native resource fetch, and never resets. Every attempt and native resource request has an identity; after cancellation or timeout, late callbacks for that identity are ignored and cannot replace the shown result. Limits are measured after content decoding except where explicitly called encoded, are configurable only at build time, and produce a non-fatal discovery warning followed by the normal fallback chain. The initial document carries no application-level size limit; provider and network limits still apply. Manifest and icon resources are fetched by the native client of section 7.1, which is where the byte, redirect, and per-resource timeout limits are enforced, and the 20 MiB aggregate budget counts those natively fetched bodies.

Each discovery attempt has a unique, non-reused disposable profile ID and generation token. Starting another attempt or leaving the screen closes its WebView and invalidates pending callbacks; stale results are ignored. Profile deletion begins only after the WebView is closed. On startup, the app removes orphaned disposable discovery profiles left by process termination. Concurrent attempts never share a profile.

## 6. Web app shell

### 6.1 Presentation

- The shell is a dedicated Android activity containing one system WebView.
- It has no app bar, address bar, navigation buttons, tab UI, overflow menu, or other browser chrome.
- Web content fills the usable application window.
- Content may draw beneath the status bar only if contrast and insets are handled correctly. It must not be obscured by either the Android status bar or the bottom system navigation bar/gesture area.
- The shell follows the manifest's supported `display` and color hints where practical. `theme_color` tints the status bar, the bottom system navigation bar, and the Android task-description color. For system bars, choose whichever of opaque black or opaque white system-bar icons has the higher WCAG 2.1 relative-luminance contrast ratio, and use the manifest color only when that ratio is at least 4.5:1; otherwise use the app theme's corresponding system-bar color and icon appearance. Alpha colors are composited over the active light- or dark-theme surface before measurement. `background_color` paints the shell window behind the WebView before the first visible paint; after the same compositing, it is used unless invalid or fully transparent. Dark mode uses its active theme surfaces for compositing and fallback rather than a separate subjective suitability test. Neither color is ever applied to native configuration screens. `fullscreen` may hide the status bar, but must never hide or cover the bottom system navigation area. The user must always retain a system escape/navigation affordance.
- Manifest display mapping is exact: `fullscreen` requests status-bar hiding subject to the preceding safety rule; `standalone`, `minimal-ui`, `browser`, and unknown/unsupported values all use the same chromeless shell with normal system bars. The shell never adds browser controls for `minimal-ui` or `browser`. Display does not change orientation or Android task behavior.
- Each configured app's shell occupies its own document-centric Android task, keyed by a stable app-ID data URI on the shell activity's base intent, so it appears as its own Recents entry carrying that app's icon and name and different configured apps can be live simultaneously. The activity uses Android's document-launch/task flags and `onNewIntent` handling to find and bring forward the existing task for that stable ID; it does not rely on dynamically generated task affinities, which Android does not support. Launching an app whose shell already exists must not create a second live shell or discard its current WebView state.
- The WebView uses the device's installed Android System WebView implementation and receives its behavior/security updates.

### 6.2 Navigation

- On system Back, first dismiss a local error screen and return to the last safe committed page when one remains. Otherwise, if `WebView.copyBackForwardList()` has an entry before its current committed index, call `goBack()`; WebView's own list determines whether redirect and reload entries are traversable.
- If neither a local error overlay nor a previous WebView back/forward-list entry exists, close the shell and return to the launcher/main screen or the previous Android task destination.
- Back gestures and hardware/software Back buttons have identical behavior.
- The shell supports pull-to-refresh: a downward drag that begins while the page is scrolled to the top reloads the current page. It is inactive whenever the page is not at scroll top, so it never competes with the site's own vertical scrolling. This gesture is the explicit reload referred to in sections 8.3 and 8.4, and it is the only reload affordance on a successfully loaded page.
- Same-origin navigation and navigation within manifest scope remain in the shell.
- An allowed HTTP(S) link that does not request a new browsing window remains in the current shell WebView, including when its destination is outside the configured app's scope. This supports non-PWA sites, authentication redirects, and cross-origin application flows. Navigating out of scope does not expand the origin allowed to receive configured credentials.
- A link that explicitly targets an allowlisted non-web external application scheme is delegated only after user confirmation. A non-allowlisted external scheme is never delegated.
- Before any navigation or external delegation, reject a non-localhost plain HTTP destination. This applies to direct navigation, redirects, form submissions, pop-ups, `target="_blank"`, `window.open()`, and URLs that would otherwise be sent to the system browser. Show a non-fatal HTTPS-required error and keep the last safe page when possible.
- Unsupported or dangerous schemes are blocked. `javascript:`, `data:`, `file:`, and `content:` top-level navigations are not launched from untrusted page links.
- An allowlisted installed-app link shows a confirmation containing the destination type before Android resolves it. If no handler exists, show a non-fatal error. A link whose scheme is not on the allowlist is blocked with a non-fatal error and is never passed to Android.
- Multiple-window support is enabled solely so that the shell can observe new-window requests and route them. The shell still provides no tabs and never creates a second visible WebView or window. Script-initiated window opening without a trusted user gesture is disabled at the WebView settings level, so an ungestured `window.open()` never reaches the policy below and simply fails.
- A new-window request is resolved by attaching a temporary probe WebView solely to learn the requested destination URL. The probe is bound to the same configured app's profile, never to the default profile and never to another app's profile. The route is supported only when the provider exposes the destination to a synchronous pre-load navigation callback where the app can cancel before dispatch; the compatibility suite verifies zero request bytes and no profile mutation for each enabled provider version. If the first observable callback occurs after dispatch, or no URL is exposed, the request is rejected. The probe is destroyed as soon as the destination is known or the attempt fails.
- Once the destination is known, an allowed HTTPS URL outside the configured app's scope opens in the system's default web browser through an Android view intent. A destination within scope is loaded in the existing shell WebView instead, because the shell has no additional windows to open it in. Every other destination follows the scheme and transport rules above.
- New-window requests whose destination cannot be determined, including an initial `about:blank`, are rejected with a non-fatal message. They are never attached to a visible WebView or allowed to navigate later without policy evaluation.
- The platform reports a new-window request without revealing whether it came from activating an `<a target="_blank">` element or from a `window.open()` call made under the same user gesture, so the preceding rule treats both as new-window requests. This is the closest achievable form of the intended rule, which is that an out-of-scope `<a href="https://…" target="_blank">` link opens in the system browser.
- Links that do not request a new window stay in the shell under the rule above, in scope or out of it. For those, and for any destination the automatic route declines, an explicit escape hatch remains: long-pressing a link offers **Open in browser** for that link's URL. It is offered only for an allowed HTTPS URL and never acts without that deliberate choice.
- When delegating a URL to the system browser, Mobile Web Shell adds no credentials, Authorization or Cookie headers, referrer headers/extras, or private WebView-profile state. It rejects URLs containing userinfo. If a destination URL's query or fragment appears to contain common token/credential parameters, delegation pauses for a redacted confirmation with **Continue** and **Cancel**; the UI names matching parameter keys but never displays their values. The warning cannot guarantee that an arbitrary site-generated URL contains no secret. If no browser can handle the URL, show a non-fatal error and leave the shell on its current page.
- External schemes use an explicit allowlist. Version 1 allows exactly `mailto:`, `tel:`, `sms:`, `geo:`, and `market:`; each is delegated only after a confirmation naming the destination type. `intent:` is not on the version 1 allowlist and is blocked. Every other custom scheme is blocked unless added to the reviewed allowlist after threat modelling. Should `intent:` ever be added, it must be parsed defensively: explicit components/packages and nested `file:`, `content:`, `javascript:`, `data:`, or disallowed-cleartext fallback URLs are rejected, and any permitted HTTPS fallback is re-evaluated under the normal navigation policy. Confirmation never grants a scheme permanently.

### 6.3 Loading and errors

- Show a minimal in-content loading indicator while the initial page loads. It disappears after the first committed visible page or an error.
- TLS errors are never bypassed. Show a local error screen with Retry and Close.
- TLS, network, and main-frame loading failures show a local error screen with Retry and Close and must not reveal stored credentials. HTTP response status pages, including 4xx and 5xx responses, remain site content and render normally unless WebView reports a transport-level failure.
- Renderer crashes show a recoverable error and allow reloading the WebView.
- Renderer-crash recovery disposes the crashed WebView and creates a new WebView attached to the same active profile. Retry loads the last eligible committed URL held in memory, or the resolved launch URL if none exists. A concurrent `CLEARING` transition or removal takes precedence: recovery is cancelled, the WebView is closed, and profile maintenance continues.
- Ordinary activity recreation retains the live WebView through a lifecycle-aware holder where practical instead of serializing page/form state. The app does not persist a WebView state bundle that may contain filled credentials or page form values. After process death it recreates the WebView and reloads the resolved launch URL under normal navigation and credential rules. Arbitrary committed navigation URLs are retained only in memory for renderer recovery and are never persisted for restoration.

### 6.4 Web capabilities

- JavaScript, DOM storage, cookies, and modern WebView defaults required by typical web apps are enabled.
- Third-party cookies are disabled by default for every configured app. They are enabled only for an app whose user has explicitly enabled **Allow third-party cookies** and acknowledged its privacy warning.
- Changing the setting affects future requests in that app's isolated profile. Disabling it prevents future third-party-cookie access but does not implicitly delete cookies already stored; the edit screen explains that **Clear cookies and site data** must be used to remove existing data.
- Enabling third-party cookies never makes cookies or any other web data visible to another configured app, even when both entries use the same URL or embedded third-party origins.
- Version 1 supports geolocation, camera, microphone, and file selection. Web notifications and client-certificate selection are unsupported and their requests are denied without hanging the shell. Geolocation, camera, and microphone require an exact-origin native confirmation; any applicable Android runtime permission is requested only after site-level consent. File selection uses the Android document chooser rather than a runtime permission and requires a user gesture for each invocation.
- A geolocation allow or deny decision is remembered for the exact origin and configured-app profile generation until that profile is cleared; a later request uses that decision without prompting again. Mobile Web Shell owns this small native permission-decision store because provider permission persistence is not assumed. It is keyed only by opaque app ID, profile generation, exact origin, and capability, participates in the same durable clear/removal lifecycle, and is never shared across generations or apps. Camera and microphone approval or denial applies only to the current request and is prompted again for each later request; simultaneous requests for the same origin and capability are serialized rather than combined. File selection requires a fresh chooser action for every request. Android app-level permissions remain controlled by the operating system. Clearing the WebView profile also removes that generation's native permission decisions but does not claim to revoke Android app-level permissions; the edit screen explains this distinction. When the Android runtime permission behind a granted site-level permission is denied permanently, the site request is denied immediately rather than left hanging, and a non-fatal message explains that the permission must be enabled in Android app settings and offers a shortcut to that settings screen. The site-level grant is retained so that the capability works again once the operating-system permission is restored.
- Mixed active content is blocked. HTTPS pages must not load executable HTTP subresources.
- Safe Browsing is enabled when supported.
- WebView debugging is disabled in production builds.
- After an explicit user action, an eligible HTTPS or loopback download URL may be handed to an Android system handler only when the request is a plain GET with no POST body and the configured app's isolated profile can prove that no cookie would be sent to that URL. The check uses the profile-scoped provider cookie API for the exact serialized URL, thereby honoring provider domain, path, Secure, expiry, partitioning, and request-context rules. If the API cannot provide an authoritative empty result, the handoff is refused conservatively. This means downloads from a site the user is signed in to are normally reported as unsupported rather than fetched unauthenticated by another application. `blob:` and `data:` download URLs are rejected, because their content exists only inside the profile and cannot be re-fetched by a handler. The URL is subjected to the same external-delegation checks; declared MIME type and filename are treated as untrusted hints. Mobile Web Shell does not provide a downloads UI.

### 6.5 Per-app web-data isolation

Every configured app must run in its own persistent, isolated WebView profile. The isolated profile resource comprises provider-owned data plus Mobile Web Shell's generation-keyed native permission decisions. A configured app's web data must not be readable, writable, or reused by:

- another configured app in Mobile Web Shell, including another entry with the same URL or origin;
- metadata-discovery or configuration WebViews;
- any other WebView owned by Mobile Web Shell;
- WebViews in other installed applications; or
- standalone browsers installed on the device.

The isolated profile includes, at minimum:

- cookies, including session, persistent, first-party, and third-party cookies;
- DOM local storage and session storage;
- IndexedDB and Web SQL data where supported by WebView;
- Cache Storage, HTTP caches, and application caches;
- service-worker registrations, scripts, state, and controlled caches;
- HTTP authentication state and client-certificate preferences;
- WebView form data, saved-password state, permissions, geolocation grants, and origin-specific settings;
- browsing history, autofill-associated state, and other persistent or ephemeral WebView browsing data.

Isolation requirements:

- Each configured app's stable ID and current profile generation map to exactly one active private WebView profile and private storage namespace. Retired generations are never reused. URLs or origins are never used as the isolation key.
- Separate entries for the same site remain isolated and may be signed in to different accounts.
- A profile persists across launches of its configured app so the app can retain its own login sessions and offline data.
- No `CookieManager`, storage manager, cache, service-worker controller, HTTP-auth store, or similar mutable WebView facility may be treated as globally shared across configured apps.
- Configuration-time metadata discovery uses a dedicated disposable profile. Its cookies, storage, authentication state, and cache are destroyed when discovery finishes and are never transferred to a configured app's runtime profile.
- Opening one configured app must not initialize, attach to, or mutate another configured app's profile.
- The transient new-window probe WebView of section 6.2 belongs to its own configured app and may attach only to that app's active profile. It must never attach to the default profile, to another configured app's profile, or to a discovery profile, and because it cancels the navigation before commit it adds no state to the profile it is attached to.
- Removing a configured app logically deletes its complete profile through the supported profile API, immediately when compatible or through the durable tombstone when cleanup must be deferred. Flash-storage cryptographic erasure is not promised. Clearing data for one app must not clear or alter any other app.
- Android application sandboxing provides separation from other applications and browsers; Mobile Web Shell must additionally enforce separation among its own configured apps.
- If the installed Android System WebView cannot provide the required independent profile/storage mechanism, Mobile Web Shell must refuse to launch web apps and show an actionable compatibility error. It must never silently fall back to a shared profile.

The implementation requires stable AndroidX WebKit 1.9.0 or newer, `WebViewFeature.MULTI_PROFILE`, `WebViewCompat.setProfile`, and `ProfileStore`. Release builds pin the exact AndroidX WebKit dependency and carry a minimum provider-version constant established by the compatibility suite; the initial release must not ship until that constant and its tested provider matrix are recorded in the implementation documentation. Android 8 remains the minimum install/configuration version, but shell launch and WebView-backed discovery are available only when the installed provider reports `MULTI_PROFILE` and its version is at least that tested minimum; newer versions remain eligible for isolation, while provider-sensitive features such as Basic authentication remain disabled until their exact version passes their separate matrix. Otherwise the app shows the provider/version and actionable update instructions. If capability disappears or the provider is downgraded after configuration, profile access, discovery, and clear are blocked without modifying stored data until compatibility returns. Removal remains available: after confirmation, the app closes any living shell it can identify, deletes the native configuration, credentials, metadata, native permission decisions, and icons, updates widgets as for a removed record, and writes a minimal pending-profile-cleanup tombstone containing only the opaque profile name/generation and creation time. It must not attempt profile APIs while incompatible. When compatibility returns, startup maintenance calls `deleteProfile` for every tombstone and removes a tombstone only after deletion is accepted or absence is confirmed. The UI states when removal has completed locally but WebView-profile cleanup is pending. The default profile is never a fallback.

Profile clearing is durable app-record state: `ACTIVE` or `CLEARING`. Each app record stores a monotonically increasing active `profileGeneration` and a nullable `retiringProfileGeneration`. To begin clear, one database transaction copies the current active generation into `retiringProfileGeneration`, changes the state, increments `profileGeneration` to reserve the next never-used name, and blocks launches; no WebView may attach to the reserved generation until the state returns to `ACTIVE`. All living WebViews and in-memory `Profile` references for the retiring generation are then closed and released, and `ProfileStore.deleteProfile` is called only for `retiringProfileGeneration`. A `true` result accepts deletion; `false` is idempotent success because the named profile does not exist. Either result retires that name permanently. After accepted clear, one transaction clears `retiringProfileGeneration` and returns the record to `ACTIVE`. A restart resumes from persisted state, so a retry can never target the newly reserved generation. Failure leaves launches blocked and offers Retry. While a record is `CLEARING`, it remains visible at its `sortPosition` in the main list and every multi-app widget as a disabled, non-launching row labelled “Clearing site data…”; tapping it shows a short explanation. Its single-app widgets show “App data maintenance.”

Removal does not retain an app record in a `DELETING` state. A durable journal first records the opaque retiring profile identifier, then closes all live WebViews and the shell task, deletes native app data and updates widgets, and finally attempts `ProfileStore.deleteProfile`. The journal's minimal tombstone survives failure or incompatibility and is retried at startup; `true` or `false` completes cleanup and deletes the tombstone. The removed app never reappears as a pending row and its profile name is never reused. The same remaining configured app has at most one live shell activity, and a new launch brings that activity forward.

The normative deletion guarantee is the `ProfileStore` contract: after accepted deletion the old `Profile` object/name is unusable by the application, and a new generation begins with no provider-visible profile state. Tests directly verify every data category for which the pinned provider exposes an observable API or controlled behavioral probe. Categories that the provider documents as profile-owned but does not expose for observation are covered by provider conformance certification and deletion API success, not by claims of synchronous physical erasure.

## 7. Web Application Manifest and icons

### 7.1 Manifest handling

When a document links to a manifest, Mobile Web Shell resolves and fetches it according to Web Manifest URL resolution rules. At minimum it recognizes:

- `name` and `short_name`;
- `start_url`;
- `scope`;
- `display`;
- `theme_color` and `background_color`;
- `icons`, including `src`, `sizes`, `type`, and `purpose`.

Invalid, unreachable, cross-origin-disallowed, or malformed manifests are ignored without preventing configuration. `start_url` and `scope` must resolve to permitted HTTP(S) URLs. A cross-origin `start_url` is never adopted; the final document URL is used instead, and then the configured URL.

If a document contains multiple eligible manifest links, the first one in document order whose `media` condition matches is used, consistent with Web Manifest processing. Link and manifest URLs honor the document base URL rules. Unsupported declared types do not make otherwise fetchable JSON unusable, but malformed content is rejected.

Manifest processing uses the final document URL as the document URL and the fetched manifest URL as the manifest URL. Relative manifest members are resolved against the manifest URL. The effective `start_url` is resolved and validated using the Web Manifest algorithm, must be same-origin with the document, and has its fragment ignored for scope matching. If it is absent or invalid, the final document URL is used. A valid explicit `scope` is resolved and applied according to the Web Manifest algorithm; an absent or invalid explicit scope uses the algorithm's default scope derived from the effective start URL. If no usable manifest applies, scope is the resolved launch URL's origin.

"Within scope" follows the Web Manifest URL-prefix algorithm: after URL parsing and canonicalization, the candidate and scope must have equal origins and the candidate path string must start with the scope path string. This is a prefix test, not an added path-segment-boundary rule; query and fragment are ignored. An origin-fallback scope has path `/`, so containment reduces to exact origin equality. An explicit manifest scope that does not contain the effective start URL is invalid and is replaced by the Web Manifest default scope derived from the effective start URL. The rest of the manifest, including name, colors, and icons, remains usable.

The launch URL must be within the effective scope or falls back to the final document URL, then the configured URL.

Manifest discovery follows the current W3C Web Application Manifest processing model implemented by the selected WebView baseline except for the explicitly stated Content Security Policy deviation below. The document DOM is the authoritative source of eligible manifest links and page-icon links, their document order, resolved URLs, matching `media`, declared `type`, `crossorigin`, and effective referrer policy. A native client is the sole fetcher of manifest and icon response bodies so that section 5.3's byte, redirect, and timeout limits are authoritative and no resource is requested twice. Every request is re-evaluated under section 9, obeys the captured effective referrer policy, and strips cookies and authorization on a cross-origin redirect unless the redirected request is independently eligible. Fetch classes are distinct:

- The manifest uses CORS mode. It sends `Origin` and validates `Access-Control-Allow-Origin` and `Access-Control-Allow-Credentials`. Credentials mode is `include` only for `crossorigin="use-credentials"` and otherwise `same-origin`; wildcard origin is rejected for a credentialed response. Its final response URL becomes the manifest URL used to resolve members.
- A manifest icon uses no-CORS image-fetch semantics. It includes disposable-profile cookies only when the icon request is same-origin with the manifest URL; cross-origin icon requests are credentialless and require no CORS response header.
- A page `rel="icon"` resource uses no-CORS image-fetch semantics. It includes disposable-profile cookies only when same-origin with the final document URL; cross-origin requests are credentialless and require no CORS response header. Its captured link referrer policy applies.
- `/favicon.ico` is requested only from the final document origin, includes that disposable profile's eligible cookies, and uses the final document's referrer policy.

The client reads and writes cookies only through the attempt's disposable discovery profile adapter and must never use a configured app's runtime profile or a global cookie store. Before each request the adapter asks the provider for cookies applicable to the exact URL; after each response it applies eligible `Set-Cookie` headers back to that disposable profile before following a redirect or issuing a later fetch, leaving domain, path, Secure, SameSite, expiry, and partitioning decisions to the provider. This read/write behavior is enabled only when the compatibility suite proves that the APIs are scoped to that disposable profile. Otherwise all four fetch classes are credentialless, response `Set-Cookie` headers are ignored, and the UI warns that authenticated metadata may be unavailable.

The native discovery fetch does not enforce the document's Content Security Policy; this is the one intentional deviation from browser manifest fetching. Discovery is a user-initiated configuration convenience, exposes fetched bytes only to the native metadata parser, executes no fetched script, and transfers no state to a runtime profile. The implementation documentation records this deviation and tests the exact CORS, credentials, referrer, redirect, cookie-isolation, and limit rules above; “as closely as possible” behavior is not permitted.

Manifest metadata is snapshotted into local storage. Only the supported fields enumerated above are retained. Stored strings are valid Unicode with NUL and non-printing control characters removed, display strings are capped at 256 Unicode code points, URLs at 8 KiB after serialization, and each manifest is limited to the first 128 icon candidates in source order. Unknown members and arbitrary page HTML are never stored. It is refreshed only when the user edits/refreshes the entry or when a future background-refresh feature is introduced.

### 7.2 Icon selection and storage

- Prefer a manifest icon suitable for the target size, favoring `purpose: maskable` for Android adaptive presentation and a square SVG or high-resolution PNG when supported.
- Otherwise use a linked favicon, then `/favicon.ico`, then the bundled default.
- Reject images that exceed configured download or decoded-pixel limits, are not valid images, or use an unsupported format.
- Rasterize/normalize the selected source into app-owned PNG or WebP renditions appropriate for the launcher list and widget sizes while retaining a higher-resolution source rendition.
- Icons are stored in private application storage and referenced by the configured app's stable ID.
- Widget icons must be rendered from local storage and must not require network access during widget rendering.

Icon selection is deterministic. Supported inputs are PNG, WebP, JPEG, ICO, and non-scripted SVG. A resource is rejected above 5 MiB encoded, 16 million decoded pixels, or 4096 pixels on either raster dimension. SVG decoding runs with external resources, scripts, animation, fonts, and network access disabled; parse or rasterization failure rejects that candidate without exposing diagnostics containing source content. The app produces 48, 72, 96, 144, 192, 288, and 512 px square renditions, preserving transparency and center-fitting non-square artwork without cropping. For adaptive presentation it first considers icons declaring purpose `maskable`, then purpose `any`; monochrome-only candidates are not selected. Exactly one source candidate is selected, and every rendition is generated from that single source. Within a purpose class, candidates are ordered by: (1) scalable candidates, meaning a non-scripted SVG or a candidate declaring `sizes="any"`; (2) a declared square size exactly equal to 512 px; (3) the smallest declared square size greater than 512 px; (4) the largest declared square size below 512 px; (5) candidates with no usable declared size; and finally source document order as the tie-breaker within any of those groups. A candidate whose decoded dimensions contradict its declaration is ranked by decoded dimensions. Fetch or decode failure advances to the next candidate. Linked page icons are limited to the first 128 eligible candidates in document order and use the same size ordering, followed by `/favicon.ico`.

## 8. Authentication

### 8.1 General rules

- Credentials are optional and belong to exactly one configured app.
- Credentials may be transmitted only to a secure context as defined in section 4.
- Credentials must never be injected into a non-loopback cleartext (plain HTTP) page, a page with a TLS error, a non-loopback cleartext redirect, or a different origin from the credential origin. The permitted loopback origin of section 4 is a secure context and is credential-eligible.
- The initial credential origin is the origin of the final secure URL observed during configuration. If that cannot be established, credentials remain stored but disabled until a secure origin is confirmed.
- When discovery redirects across origins, the UI shows the resulting credential origin and requires explicit confirmation before enabling credentials for it. If credentials were saved while discovery could not establish a secure final origin, the first successful eligible top-level commit proposes that exact origin for explicit user confirmation; credentials remain disabled until confirmed. Changing the configured URL clears the binding and requires confirmation again.
- Credential binding has explicit persisted states: `UNBOUND`, `PENDING_CONFIRMATION`, and `CONFIRMED`. `proposedCredentialOrigin` is present only while pending; `credentialOrigin` is present only when confirmed. Save is allowed in any state. Runtime credentials can be used only in `CONFIRMED`; the sole pre-confirmation exception is the disposable discovery-profile Basic challenge for the exact user-entered origin under section 5.3, which neither changes binding state nor authorizes later runtime use. A same-origin successful discovery may be confirmed inline on Save; a cross-origin result requires a separate confirmation naming both origins. A first eligible runtime commit after failed discovery sets a pending proposal and opens the same confirmation UI. Rejecting a proposal returns to `UNBOUND`. Saving a configured URL whose canonical origin changes atomically clears both origins and returns to `UNBOUND`; an edit that preserves the same canonical origin preserves an existing `CONFIRMED` binding.
- Any cross-origin authentication use requires a separate, explicit configuration; a redirect alone never expands credential scope.
- Logs, analytics, crash reports, UI error messages, backups, and saved WebView state must not contain plaintext credentials or Authorization headers.

### 8.2 HTTP Basic authentication

Reactive mode:

- Supply credentials only for a Basic challenge correlated to the pending top-level navigation to the exact confirmed credential origin. The pending navigation's canonical scheme, host, and effective port are recorded before loading.
- Correlation uses a provider-tested navigation gate. A new identity is armed by the main-frame `onPageStarted` callback for an eligible recorded top-level navigation. The gate is disarmed on a new main-frame navigation, commit, terminal error, cancellation, or after any `shouldInterceptRequest` callback whose canonical URL is not the recorded main-document request URL; that last observable event is the conservative definition of “a subresource load has begun.” A Basic challenge is supplied only while exactly one gate is armed and its callback host equals the recorded host. The scheme and effective port are taken from the recorded navigation rather than from the callback, because `onReceivedHttpAuthRequest` reports neither. Any challenge after the gate is disarmed is cancelled. The challenged realm is recorded only for retry suppression, never for expanding credential scope.
- This correlation test is a heuristic bounded by callback coverage and ordering in the installed provider. The compatibility suite must demonstrate for each enabled provider version, using challenges in the main document, redirects, iframes, and early/late subresources, that only a challenge for the eligible top-level request can arrive while the gate is armed. Basic authentication is enabled only for provider versions in the recorded passing matrix; on every other version all challenges are cancelled and the editor explains that Basic authentication is unavailable. Runtime code does not infer safety from an untested version range.
- Do not persist credentials in WebView's global HTTP-auth database.
- Cancel the challenge when the context is ineligible. Supply credentials at most once for the tuple of pending-navigation identity, origin, and realm. Pending-navigation identity is the identity recorded above when the top-level navigation started; it persists across that navigation's redirect chain and its 401 retries, and a new top-level navigation begins a new identity. A repeated challenge for the same tuple is treated as rejection, cancelled, and shown as a non-sensitive error.

Mobile Web Shell does not send Basic credentials proactively. WebView top-level custom-header propagation across redirects cannot provide the required cross-origin non-disclosure guarantee; only eligible reactive challenges are supported.

### 8.3 HTML login form filling

Form login is best-effort and operates as follows:

- Run only in the top-level frame of the exact stored credential origin and only after a secure page has committed.
- Search for visible, enabled password fields and an associated visible username/email/text field in the same form.
- Prefer standards-based hints such as `autocomplete="username"` and `autocomplete="current-password"`, followed by conservative field-name/type heuristics.
- Fill no form if the page has zero or multiple ambiguous login candidates.
- Never fill hidden fields, cross-origin iframes, sandboxed frames, or fields outside the selected login form.
- Fill the username and password and dispatch normal input/change events so common frameworks observe the values.
- Filling a DOM form necessarily allows scripts executing in the confirmed exact credential origin to observe the filled values. The credential-origin confirmation explicitly states this trust boundary. Credentials remain unavailable to other origins, subframes, Android JavaScript bridges, application logs, and diagnostic output. Injection must not construct JavaScript source containing credential literals; it uses a mechanism whose values are passed separately from executable source and clears native temporary buffers when practical.
- Do not automatically submit the form. The user reviews and activates the site's own sign-in control. This avoids unintended login attempts and supports CAPTCHAs/MFA.
- Attempt at most once per committed document unless the user explicitly reloads.
- Do not expose credentials to page-visible logs, Android JavaScript interfaces, or URL query parameters.

After a top-level commit, a narrowly scoped form-fill helper observes that document through DOM readiness and subsequent mutations for at most 10 seconds. It fills immediately when exactly one eligible candidate becomes unambiguous, then disconnects. If none does, it disconnects and the clipboard fallback may be offered. A reload or new committed document starts a new bounded observation; no persistent native Retry control is added.

Credential values are delivered with `WebViewCompat.addWebMessageListener` using a random, non-reused JavaScript object name and an allowed-origin rule containing only the exact confirmed origin. After the eligible top-level commit, native code installs the listener and uses `evaluateJavascript` only to run a fixed bootstrap that contains the random object name and document capability but no credential values. The bootstrap, running in the top-level document, identifies the candidate fields and sends one capability-bearing `ready` message. The native listener accepts only that fixed message when `isMainFrame` is true, the source origin exactly matches, and the captured navigation/document identity is still current; it treats every other page-to-native message as invalid and exposes no general command surface. Native code then sends one reply through that callback's `JavaScriptReplyProxy`, carrying username and password as WebMessage data rather than executable source. The bootstrap accepts one reply, writes only the already-selected fields, clears its references, and tears itself down. Native code calls `WebViewCompat.removeWebMessageListener` when filling completes, times out, navigates, errors, or the WebView closes, and invalidates the document capability before processing later callbacks. Form filling is enabled only when the provider supports the required WebMessage-listener and reply-proxy behavior and the compatibility suite verifies exact-origin filtering, `isMainFrame`, removal, and navigation invalidation; otherwise the eligible clipboard fallback is used.

Automatic login method first responds to an eligible HTTP Basic challenge; otherwise it attempts conservative form filling.

Login methods are mutually constraining: Form only never supplies Basic credentials; HTTP Basic only never fills forms or offers clipboard assistance; Automatic may use eligible reactive Basic, form filling, and clipboard assistance as specified.

### 8.4 Manual clipboard fallback

When both username and password are configured, no HTTP authentication challenge has been handled for the current page, and login-form autofill does not fill both fields, Mobile Web Shell offers manual credential assistance.

- The offer is a transient native prompt or bottom sheet over the shell, not persistent browser chrome.
- It appears only after a top-level page from the exact stored credential origin has committed in a secure context. It is never offered on an ineligible origin, insecure page, TLS-error page, iframe, or externally launched browser page.
- The prompt explains that automatic filling was unsuccessful and provides separate **Copy username** and **Copy password** actions, plus Dismiss. Android's clipboard contains only one selected value at a time; the app does not combine both credentials into one clipboard item.
- Nothing is copied until the user explicitly selects one of the copy actions. Copying the username does not automatically copy the password afterward.
- Before the first password copy, the UI warns that clipboard contents may be visible to the operating system, keyboards, accessibility services, or other software depending on the Android version. The user may cancel.
- Clipboard entries use a generic, non-secret label and are marked as sensitive using the Android clipboard sensitivity metadata where supported, preventing sensitive previews where the platform honors it.
- After a successful copy, show a brief confirmation that does not display the copied value.
- Clipboard clearing is best-effort because Android provides no reliable “pasted” callback to the source app. Mobile Web Shell attempts to clear its clip when the shell loses foreground, on the shell's next resume after a copy, and after 60 seconds while its process can run. Android restrictions, process suspension, or termination can prevent inspection or clearing, so the UI promises none of these attempts. Each written clip carries an app-generated opaque nonce in its metadata where supported; before clearing, the app verifies that nonce and the exact clip content. On platforms that cannot retain/read the marker, it clears only on an exact content match. It never clears a newer or different clip.
- Dismissing the offer affects only the current committed document. The helper may be offered again following a new navigation or explicit reload if autofill still fails.
- Clipboard fallback is unavailable for configurations set to **HTTP Basic only**, because those credentials are not intended for an HTML form.
- Clipboard contents are not part of the configured app's isolated WebView profile and must never be injected into page script, logs, analytics, saved state, or crash reports.

### 8.5 Credential storage

- Encrypt credential values with AES-256-GCM using a non-exportable Android Keystore-backed key generated for encryption/decryption only, with randomized encryption required and no user-authentication or biometric binding. Prefer hardware-backed or StrongBox storage when available but do not require it. Use a versioned key alias; rotation and format migration write and authenticate the replacement ciphertext completely before atomically switching the credential record reference, then delete the old ciphertext. A failed migration leaves the old record authoritative and retryable.
- Store ciphertext, nonce/IV, version, and the app record association in private app storage. Never store plaintext credentials in preferences, databases, files, logs, or process-persistent caches.
- Store credential ciphertext and encryption metadata in a separate private database or file that is excluded in full from backup and device-to-device transfer. The ordinary configuration store contains only a non-secret credential-presence flag or opaque reference. A restored configuration whose secret record is absent is treated as having no configured credentials and explains this when edited.
- Exclude credential data and key material from Android cloud backup and device-to-device transfer. Non-secret app configurations may be backed up only if credentials are removed from the restored record.
- Clear temporary character arrays/byte buffers when practical after use and avoid retaining credentials longer than needed for an eligible authentication operation.
- A device without a usable secure keystore may save apps without credentials but must refuse credential storage with a clear explanation.
- If a stored credential can no longer be decrypted because its Keystore key has become permanently invalid — for example after a lock-screen change, biometric re-enrolment, or a restore — the app deletes the undecryptable secret record, clears the credential-presence marker, and returns the configured app to the no-credentials state. Launching is never blocked by this. The editor, and a one-time non-blocking notice on the main screen, explain that stored credentials were lost and must be re-entered; neither message contains credential material.
- Encryption-format and key versions are stored so that a future format can be migrated. Migration re-encrypts a record in place under the new format on first use. A record whose stored format version is unknown to the running build is treated as undecryptable under the preceding rule.

## 9. Network and transport security

- Cleartext network traffic is disabled at the Android network-security-config level except for an explicit, non-subdomain `localhost` rule. The canonical `localhost.` spelling maps to that same rule after removal of one terminal dot.
- URL security classification uses one pinned WHATWG URL parser implementation for input, serialization, and origin comparison; Android `Uri` and `java.net.URI` are not security authorities. Host processing uses Unicode UTS #46 non-transitional IDNA with STD3 rules, maps recognized Unicode dot separators before processing, rejects IDNA errors, lowercases the ASCII host, removes exactly one terminal DNS dot, serializes an empty HTTP(S) path as `/`, removes default ports, preserves non-default decimal ports, and applies the parser's percent-encoding normalization. Userinfo is rejected before storage or navigation. Plain HTTP is allowed only when the resulting host is exact `localhost`. Localhost subdomains, all IPv4 and IPv6 literals, alternate numeric spellings, lookalike names, and DNS aliases are not treated as permitted loopback.
- A non-localhost plain HTTP URL must not be saved, loaded in a WebView, followed as a redirect, fetched for manifest/icon discovery, or delegated to an external application. There is no user override. The app shows that HTTPS is required.
- Android Network Security Config is the fail-closed enforcement layer for disallowed cleartext traffic. WebView navigation callbacks provide early cancellation and user-facing errors but are not the sole enforcement mechanism. The release's provider matrix records, independently for direct links, redirects, GET/POST form submissions, pop-ups, and external intents, whether the pinned provider plus network configuration can guarantee rejection before payload transmission. Only the affected flow is disabled when that guarantee is unavailable: its attempted use is cancelled with a non-fatal explanation that the installed WebView cannot perform that operation safely, while other passing flows remain enabled. A provider is wholly incompatible only when profile isolation or safe initial top-level navigation is unavailable. Tests verify every enabled flow and confirm that prohibited requests transmit no request bytes.
- HTTPS-to-HTTP downgrade redirects are blocked, except when the resulting HTTP URL is a permitted loopback/localhost origin. A blocked redirect never inherits credentials, headers, referrer secrets, or POST data into a subsequent request.
- Certificate errors, hostname mismatches, expired certificates, and untrusted certificate chains cannot be overridden by the user.
- Metadata and icon fetching follows the same transport restrictions as shell navigation.
- Apply the concrete discovery limits in section 5.3. The shell's 30-second initial-load timer starts immediately before the first `loadUrl` for a newly created WebView, spans the entire redirect chain without resetting, and stops on the first main-frame visible commit or terminal main-frame error. Expiry stops the load and shows the local Retry/Close error screen; callbacks carrying that navigation identity are thereafter ignored, so a late commit cannot replace the error screen. Retry creates a new navigation identity and timer. The shell imposes no application-level response-size limit; provider/network limits still apply.
- Release builds must contain no permissive WebView SSL callbacks.

## 10. Android widgets

### 10.1 Multi-app list widget

- A home-screen widget displays a compact list of configured apps with icon and name.
- It is horizontally and vertically resizable within launcher capabilities.
- The number of visible rows adapts to available height; the collection scrolls where supported.
- Tapping a row launches that configured app directly into its shell.
- Tapping the widget header or empty state opens the Mobile Web Shell main screen.
- The widget updates after add, edit, remove, icon refresh, device reboot, app upgrade, and relevant launcher resize events.
- Widget configuration allows the user to select which apps appear. Selected apps always appear in their relative canonical main-screen order; widgets do not maintain an independent ordering. The default is a persisted live **All apps** mode, in which apps added later appear automatically. If the user selects or deselects individual apps, the widget switches to a fixed selected-ID subset and later additions are not selected automatically.
- Saving a new main-screen order immediately requests an update of every multi-app widget instance. Each widget preserves its app selection while reflecting the new relative order.

### 10.2 Single-app launcher widget

- A small widget, targeting a 1×1 launcher cell where supported, is configured for one saved app.
- It displays the stored icon and configured name and visually resembles a normal installed-app launcher icon, subject to launcher-controlled widget padding and label rendering.
- Tapping it opens the selected app directly in its shell.
- Adding the widget opens an Android widget configuration activity to choose the app.
- If the selected app is removed, the widget shows the default icon and “App unavailable”; tapping it opens the main screen.
- Multiple instances may point to the same or different configured apps.

Widgets use immutable, explicit `PendingIntent` objects whose stable app ID is validated against the local database before launch. URLs and credentials are not placed directly in intents or widget state.

## 11. Data model

Each configured app has at least:

| Field | Description |
| --- | --- |
| `id` | Stable, locally generated opaque identifier |
| `name` | User-visible configured name |
| `nameSource` | `DISCOVERED` or `USER_EDITED`; governs whether refresh may replace the Name field |
| `configuredUrl` | Normalized URL entered by the user |
| `finalUrl` | Final URL observed during discovery |
| `launchUrl` | Valid manifest start URL or fallback URL |
| `scope` | Valid manifest scope or origin fallback |
| `credentialOrigin` | Exact eligible origin for authentication |
| `credentialBindingState` | `UNBOUND`, `PENDING_CONFIRMATION`, or `CONFIRMED` |
| `proposedCredentialOrigin` | Exact eligible origin awaiting confirmation, otherwise null |
| `loginMethod` | Automatic, Basic only, or form only |
| `allowThirdPartyCookies` | Per-app boolean, default false |
| `profileState` | Durable `ACTIVE` or `CLEARING` lifecycle state |
| `profileGeneration` | Monotonically increasing generation used in the private profile name |
| `retiringProfileGeneration` | Generation being durably cleared; non-null only in `CLEARING` |
| `credentialReference` | Optional opaque reference/presence marker for a secret record outside this database |
| `manifestSnapshot` | Sanitized supported manifest metadata |
| `iconReference` | Private local icon asset reference |
| `themeColor` | Optional validated color |
| `backgroundColor` | Optional validated color |
| `sortPosition` | Persisted position in the canonical main-screen and widget ordering |
| `createdAt`, `updatedAt` | Local timestamps |

Passwords, Authorization headers, cookie contents, and arbitrary page HTML are not part of the ordinary configuration database.

Each installed widget instance has its own record, keyed by the Android-assigned `appWidgetId`:

| Field | Description |
| --- | --- |
| `appWidgetId` | Android-assigned widget instance identifier |
| `widgetType` | `MULTI_APP` or `SINGLE_APP` |
| `selectionMode` | `ALL_APPS` or `SELECTED_SUBSET`; multi-app widgets only |
| `selectedAppIds` | Set of configured-app IDs; present only for `SELECTED_SUBSET` |
| `targetAppId` | The single configured-app ID; single-app widgets only |

Widget records are written by the widget configuration activity and deleted when Android reports that instance as deleted. Removing a configured app removes its ID from every `selectedAppIds` set; a single-app widget whose `targetAppId` no longer resolves keeps its record and renders the “App unavailable” state. Widget records never contain URLs, credentials, or icon bitmaps; rendering reads the configured app's stored icon renditions. Widgets have no ordering of their own and derive display order from `sortPosition`.

The backup-excluded maintenance store contains a pending-profile-cleanup tombstone for every removal before native app data is discarded, including when the provider is currently compatible. Each tombstone contains an opaque retired profile identifier/generation and creation timestamp, never a URL, origin, display name, or credential reference. Cleanup is attempted immediately when compatible. Tombstones survive restart and app upgrade, are never used to create or attach a profile, and are deleted only after compatible `ProfileStore` cleanup reports success or confirmed absence.

Backup eligibility is fixed: the ordinary configuration store is backup-eligible; the credential store, the stored icon renditions, and all WebView profile data are not. A backup-excluded installation sentinel is created on first installation. Its absence alongside a restored ordinary configuration store identifies the first launch after restore or device-to-device transfer; normalization creates the sentinel before exposing records and is idempotently resumed through the operation journal after interruption. Every restored record, including one restored in `CLEARING`, is retained, assigned an `ACTIVE` state, assigned a new `profileGeneration` greater than both its restored active and retiring generations, and has `retiringProfileGeneration` cleared; no deletion call is made because profile data was not restored. Its `iconReference` is cleared so the bundled default is used until the next successful discovery, and `credentialReference` is cleared because the secret record was not restored. `sortPosition` values are preserved and repaired into a contiguous total order.

The separate backup-excluded credential store contains records keyed by the opaque credential reference with the configured-app ID association, encrypted username and password ciphertexts, nonces/IVs, authentication tags where separate, and encryption-format/key versions. It is never joined into backup-eligible configuration exports.

Each configured-app record and profile generation map to a private WebView profile identifier derived from the opaque app ID plus generation. Profile identifiers must not expose credentials or URLs, and retired identifiers are never reused. Profile data is stored separately from the ordinary configuration database and is managed as an indivisible per-app lifecycle resource.

Operations spanning the configuration database, credential store, icon files, native permission-decision store, WebView profiles, or widget records use a durable operation journal in the backup-excluded maintenance store. Each step is idempotent, records completion before advancing, and is resumed on startup. Startup reconciliation also deletes unreferenced credential records, icon files, permission decisions for retired or absent generations, and widget records no longer installed according to `AppWidgetManager`; it clears dangling references from configuration records and requests widget refresh after repair. A resource is considered unreferenced only after no active operation journal can still adopt it. No cross-store operation relies on an in-memory transaction for crash consistency.

There is no fixed product cap on configured apps or installed widget records; every add or update must handle allocation and storage exhaustion without corrupting existing records and must report a recoverable error. Network-controlled collections are always bounded: only the first 16 eligible manifest links, first 128 manifest icon candidates, and first 128 linked page-icon candidates in document order are processed per discovery attempt. The existing byte, pixel, redirect, and time budgets still apply.

## 12. Architecture constraints

- Native Android implementation, preferably Kotlin.
- AndroidX/Jetpack components and Material UI for native screens.
- One activity or equivalent shell destination launches by stable configured-app ID, never by accepting an untrusted raw URL intent. It is instantiated at most once per configured app, in that app's own task as described in section 6.1.
- Structured persistence, preferably Room, for non-secret configuration and metadata.
- Keystore-backed encryption boundary for credentials as specified in section 8.5.
- Work compatible with current Android background-execution and widget update limits; widget refreshes should be event-driven rather than frequent polling.
- Web content is not granted a general-purpose JavaScript-to-native bridge. Any future bridge must be narrowly scoped, origin-checked, and separately threat-modeled.
- Exported activities, receivers, and services are minimized and protected. Widget entry points validate all IDs and inputs.
- The architecture must use a WebView capability that provides genuinely separate data stores per configured app. Clearing selected cookie types or intercepting storage APIs is not an acceptable substitute for profile isolation.
- Components that render widgets or native configuration UI must not initialize the default/global WebView data store.
- A live shell observes its configured-app record. Name, icon, and theme changes refresh its Android task description on resume or record change; every recreated shell validates the stable ID before attaching a profile. Changes to credentials, login method, and third-party-cookie policy apply to subsequent requests and navigations without reloading the current document. URL-origin changes, clear, and removal close the live WebView before profile retirement; removal also finishes the shell activity/task. A same-origin configured-URL change does not replace the live page immediately, but the next explicit app launch or process-death recreation loads the newly resolved launch URL. Relaunch while that shell is still live continues to bring its current page forward.

## 13. Accessibility and localization

- Native screens meet Android accessibility guidance for touch targets, focus order, TalkBack labels, text scaling, and color contrast.
- Icons are accompanied by app names; icon-only actions have content descriptions.
- The WebView remains accessible through Android's standard WebView accessibility support.
- All native strings are resource-based and localization-ready.
- Loading and error states are announced to accessibility services without excessive repetition.

## 14. Privacy

- No analytics, advertising SDK, or remote Mobile Web Shell service is required for core operation.
- Browsing destinations and credentials remain on the device except when necessarily sent to the selected websites.
- If crash reporting is later introduced, it must redact URLs by default and must never collect credentials, headers, cookies, form values, or page content.
- Removing a configured app deletes its app-specific stored metadata, icons, and credentials immediately. It deletes the complete isolated WebView profile immediately when compatible, or records cleanup durably and completes it when compatible profile APIs return.
- The required per-app **Clear cookies and site data** action deletes the selected app's complete isolated WebView profile without deleting its native configuration, stored credentials, metadata, icon, order, or widget assignments. It must not affect other configured apps.

## 15. Functional acceptance criteria

1. A user can add and save a valid HTTPS URL with a name and no manifest, then launch it from the main list.
2. The shell shows no browser chrome and its content is not obscured by the bottom system navigation area.
3. After two same-WebView navigations, system Back returns to the previous page; at the first history entry, system Back closes the shell.
4. Long-pressing a configured app opens an editor that can update or remove that app.
5. A manifest-backed site supplies its valid name, launch URL, scope, colors, and best available icon as defaults without requiring the user to edit them.
6. A non-manifest site uses its document title and favicon when available, and the bundled default icon otherwise.
7. Stored icons remain visible in the main screen and widgets while offline.
8. HTTP Basic credentials are supplied after an eligible challenge only for the exact configured secure origin.
9. HTTP Basic credentials are never added proactively; a cross-origin redirect cannot receive them from Mobile Web Shell.
10. On a conventional unambiguous HTTPS login page, configured form credentials are filled but the form is not automatically submitted.
11. No credential is filled or sent after navigation to a non-loopback cleartext HTTP page, a TLS-error page, a non-loopback cleartext URL, a cross-origin page, or an iframe.
12. Credentials are encrypted at rest with a Keystore-backed key and are excluded from backup.
13. The resizable widget launches each visible selected app and reflects configuration changes.
14. Each single-app widget instance launches its selected app and uses its stored icon and name.
15. A widget referring to a removed record cannot launch a stale URL and instead opens the main screen in an unavailable state.
16. Malformed manifests, missing favicons, offline discovery, renderer termination, and ordinary network errors are recoverable and do not crash the application.
17. Production builds reject certificate errors, block mixed active content, enable Safe Browsing where available, and disable WebView debugging.
18. Two configured entries for the same origin can simultaneously retain different authenticated sessions, cookies, local storage, IndexedDB, caches, and service workers without observing or modifying each other's data.
19. Configuration-time discovery leaves no cookies, site storage, cache, authentication state, or service-worker registration in the configured app's runtime profile.
20. Removing or clearing the data of one configured app removes its complete WebView profile without changing any other configured app's stored or in-memory web data. If removal occurs while the provider is incompatible, native data disappears immediately and an opaque tombstone causes profile deletion to complete when compatibility returns.
21. On a device whose System WebView lacks the required isolation capability, the app reports incompatibility and does not launch using a shared data store.
22. An in-scope `target="_blank"` link opens in the existing shell, while an out-of-scope one opens in the system default browser without transferring credentials or isolated profile data. An ungestured `window.open()` opens nothing. Long-pressing a link whose destination is an allowed HTTPS URL offers **Open in browser** under the same non-transfer guarantee; other destinations do not offer it.
23. When an eligible secure login page receives no HTTP-auth challenge and automatic form filling fails, the user is offered separate actions to copy the configured username or password; no credential is copied without an explicit action.
24. A copied credential is marked sensitive where supported and receives best-effort clear attempts on foreground loss, the next shell resume, and after 60 seconds while the process can run, provided the clipboard item has not since been replaced.
25. Manual credential assistance is never offered, and credentials are never copied by Mobile Web Shell, for an insecure or mismatched origin, a TLS-error page, a subframe, or an HTTP-Basic-only configuration.
26. A non-localhost plain HTTP URL cannot be saved, loaded, fetched during discovery, reached through a redirect or form submission, or delegated to the system browser; an HTTPS-required error is shown instead.
27. An out-of-scope HTTPS link without `target="_blank"` or another new-window request navigates within the existing shell WebView and does not expand the configured credential origin.
28. The user can manually reorder configured apps on the main screen using drag-and-drop or accessible move actions; the saved order survives restart, app edits, and renames.
29. Every multi-app widget reflects the canonical main-screen order after reordering while retaining its selected subset of apps. Newly added apps appear at the end of the main list.
30. The edit screen provides **Clear cookies and site data**. After confirmation, it removes the selected app's complete WebView profile and the next launch starts with no prior web session or site data, while configuration, credentials, metadata, icon, order, and widgets remain unchanged.
31. Clearing one configured app's cookies and site data does not read, delete, invalidate, or otherwise alter any other configured app's profile.
32. Third-party cookies are disabled for a new configured app. Enabling them requires an explicit per-app action and privacy-warning acknowledgement, affects only that app's isolated profile, and never weakens isolation from other configured apps.
33. Disabling third-party cookies blocks future third-party-cookie access and informs the user that previously stored cookies remain until **Clear cookies and site data** is used.
34. Entering `localhost:8080` without a scheme is normalized to `http://localhost:8080` and can be saved, while `example.com` is normalized to `https://example.com`.
35. Saving an edited URL with a different origin clears that app's WebView profile and icon after confirmation; a same-origin path edit keeps both.
36. A credential whose Keystore key has become permanently invalid is reported as lost, the configured app still launches without credentials, and no undecryptable ciphertext remains.
37. A download from a site the app holds cookies for is reported as unsupported rather than handed to a system handler, and `blob:` and `data:` download URLs are rejected.
38. Restoring a backup yields records in `ACTIVE` state with fresh profile generations, no credentials, and default icons, and no restored record references a retired profile name.
39. A configured app in `CLEARING` appears as a disabled, non-launching row at its usual position in both the main list and multi-app widgets; a removed app disappears immediately while opaque profile cleanup continues independently.
40. Each running shell appears as its own Recents entry, and relaunching a configured app whose shell is already live brings that shell forward instead of creating a second one.
41. Pull-to-refresh at the top of a loaded page reloads it, permits a further form-fill attempt, and is inactive when the page is scrolled away from the top.
42. A `mailto:`, `tel:`, `sms:`, `geo:`, or `market:` link is delegated only after confirmation, while an `intent:` link is blocked with a non-fatal error and never passed to Android.
43. A manifest `theme_color` tints the system bars and task description with legible icon contrast, and `background_color` paints the window before first paint; neither affects native configuration screens.

## 16. Required test coverage

- Unit tests for URL normalization, secure-context classification, origin matching, manifest resolution, scope checks, icon selection, and login-form candidate scoring.
- URL tests cover userinfo rejection, default-port normalization, permitted `localhost` and its trailing-dot form, and rejection of localhost subdomains, canonical IPv4 in 127/8, alternate numeric IPv4 spellings, IPv6 loopback, IPv4-mapped IPv6, lookalike hosts, and DNS aliases.
- Instrumented WebView tests using controlled HTTPS and loopback servers for navigation history, Basic challenges, redirects, form fill boundaries, TLS failures, and mixed content.
- Transport-policy tests cover permitted exact-localhost HTTP and rejected HTTP configuration, links, redirects, form submissions, manifests, icons, pop-ups, and external intents for every other host form.
- URL-input tests cover scheme prepending for an authority with optional path, query, or fragment, including `localhost:8080/path` becoming loopback HTTP, other hosts becoming HTTPS, internationalized-host canonicalization, whitespace trimming, and rejection of embedded whitespace, userinfo, empty hosts, invalid ports, and ambiguous colon forms.
- External-scheme tests cover confirmation and delegation for each allowlisted scheme, blocking of `intent:` and of every non-allowlisted scheme, and the absence of any permanent per-scheme grant.
- Download tests cover the cookie-presence refusal, the permitted no-cookie GET handoff, rejection of `blob:` and `data:` download URLs, and the unsupported-download message.
- Reload tests cover pull-to-refresh activation only at scroll top, a renewed form-fill attempt after reload, and absence of conflict with page scrolling.
- Initial-load timeout tests cover both discovery and shell timer start/stop events, redirects not resetting either deadline, cancellation on expiry, ignored late callbacks, the discovery overall deadline, and a fresh shell identity and deadline on Retry.
- Discovery-authentication tests cover an eligible Basic challenge answered for the typed origin, cancellation after a cross-origin redirect, cancellation for subresource and iframe challenges, and the absence of any inline credential confirmation caused by discovery alone.
- Manifest and icon fetch tests verify that the native discovery client enforces the byte, redirect, and per-resource timeout limits and uses only the disposable discovery profile's cookie state.
- Native discovery-client tests separately verify the manifest, manifest-icon, page-icon, and `/favicon.ico` fetch classes, including exact CORS response checks where applicable, credential modes, referrer-policy derivation, cross-origin redirect stripping, credentialless fallback when disposable-profile cookies cannot be exposed safely, and the documented CSP deviation.
- URL-change tests cover profile and icon clearing on an origin change, retention on a same-origin path change, and the save-time confirmation.
- Keystore tests cover permanent key invalidation, deletion of the undecryptable record, continued launching without credentials, and unknown-format-version handling.
- Task-model tests cover one live shell per configured app, simultaneous shells for different apps, per-app Recents entries, and relaunch bringing an existing shell forward.
- Presentation tests cover `theme_color` system-bar and task-description tinting with contrast selection, `background_color` pre-paint, dark-mode fallback, and absence of effect on native screens.
- Navigation tests for ordinary out-of-scope HTTPS links remaining in the shell; in-scope and out-of-scope `target="_blank"` links; equivalent gestured `window.open()` requests; ungestured `window.open()` opening nothing; undeterminable and `about:blank` new-window requests being rejected; the probe WebView committing no navigation and leaving no profile state; the long-press **Open in browser** escape hatch; missing browser handlers; and absence of credential/header transfer to browser intents.
- Tests proving credentials are not used on cross-origin redirects, insecure pages, subframes, ambiguous forms, or after configuration removal.
- Basic-auth compatibility tests exercise the navigation gate against top-level, redirected, iframe, and early/late subresource challenges for each enabled provider version and verify that unlisted versions cancel every challenge.
- Form-fill transport tests verify exact-origin filtering, main-frame enforcement, one-shot capability and reply behavior, listener removal, navigation invalidation, and fallback when required WebMessage behavior is unavailable.
- Clipboard-fallback tests covering the offer conditions, separate username/password actions, explicit consent, sensitive metadata, redacted confirmation, timed/background clearing, preservation of newer clipboard content, dismissal, and all ineligible security contexts.
- Credential-origin tests cover cross-origin discovery redirects, explicit binding confirmation, discovery failure followed by first eligible commit, and invalidation/reconfirmation after editing the configured URL.
- Persistence tests for encrypted credential round trips, unavailable/invalidated Keystore keys, migration, backup exclusion, and record deletion.
- Restore tests verify installation-sentinel detection and interrupted normalization, that backup-eligible configuration restored without the excluded credential store is treated as having no credentials, and that restore normalization forces `ACTIVE` state, advances profile generations, clears icon references, and repairs `sortPosition` into a contiguous order.
- Isolation tests using two entries for the same origin to prove separation of cookies, local/session storage, IndexedDB, Cache Storage, HTTP cache behavior, service workers, HTTP-auth state, permission grants, and logout/session changes.
- Lifecycle tests proving that per-app web data persists across relaunch, deleting or clearing one profile leaves other profiles unchanged, and disposable discovery profiles leave no reusable state.
- Maintenance-state tests cover disabled, non-launching `CLEARING` rows in the main list and multi-app widgets, their labels, stable positions, and explanation shown on tap; removal tests verify immediate row disappearance and independent tombstone retry.
- Profile-generation state-machine tests cover the atomic reservation of active and retiring generations, process death before and after provider deletion, idempotent retry, and proof that cleanup never targets a newly reserved generation.
- Clear-data tests covering confirmation and cancellation; every profile data category; active-WebView shutdown and launch exclusion; fresh-profile recreation; preservation of native configuration, credentials, metadata, icons, order, and widgets; failure recovery; and isolation from all other configured apps.
- Discovery lifecycle tests cover cancellation, overlapping attempts, late callbacks, process death, and startup cleanup of orphaned disposable profiles.
- Permission tests cover site grant/deny persistence, repeated per-use prompts, Android-level denial/revocation, and the distinction between clearing site grants and operating-system permissions.
- Third-party-cookie tests covering the default-off state, warning acknowledgement, enable/disable behavior, persistence, same-site versus cross-site requests, retention until explicit data clearing, and isolation between configured apps using the same first- and third-party origins.
- Compatibility tests proving that an unavailable profile-isolation facility fails closed rather than reverting to the default WebView profile.
- Incompatible-provider removal tests verify immediate native-data and widget cleanup, creation of a non-secret backup-excluded tombstone, no incompatible profile-API call, and eventual deletion and tombstone removal after compatibility returns.
- Ordering tests for drag-and-drop, accessible move actions, cancel/save behavior, persistence across restart, append-on-add, stable position after edit/rename, and gap removal.
- Ordering persistence tests cover transactional rollback after interruption and deterministic repair of duplicate or gapped legacy positions.
- Widget tests for multiple instances, resizing, canonical-order updates with different selected subsets, record updates/removal, reboot restoration, and forged/stale app IDs.
- Widget-record tests for per-instance state creation, `ALL_APPS` versus `SELECTED_SUBSET` persistence, pruning of removed app IDs from selections, and deletion of the record when the launcher removes the instance.
- Accessibility checks for the main screen, editor, dialogs, errors, and widget configuration activity.
