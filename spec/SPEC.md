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
- **Resolved launch URL**: The URL actually opened after configuration metadata is applied. This is the effective manifest `start_url` when valid; otherwise it is the final discovery URL when available, or the configured URL.
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
- the resolved launch URL or host as secondary text.

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

- **Name**: required before saving, but may initially be populated from discovered metadata.
- **URL**: required; accepts HTTPS, plus HTTP only for exact `localhost` (or `localhost.` after canonicalization). If the user omits a scheme, the app prepends `https://`. Any other HTTP URL fails inline validation and cannot be saved.
- **Username**: optional.
- **Password**: optional, masked by default, with a temporary reveal control.
- **Login method**:
  - Automatic (default);
  - HTTP Basic only;
  - Login form only.
- **Allow third-party cookies**: off by default. Enabling it requires acknowledging a privacy warning that embedded content from other sites may store/read cookies and use them for cross-site tracking. The setting applies only to this configured app's isolated WebView profile.
- **Refresh site details**: reloads the URL and repeats metadata/icon discovery.
- **Save** and **Cancel** actions.
- **Clear cookies and site data** when editing an existing entry. This clears only that configured app's isolated WebView profile as specified below.
- **Remove** action when editing an existing entry. Removal requires a confirmation dialog and deletes its metadata, cached icon, stored credentials, and isolated WebView profile, including all cookies and site data. Existing widgets for the entry change to a non-launching “App unavailable” state until reconfigured or removed by Android.

Entering only one of username or password is invalid. Inline validation must explain the issue and prevent saving.

Selecting **Clear cookies and site data** requires confirmation. The confirmation explains that the web app will be signed out and its offline/site state will be lost, then lists the affected categories: cookies, DOM storage, IndexedDB/Web SQL, Cache Storage and HTTP caches, service workers, HTTP-auth state, permissions, form data, browsing history, and all other data in that app's isolated WebView profile.

On confirmation, Mobile Web Shell prevents new launches of that configured app, closes any active WebView using its profile, requests deletion of the entire profile, and creates a fresh empty profile for the next launch. A durable maintenance state makes the operation atomic from the user's perspective: the old profile is never launchable after clearing begins, and an interruption is resumed before launch. The UI reports accepted completion or a recoverable failure. It preserves the configured app record, name, URL, icon, manifest metadata, saved username/password, login settings, order, and widget assignments. It never clears or changes another configured app's profile.

### 5.3 Metadata preview and configuration flow

After a syntactically valid URL is entered, and whenever Refresh site details is selected, the app loads the page in a controlled WebView or equivalent WebView-backed discovery flow and shows progress. It then:

1. follows normal HTTP redirects, subject to the security rules in section 9;
2. records the final URL;
3. locates and fetches a linked Web Application Manifest if present;
4. resolves the manifest fields and icon candidates;
5. if no usable manifest icon exists, locates a linked page icon such as `rel="icon"`;
6. if no linked icon is usable, requests `/favicon.ico` from the final page origin;
7. falls back to the bundled default icon;
8. proposes a name using, in order: manifest `short_name`, manifest `name`, document title, final host;
9. displays the resulting name, URL, and icon for review before Save.

Discovered values never overwrite a name the user has manually edited unless the user explicitly confirms a refresh reset.

The Name field tracks whether its current value is discovered or user-edited. Refresh always replaces derived final URL, manifest, scope, colors, and icon values with the new discovery result. It displays any newly suggested name separately; a user-edited name changes only when the user selects **Use suggested name**. Changing the configured URL immediately invalidates the previous final URL, launch URL, scope, manifest snapshot, and credential origin before new discovery begins.

A discovery failure does not prevent saving if the URL itself is valid. The UI reports the failure and uses available fallbacks. Network discovery has a finite timeout and can be cancelled by leaving the screen.

If discovery has never succeeded, `finalUrl` is null, `launchUrl` is the normalized configured URL, and `scope` is that URL's origin. A later successful discovery replaces all three derived values atomically. If a refresh fails for an existing entry, the failed attempt is shown but the last successful derived metadata remains until the user saves a changed configured URL; saving a changed URL without successful discovery stores the preceding fallback values and discards metadata derived from the old URL.

