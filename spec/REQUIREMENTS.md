# Mobile Web Shell — Functional Requirements

Platform: native Android, minimum Android 12 (API 31), target the latest stable SDK.
Application name: Mobile Web Shell.

This document states *what* the app must do. How it is built, meaning WebView profile mechanics,
persistence, journals and provider compatibility, is described in [ARCHITECTURE.md](ARCHITECTURE.md).

## 1. Purpose

Mobile Web Shell is a native Android launcher and full-screen runtime for web applications. The
user saves web apps by URL and opens them in the Android System WebView with no browser controls:
no address bar, tabs, menus or navigation buttons. Only HTTPS is accepted, never plain HTTP
(including `localhost` and loopback addresses). A Web Application Manifest is optional; when
present, its metadata improves the saved app's name, icon, theme, launch URL, scope and display.

It is not a general-purpose browser. It is a user-configured container for trusted web apps.

## 2. Goals

- A simple list from which configured web apps are launched.
- Each web app runs in a dedicated, chromeless system WebView.
- Android system Back navigates within the web app.
- Web app metadata and icons are discovered and kept locally.
- Credentials are fetched on demand from MyPass for HTTP Basic authentication or a conventional HTML login form.
- A multi-app list widget and a single-app launcher widget.
- Credentials are never persisted by Mobile Web Shell and never transmitted in insecure contexts.
- Predictable behavior for sites without PWA metadata.

## 3. Non-goals

- General browser features: editable address bar, tabs, bookmarks, history UI, downloads management, incognito.
- Syncing configurations between devices; managing credentials (that is MyPass's or the user's job).
- Acting as the default browser or handling arbitrary links globally.
- Bypassing CAPTCHAs, MFA, passkeys, federated identity or anti-automation controls.
- Guaranteed automatic form login on every site (form login is best-effort).
- Installing PWAs as Android packages.
- A separate browser or rendering engine.

## 4. Terminology

- **Configured app**: a web application entry saved by the user.
- **Shell**: the chromeless Android screen containing a WebView.
- **Resolved launch URL**: the URL actually opened. It is the manifest `start_url` when valid and within scope, otherwise the final discovery URL, otherwise the configured URL (see §8.1).
- **Secure context**: an HTTPS origin with a valid TLS connection. Redirects to other origins are evaluated independently.
- **App scope**: the effective manifest scope (including the Web Manifest default-scope algorithm). Without a usable manifest, it is the resolved launch URL's origin.
- **User-initiated / explicit user action**: directly caused by a trusted WebView user gesture as reported by the provider, with no grace period that would let later script actions inherit that status.
- **Allowed HTTPS URL**: a syntactically valid, userinfo-free HTTPS URL whose host passes the rules in §10.
- **Main-frame visible commit**: the moment the WebView reports that the current main-frame navigation became visible (`onPageCommitVisible`).
- **Credential-like URL parameter**: a case-insensitive query or fragment key equal to `access_token`, `autostarttoken`, `auth`, `authorization`, `code`, `credential`, `jwt`, `key`, `password`, `secret`, `session`, `sessionid`, `sig`, `signature`, `ticket` or `token`, or that begins or ends with one of those words separated by `_`, `-` or `.`. This is a warning heuristic only, never proof that a URL is safe.

### 4.1 Sensitive-URL consent

- Before a configured, final or launch URL containing a credential-like parameter is first persisted, show a redacted warning that names only the matching keys (never values) and requires explicit consent.
- The URL is never silently modified.
- Consent applies to that exact serialized URL; a changed sensitive URL needs consent again.
- The same warning and fresh consent are required before delegating such a URL (HTTPS or allowlisted custom scheme) to another app. For a BankID handoff, the BankID confirmation covers its expected `autostarttoken`; warnings for any other credential-like or unreadable parameters appear in that same dialog.
- The warning explains that device backup or transfer may include these URLs.
- If the user declines: a typed configured URL blocks Save; a discovered final or launch URL is discarded and the configured URL is used instead.

## 5. Main screen

- A vertically scrollable list of all configured apps. Each row shows the stored icon (or bundled default), the configured name, and the resolved launch URL as secondary text. The URL is elided in the middle so the scheme and host stay visible.
- Tap a row to launch the app. Long-press a row to open its editor.
- **Reorder** mode: drag rows by a visible handle, with equivalent accessibility move-up/move-down actions. **Done** saves; **Cancel** restores the previous order without writing anything.
- A clearly visible **Add** action, preferably a Material FAB labelled "Add web app".
- An HTTPS URL field and **Open URL without saving** action launch a typed URL directly in a shell. The same URL validation as the editor applies. Opening it does not run discovery or add a configured app.
- An empty state explains that no apps are configured and offers Add.
- The manual order is the single canonical order for the main list and all widgets. New apps are appended to the end. Removing an app closes the gap. Editing or renaming an app does not move it.
- Duplicate names and duplicate URLs are allowed.
- An app in `CLEARING` state (see §7.6) stays at its position as a disabled, non-launching row labelled "Clearing site data…". Tapping it shows a short explanation.

## 6. Add / edit screen

### 6.1 Fields and actions

- **Name** (required). Leading and trailing Unicode whitespace is trimmed. It must contain at least one non-whitespace character, must not contain NUL, bidi-control or non-printing control characters, and is limited to 128 code points. Discovered names are normalized the same way; an invalid result falls through to the next name source. While Name is empty or still holds the host it was last filled with, and the user has not edited it, it is filled synchronously from the canonical host whenever the URL validates.
- **URL** (required, HTTPS only):
  - Leading and trailing ASCII whitespace is trimmed.
  - Scheme-less input of the form authority[path][query][fragment] with a valid DNS or IDN host and optional decimal port gets `https://` prepended (`example.com/path` → `https://example.com/path`). IDN hosts are converted to canonical ASCII.
  - IP-literal hosts are accepted only with an explicit `https://`.
  - Validation rejects embedded whitespace, userinfo, an empty host, an invalid or out-of-range port, an ambiguous colon form or a malformed authority. Input is never guessed at.
  - Any `http://` URL, including `localhost`, fails inline validation.
  - Every persisted URL (configured, final, launch, scope, manifest, icon) is limited to 8 KiB after canonical serialization. Overlong typed input fails validation; overlong discovered values are ignored and their normal fallback is used.
- **MyPass integration status**: read-only indication of whether MyPass is available. Unavailability never blocks saving or launching.
- **Allow third-party cookies**: off by default. Turning it on requires acknowledging a privacy warning about cross-site tracking. It applies only to this app. The editor explains that turning it off does not delete stored cookies; use Clear cookies and site data for that.
- **Refresh site details**: reloads the URL and repeats discovery.
- **Use suggested name**: offered only when discovery proposes a name that differs from a user-edited Name. Selecting it replaces the Name and marks it as discovered again.
- **Save** and **Cancel**. Cancel leaves the persisted record unchanged.
- **Clear cookies and site data** (existing apps only), see §6.3.
- **Remove** (existing apps only), see §6.4.

### 6.2 Changing the URL of an existing app

- If the new URL's origin differs from the saved URL's origin, Save asks for confirmation. The confirmation explains that the app will be signed out of the previous site and its site data removed. On confirmation, the app's web data is discarded (as for Clear, §6.3) and the next launch starts from the new URL with empty web data.
- The new URL and metadata are authoritative from Save onward and never roll back, even after a crash.
- Derived metadata from the old URL is invalidated when the draft URL changes. If discovery of the new URL reached a visible commit, its results (or per-stage fallbacks) are saved. Otherwise the configured URL supplies launch URL and scope, and the bundled default icon is used until discovery succeeds.
- A same-origin change of path, query or fragment keeps the web data and the stored icon, unless successful icon discovery supplies a replacement.

### 6.3 Clear cookies and site data

- Requires a confirmation that says the app will be signed out and lose its offline/site state. It lists the categories: cookies, DOM storage, IndexedDB/Web SQL, Cache Storage and HTTP caches, service workers, HTTP-auth state, permissions, form data, browsing history, and all other data in the app's isolated web profile.
- On confirmation: launches of that app are blocked, any live shell for it is closed, and its web data is deleted. The operation is atomic from the user's perspective and resumes after interruption. The old data is never launchable again once clearing begins.
- The UI distinguishes "started" (`CLEARING`), "done" (`ACTIVE`) and "recoverable failure, Retry".
- If deletion must be deferred until the next app restart, the record returns to usable with fresh empty web data, and the UI says the old data will be deleted at next restart.
- Configuration, name, URL, icon, manifest metadata, order and widget assignments are preserved. MyPass entries and other apps are never affected.
- Site permission decisions are removed with the web data. Android app-level permissions are not revoked; the editor explains this distinction.
- While an app is `CLEARING`, its editor allows name and third-party-cookie edits, same-origin URL edits, Retry and Remove. It disables Clear and origin-changing URL edits, with a short explanation.

### 6.4 Remove

- Requires confirmation.
- Immediately makes the entry unlaunchable, closes its live shell, and deletes its configuration, metadata, cached icon and site permission decisions. Widgets are updated.
- Its web data is deleted immediately when possible. Otherwise deletion is completed later (after restart, or when WebView compatibility returns), and the UI states that local removal is complete but web data cleanup is pending. Failure never restores the removed app.
- Removal is available even when the installed WebView is incompatible.
- Single-app widgets for the removed app show the default icon and "App unavailable", and tapping them opens the main screen. Multi-app widgets drop the app.
- MyPass entries are never modified.

## 7. Metadata discovery (configuration time)

### 7.1 Triggers and flow