Default discovery limits are: 15 seconds for the initial document, 10 seconds per manifest or icon resource, 30 seconds overall, 10 redirects per resource chain, 1 MiB decoded manifest text, 5 MiB encoded icon input, and 20 MiB aggregate response bodies per attempt. Limits are measured after content decoding except where explicitly called encoded, are configurable only at build time, and produce a non-fatal discovery warning followed by the normal fallback chain.

Each discovery attempt has a unique, non-reused disposable profile ID and generation token. Starting another attempt or leaving the screen closes its WebView and invalidates pending callbacks; stale results are ignored. Profile deletion begins only after the WebView is closed. On startup, the app removes orphaned disposable discovery profiles left by process termination. Concurrent attempts never share a profile.

## 6. Web app shell

### 6.1 Presentation

- The shell is a dedicated Android activity containing one system WebView.
- It has no app bar, address bar, navigation buttons, tab UI, overflow menu, or other browser chrome.
- Web content fills the usable application window.
- Content may draw beneath the status bar only if contrast and insets are handled correctly. It must not be obscured by either the Android status bar or the bottom system navigation bar/gesture area.
- The shell follows the manifest's supported `display` and color hints where practical. `fullscreen` may hide the status bar, but must never hide or cover the bottom system navigation area. The user must always retain a system escape/navigation affordance.
- Manifest display mapping is exact: `fullscreen` requests status-bar hiding subject to the preceding safety rule; `standalone`, `minimal-ui`, `browser`, and unknown/unsupported values all use the same chromeless shell with normal system bars. The shell never adds browser controls for `minimal-ui` or `browser`. Display does not change orientation or Android task behavior.
- The WebView uses the device's installed Android System WebView implementation and receives its behavior/security updates.

### 6.2 Navigation

- On system Back, if the WebView has a previous committed history entry, navigate back in the WebView.
- Otherwise, close the shell and return to the launcher/main screen or the previous Android task destination.
- Back gestures and hardware/software Back buttons have identical behavior.
- Same-origin navigation and navigation within manifest scope remain in the shell.
- An allowed HTTP(S) link that does not request a new browsing window remains in the current shell WebView, including when its destination is outside the configured app's scope. This supports non-PWA sites, authentication redirects, and cross-origin application flows. Navigating out of scope does not expand the origin allowed to receive configured credentials.
- A link that explicitly targets a non-web external application scheme is delegated only after user confirmation.
- Before any navigation or external delegation, reject a non-localhost plain HTTP destination. This applies to direct navigation, redirects, form submissions, pop-ups, `target="_blank"`, `window.open()`, and URLs that would otherwise be sent to the system browser. Show a non-fatal HTTPS-required error and keep the last safe page when possible.
- Unsupported or dangerous schemes are blocked. `javascript:`, `data:`, `file:`, and `content:` top-level navigations are not launched from untrusted page links.
- `intent:`, `mailto:`, `tel:`, and other installed-app links show a confirmation containing the destination type before Android resolves them. If no handler exists, show a non-fatal error.
- A user-initiated HTTP(S) link with `target="_blank"`, or an equivalent request for a new window, is evaluated against the configured app's scope. If the destination is within scope, it opens in the existing shell WebView because the shell does not provide tabs or additional windows. If the destination is outside scope, it opens in the system's default web browser through an Android view intent.
- New-window requests whose destination cannot be determined before navigation, including an initial `about:blank`, are rejected with a non-fatal message. They are not attached to the existing WebView or allowed to navigate later without policy evaluation.
- When delegating a URL to the system browser, Mobile Web Shell adds no credentials, Authorization or Cookie headers, referrer headers/extras, or private WebView-profile state. It rejects URLs containing userinfo and warns for destination URLs whose query or fragment appears to contain common token/credential parameters; it cannot guarantee that an arbitrary site-generated URL contains no secret. If no browser can handle the URL, show a non-fatal error and leave the shell on its current page.
- External schemes use an explicit allowlist. `mailto:` and `tel:` may be confirmed and resolved. `intent:` is parsed defensively: explicit components/packages and nested `file:`, `content:`, `javascript:`, `data:`, or disallowed-cleartext fallback URLs are rejected; any permitted HTTPS fallback is re-evaluated under the normal navigation policy. Other custom schemes are blocked unless added to the reviewed allowlist. Confirmation never grants a scheme permanently.