- Discovery runs after a syntactically valid URL has been unchanged for 500 ms, when the URL field loses focus, and on **Refresh site details**. Progress is shown.
- Steps:
  1. Follow redirects (subject to §10), including same-origin redirect pages (see §7.2).
  2. Record the final URL.
  3. Fetch a linked Web Application Manifest, if any.
  4. Resolve manifest fields and icon candidates.
  5. Without a usable manifest icon, use a linked page icon (`rel="icon"`).
  6. Without that, use `/favicon.ico` from the final page origin.
  7. Otherwise, use the bundled default icon.
  8. Suggest a name from, in order: manifest `short_name`, manifest `name`, document title, final host.
  9. Show the resulting name, URL and icon for review before Save.
- As soon as the URL is valid, an empty, non-user-edited Name is filled with the canonical host, so a network failure never leaves a new entry without a name.
- Discovered values never overwrite a user-edited Name unless the user selects **Use suggested name**. A newly suggested name is shown separately.
- Save never waits for discovery. It cancels any in-flight attempt and uses the stages already completed, with fallbacks for the rest.
- Leaving the screen cancels discovery.

### 7.2 Failure and fallbacks

- A discovery failure never blocks saving a valid URL. The UI reports the failure and uses fallbacks.
- Each stage (document, DOM, manifest, colors, name, icon) succeeds or falls back independently.
- The final URL is the first committed main-frame URL when it is same-origin with the configured URL. A later stage failure does not discard it.
- A committed same-origin document is a redirect page, not final, when it declares an immediate (zero-delay) meta refresh to another same-origin document, or starts another same-origin main-frame navigation before its DOM snapshot is taken. Discovery follows it, at most 5 times per attempt, and takes the final URL and metadata from the document it leads to. The redirect page's commit is used only if the followed navigation fails or times out.
- If the first commit is on a different origin (for example an identity-provider login page), no final URL and no metadata are taken from it. A non-fatal notice says the site redirected to another origin.
- With no commit: launch URL = normalized configured URL, scope = its origin, and `/favicon.ico` is requested from the configured origin. An uncommitted redirect URL is never treated as final.
- A committed document without an eligible manifest link counts as a successful, empty manifest stage and clears the previous manifest snapshot.
- A failed Refresh of an unchanged, persisted URL keeps the last successful snapshot. A new or changed URL uses fallback values.
- Changing the URL immediately invalidates the draft's previous final URL, launch URL, scope and manifest snapshot.

### 7.3 Limits

The following defaults can be changed only at build time. Exceeding a limit gives a non-fatal warning and the normal fallback.

| Limit | Default |
| --- | --- |
| Initial document load | 15 s (spans redirects and redirect pages; stops at first final commit or terminal error) |
| Each manifest or icon resource | 10 s |
| Overall attempt | 30 s (never resets) |
| Redirects per resource chain | 10 |
| Manifest text (decoded) | 1 MiB |
| Icon input (encoded) | 5 MiB |
| Aggregate response bodies per attempt | 20 MiB |
| Manifest links considered | first 16 |
| Manifest icon candidates | first 128 |
| Linked page-icon candidates | first 128 |

Timers pause while a MyPass request started by that attempt is outstanding.

### 7.4 Authentication during discovery

- Discovery may answer an HTTP Basic challenge through MyPass only for a top-level navigation whose origin equals the origin of the typed configured URL. The MyPass lookup uses the exact challenged request URL (after same-origin redirects) and the realm.
- Challenges after a cross-origin redirect, for subresources, for iframes, and proxy-authentication challenges are cancelled.
- Only Refresh site details or URL-field focus loss may request MyPass. A debounce-triggered attempt cancels every challenge and shows "Authentication required – select Refresh site details."
- Within the same attempt, the returned credential may be reused once per request for a same-origin, same-realm manifest, icon or `/favicon.ico` Basic `401`. It is never reused after a cross-origin redirect.
- The credential is discarded when the attempt ends. Discovery never fills login forms.
- If MyPass is unavailable or returns nothing, discovery continues unauthenticated.
- Discovery never leaves web data (cookies, storage, cache, auth state, service workers) in any configured app's runtime web data.

## 8. Web App Manifest and icons

### 8.1 Manifest

- Recognized members: `name`, `short_name`, `start_url`, `scope`, `display`, `theme_color`, `background_color`, and `icons` (`src`, `sizes`, `type`, `purpose`).
- Invalid, unreachable, cross-origin-disallowed or malformed manifests are ignored without blocking configuration.
- With multiple manifest links, only the first in document order whose `media` matches is fetched. Failure does not fall through to a later link.
- Processing follows the W3C Web Application Manifest model: the document URL is the final document URL, members are resolved against the manifest URL, and document base URL rules apply.
- `start_url` must be an allowed HTTPS URL, same-origin with the document. Otherwise the final document URL is used, then the configured URL. Its fragment is ignored for scope matching.
- `scope`: a valid explicit scope is used. Otherwise the Web Manifest default scope derived from the effective start URL is used. An explicit scope that does not contain the start URL is invalid and replaced by the default scope; the rest of the manifest stays usable.
- **Within scope** is a Web Manifest URL-prefix test: equal origins, and the candidate path starts with the scope path (a plain string prefix). Query and fragment are ignored. An origin-fallback scope has path `/`.
- The launch URL must be within scope. Otherwise it falls back to the final document URL, then the configured URL.
- Stored snapshot: only the supported members. Strings are sanitized (valid Unicode, NUL and non-printing control characters removed), display strings are capped at 256 code points, URLs at 8 KiB, and at most 128 icon candidates are kept. Unknown members and page HTML are never stored.
- The snapshot is refreshed only on user edit or Refresh.

### 8.2 Icons

- Supported formats: PNG, WebP, JPEG, ICO and non-scripted SVG. SVG is rendered with external resources, scripts, animation, fonts and network disabled.
- A candidate is rejected when it exceeds 5 MiB encoded, 16 million decoded pixels or 4096 px on either raster dimension, or when it is invalid, unsupported or fails to decode. Error diagnostics never expose source content.
- Source priority: manifest icons with purpose `any`, then purpose `maskable` (monochrome-only candidates are not used), then linked page icons (first 128), then `/favicon.ico`, then the bundled default.
- Ranking within a class:
  1. Scalable candidates (SVG or `sizes="any"`).
  2. Exactly 512 px square.
  3. The smallest square size above 512 px.
  4. The largest square size below 512 px.
  5. No usable declared size.
  6. Document order breaks ties.
  A multi-token `sizes` uses the candidate's best token; invalid tokens are ignored. Candidates are tried one at a time in rank order, and the first that fetches and decodes within limits wins. Actual dimensions do not re-rank.
- Exactly one source is selected. From it, square 48, 72, 96, 144, 192, 288 and 512 px renditions are produced, preserving transparency and center-fitting non-square art without cropping. A `maskable` source is cropped to its safe zone (the central circle with 80% diameter) and rendered with a rounded-square mask.
- Icons are stored privately, keyed by the app's stable ID. The list and widgets render from local storage and never need the network.

## 9. Web app shell

- An ad-hoc shell uses the same navigation, presentation, error, retry and permission behavior as a saved shell. It has its own disposable web profile, which is retired when the shell closes, and does not use automatic MyPass Basic authentication or form fill. Site permission choices in this mode are not persisted.

### 9.1 Presentation

- A dedicated screen with one WebView and no browser chrome (no app bar, address bar, navigation buttons, tabs or overflow menu).
- Content fills the window and is never obscured by the status bar or the bottom navigation bar / gesture area. The shell draws edge-to-edge on all API levels and insets the web content.
- `display: fullscreen` may hide the status bar but never the bottom navigation area. `standalone`, `minimal-ui`, `browser` and unknown values all give the same chromeless shell with normal system bars. Display never changes orientation or task behavior.
- `theme_color` tints the status bar, navigation bar and Recents task color. Black or white system-bar icons are chosen by higher WCAG 2.1 contrast, and the manifest color is used only when that contrast is at least 4.5:1; otherwise the app theme's color is used. Alpha colors are composited over the active light/dark surface before measuring.
- `background_color` paints the window behind the WebView before first paint, unless invalid or fully transparent.
- Neither manifest color ever affects native configuration screens.
- Each configured app has its own Recents entry with that app's icon and name. Different apps can be live at the same time. Launching an app whose shell is already live brings it forward and never creates a second shell or discards its state.
- Name, icon and theme changes refresh the Recents entry on resume or on change.
- The shell uses the device's installed Android System WebView.

### 9.2 Back and reload

- System Back first dismisses a local error screen, returning to the last safe page when one remains. Otherwise it goes back in WebView history if there is a previous entry. Otherwise it closes the shell.
- Gesture Back and hardware/software Back behave identically.
- Pull-to-refresh: a downward drag starting while the page is at scroll top reloads the page. It is inactive when the page is not at the top, and a gesture that starts sideways stays with the page even if it drifts downward, so horizontal scrolling in the page is not interrupted. It is the only reload control on a loaded page, and it counts as an explicit reload (it allows a new form-fill attempt).

### 9.3 Navigation policy