### 6.3 Loading and errors

- Show a minimal in-content loading indicator while the initial page loads. It disappears after the first committed visible page or an error.
- TLS errors are never bypassed. Show a local error screen with Retry and Close.
- TLS, network, and main-frame loading failures show a local error screen with Retry and Close and must not reveal stored credentials. HTTP response status pages, including 4xx and 5xx responses, remain site content and render normally unless WebView reports a transport-level failure.
- Renderer crashes show a recoverable error and allow reloading the WebView.
- Renderer-crash recovery disposes the crashed WebView and creates a new WebView attached to the same active profile. Retry loads the last eligible committed URL held in memory, or the resolved launch URL if none exists. A concurrent `CLEARING` or `DELETING` transition takes precedence: recovery is cancelled, the WebView is closed, and profile maintenance continues.
- Ordinary activity recreation retains the live WebView through a lifecycle-aware holder where practical instead of serializing page/form state. The app does not persist a WebView state bundle that may contain filled credentials or page form values. After process death it recreates the WebView and reloads the resolved launch URL under normal navigation and credential rules. Arbitrary committed navigation URLs are retained only in memory for renderer recovery and are never persisted for restoration.

### 6.4 Web capabilities

- JavaScript, DOM storage, cookies, and modern WebView defaults required by typical web apps are enabled.
- Third-party cookies are disabled by default for every configured app. They are enabled only for an app whose user has explicitly enabled **Allow third-party cookies** and acknowledged its privacy warning.
- Changing the setting affects future requests in that app's isolated profile. Disabling it prevents future third-party-cookie access but does not implicitly delete cookies already stored; the edit screen explains that **Clear cookies and site data** must be used to remove existing data.
- Enabling third-party cookies never makes cookies or any other web data visible to another configured app, even when both entries use the same URL or embedded third-party origins.
- Version 1 supports geolocation, camera, microphone, and file selection. Web notifications and client-certificate selection are unsupported and their requests are denied without hanging the shell. Geolocation, camera, and microphone require an exact-origin native confirmation; any applicable Android runtime permission is requested only after site-level consent. File selection uses the Android document chooser rather than a runtime permission and requires a user gesture for each invocation.
- A geolocation origin decision may be remembered in the configured app's profile until cleared; camera, microphone, and file-selection approval is requested for each use. Android app-level permissions remain controlled by the operating system. Clearing the WebView profile removes remembered site-level grants but does not claim to revoke Android app-level permissions; the edit screen explains this distinction.
- Mixed active content is blocked. HTTPS pages must not load executable HTTP subresources.
- Safe Browsing is enabled when supported.
- WebView debugging is disabled in production builds.
- After an explicit user action, an eligible HTTPS or loopback download URL may be handed to an Android system handler only when it requires no isolated cookies, Authorization header, POST body, or other profile state. Authenticated/profile-dependent downloads are reported as unsupported. The URL is subjected to the same external-delegation checks; declared MIME type and filename are treated as untrusted hints. Mobile Web Shell does not provide a downloads UI.

### 6.5 Per-app web-data isolation

Every configured app must run in its own persistent, isolated WebView profile. A configured app's web data must not be readable, writable, or reused by:

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
- Removing a configured app logically deletes its complete profile through the supported profile API. Flash-storage cryptographic erasure is not promised. Clearing data for one app must not clear or alter any other app.
- Android application sandboxing provides separation from other applications and browsers; Mobile Web Shell must additionally enforce separation among its own configured apps.
- If the installed Android System WebView cannot provide the required independent profile/storage mechanism, Mobile Web Shell must refuse to launch web apps and show an actionable compatibility error. It must never silently fall back to a shared profile.