- A link that does not open a new window stays in the shell, whether in scope or out of scope (this supports non-PWA sites, auth redirects and cross-origin flows). Leaving scope never by itself authorizes a MyPass request.
- Plain HTTP destinations are rejected before any navigation or delegation: direct links, redirects, form submissions, pop-ups, `target="_blank"`, `window.open()` and browser handoff. A non-fatal "HTTPS required" error is shown and the last safe page is kept when possible.
- `javascript:`, `data:`, `file:` and `content:` top-level navigations from page links are blocked.
- **New windows** (`target="_blank"` or a gestured `window.open()`; the two cannot be told apart):
  - The destination is determined before anything is sent.
  - An in-scope HTTPS destination loads in the existing shell.
  - An out-of-scope allowed HTTPS destination within another configured app's scope opens in that app's shell, using its active instance when one exists. Otherwise it opens in the system default browser.
  - A non-GET request, an undeterminable destination, or `about:blank` is rejected with a non-fatal message. A blank window is rejected even when its opener navigates it afterwards, and a window that has been rejected can never be routed later.
  - Routing a new window sends no request byte, commits no document for the destination, and leaves no web data in the app's profile.
  - The one exception is the opener's own origin. `window.open()` gives the opener access to the new window's initial blank document before Android reports the window, and that document has the opener's origin. The opener can therefore write its own origin's cookies and storage through it. This needs no authority beyond what the opener already has, and it cannot reach any other origin's data.
  - An ungestured `window.open()` opens nothing.
  - No second visible WebView or window is ever created.
- **Open in browser**: long-pressing a link offers it only for an allowed HTTPS URL, and it acts only on that deliberate choice.
- **System browser handoff**: no credentials, Authorization/Cookie headers, referrer, or web data are transferred. Userinfo URLs are rejected. Credential-like parameters trigger the redacted consent from §4.1 (Continue / Cancel). If no browser is available, show a non-fatal error and stay on the page.
- **External schemes**: the allowlist is `mailto:`, `tel:`, `sms:`, `geo:`, `market:` and `bankid:`. BankID's `https://app.bankid.com/?autostarttoken=…` app link is also delegated to Android, while other HTTPS links stay in the shell.
  - Delegation needs a confirmation naming the destination type. `bankid:` and the BankID HTTPS autostart link may ask for confirmation on any main-frame navigation, including redirects and script navigation; they are never delegated from a subframe. Other external schemes require a main-frame, non-redirect navigation with a trusted user gesture. A redirected or ungestured one is blocked with a non-fatal message and no confirmation.
  - If no handler is installed, show a non-fatal error.
  - Confirmation never grants a scheme permanently.
  - The BankID confirmation explicitly covers sending that sign-in link to another app. It is the only confirmation for a BankID handoff; any warning for other sensitive parameters appears in it. Other destinations still use the separate credential-like parameter warning from §4.1 when needed.
  - `intent:` and every other scheme are blocked with a non-fatal message and never passed to Android.
  - Each navigation blocked for its scheme (main frame or subframe, in the shell, a new window or discovery) is logged at info level with the scheme. Only a debug build also logs the full URL, since it may carry secrets.
  - Credential-like parameter scanning applies to each scheme's query and fragment components (for `mailto:`, the query after the address and any fragment), parsed as `&`-separated UTF-8 percent-decoded pairs without turning `+` into a space. Scheme payloads (recipient, number, coordinates, market path and BankID path) are not scanned. Malformed or ambiguous components give a conservative "unparseable" warning.

### 9.4 Loading and errors

- A minimal in-content loading indicator is shown during the initial load and disappears at first visible commit or on error.
- Initial load timeout is 30 s. It spans redirects without resetting, pauses while MyPass is outstanding, and on expiry shows the error screen. Late callbacks are ignored, and each Retry starts a fresh timer.
- TLS, network and main-frame failures show a local error screen with **Retry** and **Close**, which never reveals credentials. TLS errors are never bypassable.
- HTTP 4xx and 5xx pages render normally as site content.
- Retry after an initial failure loads the resolved launch URL. After a failed later navigation, Retry repeats it only if it was an idempotent GET, never POST or any request with a body. Otherwise it reloads the last safe committed GET, or the launch URL. Close finishes the shell.
- A renderer crash shows a recoverable error. Reload creates a fresh WebView with the same app data and loads the last committed URL held in memory, or the launch URL. A concurrent clear or remove takes precedence.
- Configuration changes (for example rotation) keep the live page where practical. After process death, the shell reloads the resolved launch URL. Page state, form values and visited URLs are never persisted.
- Changing the app's configuration while its shell is live:
  - An origin change, clear or remove closes the live shell.
  - A same-origin URL change takes effect on the next fresh shell.
  - A third-party-cookie change applies to later requests without a reload.

### 9.5 Web capabilities

- JavaScript, DOM storage, cookies and typical modern WebView defaults are enabled.
- Third-party cookies are off unless enabled per app (§6.1). Enabling them never exposes data to another configured app.
- Supported: geolocation, camera, microphone and file selection. Web notifications and client-certificate selection are denied without hanging.
- Geolocation, camera and microphone need an exact-origin in-app confirmation first, then the Android runtime permission if needed.
  - A geolocation decision (allow/deny) is remembered per exact origin, per app, until the app's web data is cleared. A remembered deny is applied silently. A remembered allow proceeds to the Android permission check. If the Android permission is permanently denied, the request is denied with a shortcut to the Android app settings.
  - Camera and microphone are asked every time. Concurrent requests for the same origin and capability are serialized.
  - File selection uses the Android document chooser and needs a fresh tap on a native confirmation showing the page’s origin every time.
- Mixed active content is blocked. Safe Browsing is on where supported. WebView debugging is off in production builds.
- Downloads (including `blob:` and `data:`) are unsupported: a non-fatal message is shown, the page stays, and nothing is handed to another app.

### 9.6 Per-app web data isolation

- Each configured app has its own persistent web data covering cookies, DOM/session storage, IndexedDB/Web SQL, caches, service workers, HTTP-auth state, client-certificate preferences, form/password data, permissions, history and all other WebView data.
- It is never readable, writable or reused by another configured app (even one with the same URL), by discovery, by any other WebView of Mobile Web Shell, or by other apps and browsers.
- Two entries for the same site can be signed in to different accounts at the same time.
- Web data persists across launches.
- Opening one app never touches another app's data.
- If the installed WebView cannot provide real per-app isolation, the app refuses to launch web apps and shows an actionable compatibility error with the provider, version, the specific reason it is blocked, and how to update. It never falls back to shared data. Configuration still works, and removal remains available.

## 10. Transport security

- Plain HTTP is never allowed for any host, with no user override. A plain HTTP URL is never saved, loaded, followed as a redirect, fetched during discovery or delegated. "HTTPS required" is shown instead. Cleartext is blocked at the OS network-config level as well.
- HTTPS hosts may be DNS names or IPv4/IPv6 literals, with normal certificate validation.
- URL canonicalization: WHATWG URL parsing; UTS #46 non-transitional IDNA with STD3 rules and IDNA errors rejected; lowercase ASCII host; one terminal dot removed; an empty path becomes `/`; default ports removed and non-default ports kept; userinfo rejected.
- HTTPS→HTTP downgrade redirects are blocked and never carry credentials, headers, referrer secrets or POST data forward.
- Certificate errors (hostname mismatch, expiry, untrusted chain) can never be overridden.
- Discovery fetching follows the same restrictions as shell navigation.

## 11. Authentication (MyPass)

### 11.1 General