The implementation requires stable AndroidX WebKit 1.9.0 or newer, `WebViewFeature.MULTI_PROFILE`, `WebViewCompat.setProfile`, and `ProfileStore`. Release builds pin the exact AndroidX WebKit dependency and carry a minimum provider-version constant established by the compatibility suite; the initial release must not ship until that constant and its tested provider matrix are recorded in the implementation documentation. Android 8 remains the minimum install/configuration version, but shell launch and WebView-backed discovery are available only when the installed provider both reports `MULTI_PROFILE` and meets the release's tested minimum. Otherwise the app shows the provider/version and actionable update instructions. If capability disappears or the provider is downgraded after configuration, all profile access, discovery, clear, and removal work is blocked without modifying stored data until compatibility returns. The default profile is never a fallback.

Profile lifecycle is durable state: `ACTIVE`, `CLEARING`, or `DELETING`, and each app record stores a monotonically increasing profile generation. Before clear or removal, the record enters the corresponding state transactionally, all launches are blocked, and all living WebViews and in-memory `Profile` references for the old generation are closed and released. The app then calls `ProfileStore.deleteProfile` for that generation. A `true` result accepts deletion; `false` is idempotent success because the named profile does not exist. Either result retires that profile name permanently; it is never reused. Clear increments the generation and returns the record to `ACTIVE`, so its next launch creates a uniquely named fresh profile. Removal deletes native data only after deletion acceptance/confirmed absence. Process restart resumes any non-active operation idempotently. Failure leaves launches blocked and offers Retry. A failed removal remains as a disabled “Pending removal” row in the main list and editor; its native configuration, encrypted credentials, and icon remain until retry succeeds, and removal cannot be cancelled after profile deletion has begun. Widgets show “App data maintenance” for clearing and “App unavailable” for deletion. The same configured app has at most one live shell activity, and a new launch brings that activity forward.

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

Invalid, unreachable, cross-origin-disallowed, or malformed manifests are ignored without preventing configuration. `start_url` and `scope` must resolve to permitted HTTP(S) URLs. A cross-origin `start_url` is not adopted unless allowed by applicable manifest rules; the configured/final URL is used instead.

If a document contains multiple eligible manifest links, the first one in document order whose `media` condition matches is used, consistent with Web Manifest processing. Link and manifest URLs honor the document base URL rules. Unsupported declared types do not make otherwise fetchable JSON unusable, but malformed content is rejected.

Manifest processing uses the final document URL as the document URL and the fetched manifest URL as the manifest URL. Relative manifest members are resolved against the manifest URL. The effective `start_url` is resolved and validated using the Web Manifest algorithm, must be same-origin with the document, and has its fragment ignored for scope matching. If it is absent or invalid, the final document URL is used. A valid explicit `scope` is resolved and applied according to the Web Manifest algorithm; an absent or invalid explicit scope uses the algorithm's default scope derived from the effective start URL. If no usable manifest applies, scope is the resolved launch URL's origin. The launch URL must be within the effective scope or falls back to the final document URL, then the configured URL.

Manifest discovery follows the current W3C Web Application Manifest processing model implemented by the selected WebView baseline. The document DOM is the authoritative source of eligible manifest links and their document order, resolved base URL, `media`, `type`, and `crossorigin` attributes. The manifest request uses CORS mode; credentials mode is `include` only for `crossorigin="use-credentials"` and is otherwise `same-origin`. Redirects are limited and each destination is re-evaluated under section 9. The final response URL is the manifest URL used to resolve relative members. Referrer policy follows the document/link policy, and Content Security Policy is honored by a WebView-executed fetch. A native client may be used only if it reproduces these observable rules and uses the same disposable discovery profile's cookie state; it must not use the runtime profile or a global cookie store.

Manifest metadata is snapshotted into local storage. It is refreshed only when the user edits/refreshed the entry or when a future background-refresh feature is introduced.

### 7.2 Icon selection and storage