- Mobile Web Shell never asks the user to enter or store credentials in its own UI. It fetches a credential from MyPass only when an eligible authentication operation needs it, and keeps it in memory only for that operation.
- The MyPass contract is [MyPass INTEGRATION.md](https://github.com/mikaelstaldal/mypass-android/blob/main/INTEGRATION.md): action `nu.staldal.mypass.action.FETCH_PASSWORD`, explicit package `nu.staldal.mypass`, and permission `nu.staldal.mypass.permission.FETCH_PASSWORD`. Extras: `nu.staldal.mypass.extra.URL` and `nu.staldal.mypass.extra.REALM` in; `nu.staldal.mypass.extra.RESULT_USERNAME` and `nu.staldal.mypass.extra.RESULT_PASSWORD` out. Release builds share MyPass's signing identity; debug builds use the shared debug key.
- A result counts only if it is `RESULT_OK` with both extras present. Anything else (a missing package, failed resolution, `ActivityNotFoundException`, `SecurityException`, cancellation, a missing extra, activity destruction, or the 60 s timeout) means no credential, and never causes a crash.
- MyPass being unavailable never blocks configuration, discovery, launch, navigation or unauthenticated use. Login forms stay usable for manual entry and Android autofill. At most a concise, non-blocking note is shown when automatic fill cannot use MyPass.
- Credentials are only requested or sent for a secure context, and never for TLS-error pages, cleartext pages, prohibited redirects or subframes.
- Credentials are never written to preferences, databases, files, saved state, the clipboard, WebView's HTTP-auth database, logs, analytics, crash reports, intents, widgets or backups. References are cleared as soon as the operation ends. The WebView's own in-memory per-app HTTP-auth cache after a successful answer is accepted; it is removed by clearing or removing the app.
- Only one MyPass request is outstanding per shell or discovery flow. A Basic challenge takes precedence over form fill for the same document. Requests are not queued across navigations. Stale or mismatched results are discarded.

### 11.2 HTTP Basic authentication

- This applies to any origin-server auth challenge the WebView reports; the WebView does not expose the scheme.
- Reactive only. Credentials are never sent proactively.
- Credentials are supplied only during the first top-level navigation of a fresh WebView, including its accepted same-origin redirects, before a document commits or a competing request begins. Later links, Back, reload and Retry on the same WebView never restore eligibility. Challenges from subresources, iframes or ambiguous situations are cancelled.
- A service-worker network request makes the profile ineligible for new Basic-auth lookups for the rest of the process, including replacement WebViews. Existing cached authentication may continue working; it is not a new MyPass lookup.
- Reopening an existing live shell does not restore eligibility. Closing the shell and launching a fresh one permits another initial attempt only if its profile remains eligible. Unsaved page state and live browsing history are lost on close. A public launch page followed by a protected link cannot initiate Basic auth; configure the protected page as the launch URL when needed.
- The MyPass lookup uses the exact top-level request URL (after redirects) and the challenge realm. There is no name-based lookup.
- A returned credential is supplied at most once per navigation, URL and realm. A repeated challenge counts as rejection: it is cancelled and a non-sensitive error is shown.
- There is no manual fallback. Without a credential, the challenge is cancelled and a non-sensitive "authentication unavailable" error is shown.
- A credential never follows a cross-origin redirect.

### 11.3 HTML login form filling (best effort)

- Runs only in the top-level frame of a committed secure page. The lookup URL is the exact committed document URL (URL only, no realm).
- For up to 10 s after commit, the page is watched for exactly one unambiguous login candidate: a visible, enabled password field plus an associated visible username/email/text field in the same form. Standards `autocomplete` hints take priority over conservative name/type heuristics.
- With zero or several candidates, or no credential returned, nothing happens and the form is left for manual entry.
- At most one form-fill request per committed document, unless the user explicitly reloads (pull-to-refresh).
- Only the selected fields are filled, and normal input/change events are dispatched. Hidden fields, cross-origin or sandboxed iframes, and fields outside the form are never filled. The form is never submitted automatically.
- Credentials are never exposed to page-visible logs, JavaScript interfaces, URLs or any other origin. They are delivered only to the exact committed origin and document that requested them.
- There is no clipboard fallback and no persistent native Retry control.

## 12. Widgets

### 12.1 Multi-app list widget

- A compact list of configured apps with icon and name. It is resizable in both directions, the row count adapts to height, and it scrolls where supported.
- Tap a row to launch that app. Tap the header or empty state to open the main screen.
- Configuration chooses **All apps** (the default and live: later additions appear automatically) or a fixed selected subset (later additions are not added).
- Rows always follow the canonical main-screen order. Reordering updates every instance immediately while each keeps its own selection.
- Updates after add, edit, remove, icon refresh, reboot, app upgrade and resize.
- `CLEARING` apps show as disabled rows labelled "Clearing site data…". Removed apps disappear.

### 12.2 Single-app launcher widget

- Targets a 1×1 cell and looks like a normal launcher icon (stored icon and name).
- Adding one opens a configuration screen to pick the app. Tap to open that app directly in its shell.
- Multiple instances may point to the same or different apps.
- A removed target shows the default icon and "App unavailable", and tapping it opens the main screen. A `CLEARING` target shows "App data maintenance".
- Reconfiguration is supported.

### 12.3 Common

- Widget configuration screens show `CLEARING` apps as disabled and not selectable.
- Widgets never launch a stale URL, never contain URLs or credentials, and render icons from local storage without network access.

## 13. Backup and restore

- The app configuration (including URLs) is included in device backup. Icons, widget records, maintenance state and all web data are not.
- On first launch after restore or device transfer, every restored app becomes usable (`ACTIVE`) with fresh, empty web data and the default icon until the next successful discovery. Order is preserved and repaired. No credential state is involved.

## 14. Accessibility and localization

Acceptance procedure: [native accessibility acceptance](ACCEPTANCE.md#accessibility-procedure-r14) (automated native-screen checks and device TalkBack/text-scaling/contrast checks).

- Native screens meet Android accessibility guidance: touch targets, focus order, TalkBack labels, text scaling and contrast.
- Icons are accompanied by names, and icon-only actions have content descriptions.
- WebView content stays accessible through standard WebView support.
- All native strings are resource-based and localizable.
- Loading and error states are announced to accessibility services without excessive repetition.

## 15. Privacy

- No analytics, advertising SDK or remote Mobile Web Shell service.
- Browsing destinations stay on the device except when sent to the chosen website or to MyPass as lookup URLs.
- Any future crash reporting must redact URLs by default and never collect credentials, headers, cookies, form values or page content.
- Remove and Clear behave as in §6.3–6.4. MyPass entries are never modified.

## 16. Resource handling

- There is no product cap on the number of apps or widgets. Storage or allocation exhaustion gives a recoverable error and never corrupts existing records.
- Every network-controlled collection is bounded (see §7.3).
- Malformed manifests, missing favicons, offline discovery, renderer termination and ordinary network errors never crash the app.

## 17. Acceptance criteria

Test traceability and end-to-end device procedures: [ACCEPTANCE.md](ACCEPTANCE.md).

1. A valid HTTPS URL with a name and no manifest can be added, saved and launched from the main list.
2. The shell shows no browser chrome, and the bottom system navigation area never obscures content.
3. After two in-WebView navigations, Back returns to the previous page; at the first entry, Back closes the shell.
4. Long-press opens an editor that can update or remove the app.
5. A manifest-backed site supplies its name, launch URL, scope, colors and best icon as defaults.
6. A non-manifest site uses its document title and favicon when available, otherwise the default icon.
7. Stored icons remain visible in the main screen and widgets while offline.
8. For an eligible Basic challenge, MyPass is queried with the exact top-level URL and realm, and the credential is supplied only to that challenge.
9. Basic credentials are never sent proactively, and a cross-origin redirect cannot inherit an earlier credential.
10. On an unambiguous secure login page, MyPass is queried with the exact committed URL and the form is filled but not submitted.
11. No credential is filled or sent to a cleartext page, a TLS-error page, an iframe, or a document other than the one that requested it.
12. Credentials are never persisted, or placed in saved state, the clipboard, logs, intents, widgets or backups.
13. The resizable widget launches each visible selected app and reflects configuration changes.
14. Each single-app widget launches its app and shows its stored icon and name.
15. A widget for a removed app cannot launch a stale URL and opens the main screen in an unavailable state.
16. Malformed manifests, missing favicons, offline discovery, renderer termination and network errors are recoverable without a crash.
17. Production builds reject certificate errors, block mixed active content, enable Safe Browsing where available and disable WebView debugging.
18. Two entries for the same origin keep separate simultaneous sessions, cookies, storage, IndexedDB, caches and service workers.
19. Discovery leaves no web data in the configured app's runtime data.
20. Removing or clearing one app deletes its web data without affecting any other app. Removal while the WebView is incompatible deletes native data immediately and completes web-data deletion when compatibility returns.
21. On a WebView lacking the isolation capability, the app reports incompatibility and never launches with shared data.
22. An in-scope `target="_blank"` link opens in the shell, and an out-of-scope one without a matching configured app opens in the system browser without transferring credentials or data. An ungestured `window.open()` opens nothing. Long-press **Open in browser** is offered only for allowed HTTPS URLs.
23. Without a MyPass form credential, the page remains usable for manual login.
24. Without a MyPass Basic credential, the challenge is cancelled and the failure reported, and other apps are unaffected.
25. No credential ever goes to the clipboard, and MyPass is never queried for an insecure page, TLS-error page or subframe.
26. Plain HTTP cannot be saved, loaded, fetched in discovery, reached through a redirect or form submission, or delegated; "HTTPS required" is shown.
27. An out-of-scope HTTPS link without a new-window request navigates inside the shell, and later authentication is looked up by that destination's exact URL.
28. Apps can be reordered by drag-and-drop or accessible move actions, and the order survives restart, edits and renames.
29. Multi-app widgets follow the canonical order after reordering and keep their subsets. New apps are appended at the end.
30. Clear cookies and site data removes the app's web data after confirmation. The next launch has no prior session, and configuration, metadata, icon, order, widgets and MyPass entries are unchanged.
31. Clearing one app never alters another app's web data.
32. Third-party cookies are off for new apps. Enabling them is per app, requires acknowledging a warning, and never weakens isolation.
33. Disabling third-party cookies blocks future access and informs the user that stored cookies remain until cleared.
34. `example.com` normalizes to `https://example.com` and can be saved, while `http://…` (including `http://localhost:8080`) fails validation.
35. An origin-changing URL edit clears web data and the old icon after confirmation (replaced by discovery or the default). A same-origin path edit keeps both unless discovery supplies a new icon.
36. MyPass absence, disappearance, signature-permission failure, cancellation, malformed result and timeout are handled without a crash or credential residue.
37. Every download request (including `blob:` and `data:`) is reported as unsupported and never handed to another app.
38. After restore, records are `ACTIVE` with fresh web data and default icons, and authentication depends only on MyPass availability at use time.
39. A `CLEARING` app is a disabled row at its usual position in the main list and multi-app widgets. A removed app disappears immediately.
40. Each running shell has its own Recents entry, and relaunching a live app brings that shell forward.
41. Pull-to-refresh at page top reloads and permits a new form-fill attempt, and it is inactive when scrolled and for a gesture that starts sideways.
42. `mailto:`, `tel:`, `sms:`, `geo:`, `market:`, `bankid:` and BankID's HTTPS autostart app link are delegated only after confirmation, and `intent:` is blocked. BankID autostart works for main-frame redirects and script navigation.
43. `theme_color` tints the system bars and task description with legible icon contrast, and `background_color` paints before first paint; neither affects native screens.
44. Discovery of a site whose start page is a same-origin redirect page (immediate meta refresh or script redirect) proposes the title and icon of the page it redirects to.
45. A valid HTTPS URL entered on the main screen opens immediately in an unsaved shell with an isolated disposable profile. It runs no discovery, creates no configured app, and never requests automatic authentication. Closing the shell retires its profile.
46. An out-of-scope new-window link matching another configured app opens the destination in that app's shell and reuses its active WebView and profile when available; unmatched links still open in the system browser.