- Prefer a manifest icon suitable for the target size, favoring `purpose: maskable` for Android adaptive presentation and a square SVG or high-resolution PNG when supported.
- Otherwise use a linked favicon, then `/favicon.ico`, then the bundled default.
- Reject images that exceed configured download or decoded-pixel limits, are not valid images, or use an unsupported format.
- Rasterize/normalize the selected source into app-owned PNG or WebP renditions appropriate for the launcher list and widget sizes while retaining a higher-resolution source rendition.
- Icons are stored in private application storage and referenced by the configured app's stable ID.
- Widget icons must be rendered from local storage and must not require network access during widget rendering.

Icon selection is deterministic. Supported inputs are PNG, WebP, JPEG, ICO, and non-scripted SVG. A resource is rejected above 5 MiB encoded, 16 million decoded pixels, or 4096 pixels on either raster dimension. SVG is rendered with external resources, scripts, animation, and network access disabled. The app produces 48, 72, 96, 144, 192, 288, and 512 px square renditions, preserving transparency and center-fitting non-square artwork without cropping. For adaptive presentation it first considers icons declaring `maskable`, then `any`; monochrome-only candidates are not selected. Within a purpose class, candidates are ordered by: exact declared square size at or above 512 px; smallest square size above 512 px; largest square size below 512 px; `any`; then source document order. SVG `any` counts as scalable and precedes raster sizes within the same purpose class. A candidate whose decoded dimensions contradict its declaration is ranked by decoded dimensions. Fetch or decode failure advances to the next candidate. Linked page icons use the same size ordering, followed by `/favicon.ico`.

## 8. Authentication

### 8.1 General rules

- Credentials are optional and belong to exactly one configured app.
- Credentials may be transmitted only to a secure context as defined in section 4.
- Credentials must never be injected into an HTTP page, a page with a TLS error, a non-loopback cleartext redirect, or a different origin from the credential origin.
- The initial credential origin is the origin of the final secure URL observed during configuration. If that cannot be established, credentials remain stored but disabled until a secure origin is confirmed.
- When discovery redirects across origins, the UI shows the resulting credential origin and requires explicit confirmation before enabling credentials for it. If credentials were saved while discovery could not establish a secure final origin, the first successful eligible top-level commit proposes that exact origin for explicit user confirmation; credentials remain disabled until confirmed. Changing the configured URL clears the binding and requires confirmation again.
- Credential binding has explicit persisted states: `UNBOUND`, `PENDING_CONFIRMATION`, and `CONFIRMED`. `proposedCredentialOrigin` is present only while pending; `credentialOrigin` is present only when confirmed. Save is allowed in any state, but credentials can be used only in `CONFIRMED`. A same-origin successful discovery may be confirmed inline on Save; a cross-origin result requires a separate confirmation naming both origins. A first eligible runtime commit after failed discovery sets a pending proposal and opens the same confirmation UI. Rejecting a proposal returns to `UNBOUND`. Changing the configured URL atomically clears both origins and returns to `UNBOUND`.
- Any cross-origin authentication use requires a separate, explicit configuration; a redirect alone never expands credential scope.
- Logs, analytics, crash reports, UI error messages, backups, and saved WebView state must not contain plaintext credentials or Authorization headers.

### 8.2 HTTP Basic authentication

Reactive mode:

- Supply credentials only for a Basic challenge correlated to the pending top-level navigation to the exact confirmed credential origin. The pending navigation's canonical HTTPS scheme, host, and effective port are recorded before loading; the callback host must match it, and the challenged realm is recorded only for retry suppression, not for expanding credential scope. Subresource and iframe challenges are cancelled. If the pinned provider cannot prove that a callback belongs to that top-level navigation and effective port, Basic authentication is disabled for that provider and the challenge is cancelled.
- Do not persist credentials in WebView's global HTTP-auth database.
- Cancel the challenge when the context is ineligible. Supply credentials at most once for the tuple of committed-document generation, origin, and realm; a repeated challenge for that tuple is treated as rejection, cancelled, and shown as a non-sensitive error.

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

Credential values are delivered through a temporary AndroidX WebKit WebMessage channel restricted to the exact confirmed origin and the current top-level document. The channel name and per-document capability are random and non-reused; it is installed only after an eligible secure commit, accepts no page-to-native commands or credential requests, and is removed when filling completes, times out, navigates, errors, or the WebView closes. Native code sends the values as message data rather than concatenating them into executable JavaScript source. The bootstrap script contains only fixed code and the random capability, validates one message, writes only the already-selected fields, clears its references, and tears itself down. Provider versions unable to enforce the allowed-origin and document-lifetime restrictions do not support form filling and use the eligible clipboard fallback instead.

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

- Encrypt credential values using a non-exportable Android Keystore-backed key and authenticated encryption.
- Store ciphertext, nonce/IV, version, and the app record association in private app storage. Never store plaintext credentials in preferences, databases, files, logs, or process-persistent caches.
- Store credential ciphertext and encryption metadata in a separate private database or file that is excluded in full from backup and device-to-device transfer. The ordinary configuration store contains only a non-secret credential-presence flag or opaque reference. A restored configuration whose secret record is absent is treated as having no configured credentials and explains this when edited.
- Exclude credential data and key material from Android cloud backup and device-to-device transfer. Non-secret app configurations may be backed up only if credentials are removed from the restored record.
- Clear temporary character arrays/byte buffers when practical after use and avoid retaining credentials longer than needed for an eligible authentication operation.
- A device without a usable secure keystore may save apps without credentials but must refuse credential storage with a clear explanation.

## 9. Network and transport security

- Cleartext network traffic is disabled at the Android network-security-config level except for an explicit, non-subdomain `localhost` rule. The canonical `localhost.` spelling maps to that same rule after removal of one terminal dot.
- URL security classification uses one standards-based parser. Userinfo is rejected. Scheme and host are canonicalized and default ports are normalized before origin comparison. Plain HTTP is allowed only for exact `localhost` (and `localhost.` after removing one terminal dot). Localhost subdomains, all IPv4 and IPv6 literals, alternate numeric spellings, lookalike names, and DNS aliases are not treated as permitted loopback.
- A non-localhost plain HTTP URL must not be saved, loaded in a WebView, followed as a redirect, fetched for manifest/icon discovery, or delegated to an external application. There is no user override. The app shows that HTTPS is required.
- Android Network Security Config is the fail-closed enforcement layer for disallowed cleartext traffic. WebView navigation callbacks provide early cancellation and user-facing errors but are not the sole enforcement mechanism. The release's provider matrix records, for direct links, redirects, GET/POST form submissions, pop-ups, and external intents, whether the pinned provider plus network configuration can guarantee rejection before payload transmission. A flow that cannot meet that guarantee is disabled for the affected provider rather than treated as best-effort. Tests verify every enabled flow and confirm that prohibited requests transmit no request bytes.
- HTTPS-to-HTTP downgrade redirects are blocked, except when the resulting HTTP URL is a permitted loopback/localhost origin. A blocked redirect never inherits credentials, headers, referrer secrets, or POST data into a subsequent request.
- Certificate errors, hostname mismatches, expired certificates, and untrusted certificate chains cannot be overridden by the user.
- Metadata and icon fetching follows the same transport restrictions as shell navigation.
- Apply the concrete discovery limits in section 5.3. Shell main-frame navigation uses a 30-second initial-load error timeout but does not impose an application-level response-size limit; provider/network limits still apply.
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
| `configuredUrl` | Normalized URL entered by the user |
| `finalUrl` | Final URL observed during discovery |
| `launchUrl` | Valid manifest start URL or fallback URL |
| `scope` | Valid manifest scope or origin fallback |
| `credentialOrigin` | Exact eligible origin for authentication |
| `credentialBindingState` | `UNBOUND`, `PENDING_CONFIRMATION`, or `CONFIRMED` |
| `proposedCredentialOrigin` | Exact eligible origin awaiting confirmation, otherwise null |
| `loginMethod` | Automatic, Basic only, or form only |
| `allowThirdPartyCookies` | Per-app boolean, default false |
| `profileState` | Durable `ACTIVE`, `CLEARING`, or `DELETING` lifecycle state |
| `profileGeneration` | Monotonically increasing generation used in the private profile name |
| `credentialReference` | Optional opaque reference/presence marker for a secret record outside this database |
| `manifestSnapshot` | Sanitized supported manifest metadata |
| `iconReference` | Private local icon asset reference |
| `themeColor` | Optional validated color |
| `backgroundColor` | Optional validated color |
| `sortPosition` | Persisted position in the canonical main-screen and widget ordering |
| `createdAt`, `updatedAt` | Local timestamps |

Passwords, Authorization headers, cookie contents, and arbitrary page HTML are not part of the ordinary configuration database.

The separate backup-excluded credential store contains records keyed by the opaque credential reference with the configured-app ID association, encrypted username and password ciphertexts, nonces/IVs, authentication tags where separate, and encryption-format/key versions. It is never joined into backup-eligible configuration exports.

Each configured-app record and profile generation map to a private WebView profile identifier derived from the opaque app ID plus generation. Profile identifiers must not expose credentials or URLs, and retired identifiers are never reused. Profile data is stored separately from the ordinary configuration database and is managed as an indivisible per-app lifecycle resource.

## 12. Architecture constraints

- Native Android implementation, preferably Kotlin.
- AndroidX/Jetpack components and Material UI for native screens.
- One activity or equivalent shell destination launches by stable configured-app ID, never by accepting an untrusted raw URL intent.
- Structured persistence, preferably Room, for non-secret configuration and metadata.
- Keystore-backed encryption boundary for credentials as specified in section 8.5.
- Work compatible with current Android background-execution and widget update limits; widget refreshes should be event-driven rather than frequent polling.
- Web content is not granted a general-purpose JavaScript-to-native bridge. Any future bridge must be narrowly scoped, origin-checked, and separately threat-modeled.
- Exported activities, receivers, and services are minimized and protected. Widget entry points validate all IDs and inputs.
- The architecture must use a WebView capability that provides genuinely separate data stores per configured app. Clearing selected cookie types or intercepting storage APIs is not an acceptable substitute for profile isolation.
- Components that render widgets or native configuration UI must not initialize the default/global WebView data store.

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
- Removing a configured app deletes its app-specific stored metadata, icons, credentials, and complete isolated WebView profile.
- The required per-app **Clear cookies and site data** action deletes the selected app's complete isolated WebView profile without deleting its native configuration, stored credentials, metadata, icon, order, or widget assignments. It must not affect other configured apps.

## 15. Functional acceptance criteria

1. A user can add and save a valid HTTPS URL with a name and no manifest, then launch it from the main list.
2. The shell shows no browser chrome and its content is not obscured by the bottom system navigation area.
3. After two same-WebView navigations, system Back returns to the previous page; at the first history entry, system Back closes the shell.
4. Long-pressing a configured app opens an editor that can update or remove that app.
5. A manifest-backed site supplies its valid name, launch URL, scope, colors, and best available icon as defaults without requiring the manifest to exist.
6. A non-manifest site uses its document title and favicon when available, and the bundled default icon otherwise.
7. Stored icons remain visible in the main screen and widgets while offline.
8. HTTP Basic credentials are supplied after an eligible challenge only for the exact configured secure origin.
9. HTTP Basic credentials are never added proactively; a cross-origin redirect cannot receive them from Mobile Web Shell.
10. On a conventional unambiguous HTTPS login page, configured form credentials are filled but the form is not automatically submitted.
11. No credential is filled or sent after navigation to HTTP, a TLS-error page, a non-loopback cleartext URL, a cross-origin page, or an iframe.
12. Credentials are encrypted at rest with a Keystore-backed key and are excluded from backup.
13. The resizable widget launches each visible selected app and reflects configuration changes.
14. Each single-app widget instance launches its selected app and uses its stored icon and name.
15. A widget referring to a removed record cannot launch a stale URL and instead opens the main screen in an unavailable state.
16. Malformed manifests, missing favicons, offline discovery, renderer termination, and ordinary network errors are recoverable and do not crash the application.
17. Production builds reject certificate errors, block mixed active content, enable Safe Browsing where available, and disable WebView debugging.
18. Two configured entries for the same origin can simultaneously retain different authenticated sessions, cookies, local storage, IndexedDB, caches, and service workers without observing or modifying each other's data.
19. Configuration-time discovery leaves no cookies, site storage, cache, authentication state, or service-worker registration in the configured app's runtime profile.
20. Removing or clearing the data of one configured app removes its complete WebView profile without changing any other configured app's stored or in-memory web data.
21. On a device whose System WebView lacks the required isolation capability, the app reports incompatibility and does not launch using a shared data store.
22. An in-scope `target="_blank"` link opens in the existing shell, while an out-of-scope `target="_blank"` link opens in the system default browser without transferring credentials or isolated profile data.
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

## 16. Required test coverage

- Unit tests for URL normalization, secure-context classification, origin matching, manifest resolution, scope checks, icon selection, and login-form candidate scoring.
- URL tests cover userinfo rejection, default-port normalization, permitted `localhost` and its trailing-dot form, and rejection of localhost subdomains, canonical IPv4 in 127/8, alternate numeric IPv4 spellings, IPv6 loopback, IPv4-mapped IPv6, lookalike hosts, and DNS aliases.
- Instrumented WebView tests using controlled HTTPS and loopback servers for navigation history, Basic challenges, redirects, form fill boundaries, TLS failures, and mixed content.
- Transport-policy tests cover permitted exact-localhost HTTP and rejected HTTP configuration, links, redirects, form submissions, manifests, icons, pop-ups, and external intents for every other host form.
- Navigation tests for ordinary out-of-scope HTTPS links remaining in the shell; in-scope and out-of-scope `target="_blank"` links; equivalent `window.open()` requests triggered by a user gesture; missing browser handlers; and absence of credential/header transfer to browser intents.
- Tests proving credentials are not used on cross-origin redirects, insecure pages, subframes, ambiguous forms, or after configuration removal.
- Clipboard-fallback tests covering the offer conditions, separate username/password actions, explicit consent, sensitive metadata, redacted confirmation, timed/background clearing, preservation of newer clipboard content, dismissal, and all ineligible security contexts.
- Credential-origin tests cover cross-origin discovery redirects, explicit binding confirmation, discovery failure followed by first eligible commit, and invalidation/reconfirmation after editing the configured URL.
- Persistence tests for encrypted credential round trips, unavailable/invalidated Keystore keys, migration, backup exclusion, and record deletion.
- Restore tests verify that backup-eligible configuration restored without the excluded credential store is treated as having no credentials.
- Isolation tests using two entries for the same origin to prove separation of cookies, local/session storage, IndexedDB, Cache Storage, HTTP cache behavior, service workers, HTTP-auth state, permission grants, and logout/session changes.
- Lifecycle tests proving that per-app web data persists across relaunch, deleting or clearing one profile leaves other profiles unchanged, and disposable discovery profiles leave no reusable state.
- Clear-data tests covering confirmation and cancellation; every profile data category; active-WebView shutdown and launch exclusion; fresh-profile recreation; preservation of native configuration, credentials, metadata, icons, order, and widgets; failure recovery; and isolation from all other configured apps.
- Discovery lifecycle tests cover cancellation, overlapping attempts, late callbacks, process death, and startup cleanup of orphaned disposable profiles.
- Permission tests cover site grant/deny persistence, repeated per-use prompts, Android-level denial/revocation, and the distinction between clearing site grants and operating-system permissions.
- Third-party-cookie tests covering the default-off state, warning acknowledgement, enable/disable behavior, persistence, same-site versus cross-site requests, retention until explicit data clearing, and isolation between configured apps using the same first- and third-party origins.
- Compatibility tests proving that an unavailable profile-isolation facility fails closed rather than reverting to the default WebView profile.
- Ordering tests for drag-and-drop, accessible move actions, cancel/save behavior, persistence across restart, append-on-add, stable position after edit/rename, and gap removal.
- Ordering persistence tests cover transactional rollback after interruption and deterministic repair of duplicate or gapped legacy positions.
- Widget tests for multiple instances, resizing, canonical-order updates with different selected subsets, record updates/removal, reboot restoration, and forged/stale app IDs.
- Accessibility checks for the main screen, editor, dialogs, errors, and widget configuration activity.
