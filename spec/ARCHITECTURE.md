# Mobile Web Shell — Architecture

This document records the technical design and constraints that implement
[REQUIREMENTS.md](REQUIREMENTS.md). Section references of the form "R§n" point to REQUIREMENTS.md.

## 1. Technology baseline

- Native Android, Kotlin. Minimum SDK 26; target SDK is the latest stable.
- The initial build uses one `app` module, Gradle 8.14.3, Android Gradle Plugin 8.13.2, Kotlin 2.2.21, Java 21 as the build toolchain and Java/Kotlin bytecode target, and compile/target SDK 36 (the stable API at scaffold time). Versions are pinned in `gradle/libs.versions.toml`; API 37 is still a preview.
- AndroidX/Jetpack and Material components for native screens.
- Room (or equivalent structured persistence) for non-secret configuration and metadata.
- **AndroidX WebKit ≥ 1.9.0, pinned to an exact version in release builds.** Required features: `WebViewFeature.MULTI_PROFILE`, `WebViewCompat.setProfile`, `ProfileStore`, `WebViewCompat.addWebMessageListener`.
- Package-pinned activity-result integration with MyPass. There is no credential persistence subsystem.
- Background work respects current Android background-execution and widget-update limits. Widget refreshes are event-driven, not polled.
- One pinned WHATWG URL parser implementation is the only security authority for URL parsing, serialization and origin comparison. Android `Uri` and `java.net.URI` are not used for security decisions.

## 2. Component overview

| Component | Responsibility |
| --- | --- |
| `UrlPolicy` | Input normalization, canonical serialization, HTTPS/host classification, origin comparison, scope containment, credential-like parameter detection (including custom-scheme parsing) |
| Configuration store (Room, backup-eligible) | App records (§6.1), canonical order |
| Maintenance store (backup-excluded) | Operation journal, profile tombstones, disposable-discovery-profile records, permission decisions, widget records, self-test result, installation sentinel |
| Icon store (private files, backup-excluded) | PNG/WebP renditions keyed by app ID |
| `ProfileManager` | Profile naming, generation lifecycle, `ProfileStore` calls, deferred deletion, startup maintenance |
| `ProviderCompatibility` | Provider package/version floors, `MULTI_PROFILE` check, runtime self-test, per-feature enablement flags |
| Discovery engine | Discovery WebView + DOM extraction + native discovery client + manifest processor + icon pipeline |
| Shell activity | Chromeless WebView host, navigation policy, new-window routing, errors, timers, permissions, presentation |
| `MyPassClient` | Intent construction, availability, result validation, timeout, request identity binding |
| Basic-auth navigation gate | Correlates auth challenges with an eligible top-level navigation |
| Form-fill engine | Candidate detection helper, WebMessage transport, nonce management |
| Widgets | Multi-app `RemoteViews` collection widget, single-app widget, configuration activities |
| Startup reconciliation | Journal resume, tombstone/discovery-profile cleanup, orphan pruning, restore normalization, order repair |

## 3. URL handling

- Scheme-less input is recognized **before** parsing as authority[path][query][fragment]. `https://` is prepended, then the pinned WHATWG parser runs.
- Host processing: UTS #46 non-transitional IDNA with STD3 rules. Recognized Unicode dot separators are mapped before processing. IDNA errors are rejected. The ASCII host is lowercased and exactly one terminal DNS dot is removed.
- Serialization: an empty HTTPS path becomes `/`; default ports are removed; non-default decimal ports are kept; the parser's percent-encoding normalization is applied. IPv4 and IPv6 literals use the parser's canonical form.
- Userinfo is rejected before storage or navigation.
- The 8 KiB limit applies to the canonical serialization of every persisted URL.
- Scope containment follows the Web Manifest prefix algorithm: equal origins, and the candidate path string starts with the scope path string. It is a string prefix with no segment-boundary rule; query and fragment are ignored.
- Credential-like parameter detection:
  - HTTPS: query and fragment keys.
  - Custom schemes: parsed with the registered syntax for the scheme. Every query component is split into `&`-separated pairs and UTF-8 percent-decoded, with `+` left as-is. For `mailto:`, both the query after the address and the URI fragment are scanned.
  - A malformed escape, undecodable key or ambiguous component yields an "unparseable" key warning.
- Should `intent:` ever be allowlisted, it must be parsed defensively. Explicit components or packages are rejected, as are nested `file:`, `content:`, `javascript:`, `data:` and cleartext fallbacks. Any HTTPS fallback is re-evaluated under navigation policy. Adding any scheme requires a threat model.

## 4. Network security

- The network security config disables cleartext with no exceptions. This is the fail-closed enforcement layer; WebView callbacks provide early cancellation and user-facing errors only.
- Release builds contain no permissive `onReceivedSslError` or other SSL callbacks.
- Safe Browsing is enabled when supported. Mixed content mode is `MIXED_CONTENT_NEVER_ALLOW`. `setWebContentsDebuggingEnabled(false)` in production.
- The provider matrix (§13) records, per flow (direct links, redirects, GET/POST form submissions, pop-ups, external intents), whether cleartext is rejected before any request bytes are sent. Failure of any flow makes that provider version incompatible.

## 5. WebView profile isolation

### 5.1 Profile naming and lifecycle

- The profile name is derived from the opaque app ID plus `profileGeneration`. It contains no URL or credential. Retired names are never reused.
- The profile resource is provider-owned data plus Mobile Web Shell's generation-keyed native permission decisions.
- Every WebView (shell, new-window probe, discovery, self-test) is bound to its profile via `WebViewCompat.setProfile` **before its first load**. The default/global profile is never used by any WebView, cookie access or storage access. No `CookieManager`, storage manager, cache, service-worker controller or HTTP-auth store is treated as global.
- The third-party-cookie setting is applied to the app's profile cookie manager. It affects subsequent requests without a reload.
- Opening one app never initializes or attaches another app's profile.

### 5.2 Profile state machine

App record fields: `profileState` (`ACTIVE` | `CLEARING`), `profileGeneration` (monotonic), and `retiringProfileGeneration` (non-null only in `CLEARING`).

Clear (and origin-changing URL edit):

1. In one DB transaction: `retiringProfileGeneration := profileGeneration`, `profileGeneration += 1` (reserving the next never-used name), `profileState := CLEARING`. Launches are now blocked, and nothing may attach to the reserved generation until `ACTIVE`.
2. Finish the live shell activity and task for the app. Close and release all WebViews and in-memory `Profile` references for the retiring generation.
3. Delete the retiring generation's native permission decisions through the journal.
4. Call `ProfileStore.deleteProfile(retiringName)`:
   - `true` (deleted) or `false` (absent) means success, and the name is retired. In one transaction: clear `retiringProfileGeneration` and set `ACTIVE`.
   - `IllegalStateException` (profile still loaded in this process) means **deferral**. In one transaction: write a pending-cleanup tombstone for the retiring name, clear `retiringProfileGeneration` and set `ACTIVE`. The UI states that the old data is deleted at next restart.
   - Any other failure keeps `CLEARING` with launches blocked and offers Retry.
5. A restart resumes from persisted state. A retry can never target the newly reserved generation.

Origin-changing URL save:

1. Write a journal intent (old/new identifiers, no credentials) to the maintenance store.
2. One configuration transaction stores the new configuration snapshot, records the old generation as retiring, reserves the next generation and enters `CLEARING`.
3. Continue as in Clear.

After a crash, an intent whose configuration transaction has not completed is completed forward, never abandoned. The new URL is authoritative from step 2 onward.

Removal (no `DELETING` state is kept on the record):

1. Write a durable, backup-excluded tombstone with creation time and the opaque name/generation of the active generation, plus the retiring one if the record is `CLEARING`.
2. Close live WebViews and the shell task. Delete the configuration record, metadata, icons and permission decisions. Prune widget selections and update widgets.
3. Attempt `deleteProfile` if the provider is compatible. `true`/`false` deletes the tombstone. Otherwise the tombstone remains for startup retry. Profile APIs are never called while incompatible.

### 5.3 Startup maintenance

At every process start, **before any profile is loaded**:

- Resume incomplete journal operations.
- Retry `deleteProfile` for every tombstone and every disposable-discovery-profile record (when compatible). Remove the record only after `true`/`false`.
- Delete unreferenced icon files, permission decisions for absent or retired generations, and widget records for instances `AppWidgetManager` no longer reports. Clear dangling references and request a widget refresh.
- A resource is unreferenced only when no active journal can still adopt it.

### 5.4 Operation journal

Every operation spanning the configuration DB, icon files, permission store, WebView profiles or widget records uses the durable journal in the maintenance store. Each step is idempotent, records its completion before advancing, and is resumed on startup. No cross-store operation relies on in-memory transactions.

The journal stores a typed intent with opaque resource identifiers and a completed-step cursor. The runner replays the first unfinished step after process death; a step therefore also tolerates replay when its external effect succeeded before its cursor update. Startup resume attempts every pending intent and reports failures for the caller to handle before exposing affected apps. A completed intent may be discarded after consumers no longer need to inspect it.

### 5.5 Deletion guarantee

The normative guarantee is the `ProfileStore` contract: after accepted deletion the old name is unusable, and a new generation starts with no provider-visible state. Tests verify every data category the provider exposes an observable API or probe for. Other profile-owned categories rely on provider conformance and deletion success. Cryptographic erasure is not promised.

## 6. Data model

### 6.1 Configured app (configuration store, backup-eligible)

| Field | Description |
| --- | --- |
| `id` | Stable, locally generated opaque identifier |
| `name` | User-visible name |
| `nameSource` | `DISCOVERED` \| `USER_EDITED` |
| `configuredUrl` | Normalized user URL |
| `finalUrl` | Final committed discovery URL (nullable) |
| `launchUrl` | Valid manifest start URL or fallback |
| `scope` | Valid manifest scope or origin fallback |
| `allowThirdPartyCookies` | Boolean, default false |
| `profileState` | `ACTIVE` \| `CLEARING` |
| `profileGeneration` | Monotonic generation used in the profile name |
| `retiringProfileGeneration` | Non-null only in `CLEARING` |
| `manifestSnapshot` | Sanitized supported members; authoritative for normalized `display` |
| `iconReference` | Private icon asset reference |
| `themeColor`, `backgroundColor` | Optional validated colors |
| `sortPosition` | Canonical order position |
| `sensitiveUrlConsent` | Record of consent for the exact serialized sensitive URL(s) (implementation detail of R§4.1) |
| `createdAt`, `updatedAt` | Timestamps |

- There are never passwords, Authorization headers, cookie contents or page HTML.
- Ordering is a stable total order with contiguous positions. Add, remove and reorder update all affected positions in one transaction. The app ID is the deterministic tie-breaker when repairing duplicate or gapped positions.

### 6.2 Maintenance store (backup-excluded)

`maintenance.db` is a separate, versioned SQLite database under `noBackupFilesDir`. Its SQLite journal stays in that directory. It contains no configuration rows or credentials.

- **Permission decisions**: (app ID, generation, canonical exact origin, capability = `GEOLOCATION`) is unique, with decision `SITE_ALLOW` | `SITE_DENY`. Decisions are never migrated to a new generation, and are deleted via the journal only once no live WebView can use the retiring generation.
- **Widget records**, keyed by `appWidgetId`:

  | Field | Description |
  | --- | --- |
  | `appWidgetId` | Android-assigned identifier |
  | `widgetType` | `MULTI_APP` \| `SINGLE_APP` |
  | `selectionMode` | `ALL_APPS` \| `SELECTED_SUBSET` (multi only) |
  | `selectedAppIds` | Set, only for `SELECTED_SUBSET` |
  | `targetAppId` | Single-app only |

  Records are written by the configuration activity and deleted on `onDeleted`. App removal prunes the app from `selectedAppIds`. A single-app record with an unresolved target is kept and rendered as unavailable. An installed widget without a record (for example after a restore) renders as an unavailable single-app widget or an `ALL_APPS` multi-app widget. Records never contain URLs, credentials or bitmaps.
- **Tombstones**: opaque retired profile name/generation plus creation time only. They are never used to create or attach a profile.
- **Disposable discovery profile records**: an opaque ID, written durably before the profile is created or attached.
- **Runtime self-test result**: the match key and per-feature results, with no URLs or credentials.
- **Installation sentinel.**

### 6.3 Backup and restore

- Backup-eligible: the configuration store only. Excluded: the maintenance store, icon renditions and all WebView profile data.
- The configuration Room database is named `configuration.db` and uses `RoomDatabase.JournalMode.TRUNCATE` so the single backup-eligible file contains committed edits. The backup rules include that database file only.
- A missing sentinel alongside a restored configuration store means first launch after restore or transfer. Normalization, resumable via the journal, creates the sentinel before exposing records. Every record becomes `ACTIVE` with `profileGeneration` greater than both restored generations, `retiringProfileGeneration` is cleared, and `iconReference` is cleared. `sortPosition` is repaired to contiguous. No deletion call is made, because no profile data was restored.

### 6.4 Resource exhaustion

There is no cap on apps or widgets. Every add or update handles storage/allocation failure without corrupting existing records and reports a recoverable error.

## 7. Provider compatibility and runtime self-test

- Release builds embed, per provider package, a minimum version floor generated at build time from qualifying `PASS` entries in the compatibility matrix (§15). A package absent from the matrix is incompatible. Version names must be numeric dot-separated components; unknown formats fail closed.
- Shell launch and WebView-backed discovery require all of the following:
  - the provider package is listed;
  - its version is at or above the floor;
  - it reports `MULTI_PROFILE`;
  - the runtime self-test's isolation and deletion probes pass.
  Otherwise the app shows the provider and version with update instructions. If compatibility disappears later, profile access, discovery and clear are blocked without modifying stored data. Removal still works (§5.2). The default profile is never a fallback.
- A debug build may set `debugProviderOverride=true` in `local.properties` (or `DEBUG_PROVIDER_OVERRIDE=true`) to bypass the package floor and a pending self-test during development. It still requires `MULTI_PROFILE` and does not override an explicitly failed isolation or deletion probe. Release builds cannot enable this override.
- **Runtime self-test** match key: provider package, provider version, Android API level and primary CPU ABI. It runs on first start and whenever the key changes, before any provider-sensitive feature is enabled.
  - It uses a dedicated disposable profile and no network. Synthetic responses for a reserved `.invalid` HTTPS origin are served via `shouldInterceptRequest`.
  - Probes, each failing closed independently:

    | Probe | On failure |
    | --- | --- |
    | Profile isolation | Provider incompatible |
    | Profile deletion | Provider incompatible |
    | Basic-auth navigation gate | Cancel every challenge |
    | Pre-dispatch new-window routing (cancellation before dispatch) | Reject every new-window request |
    | Form-fill WebMessage (`isMainFrame`, source origin, replaced object name unavailable) | Disable automatic filling; manual entry remains |

  - The result is stored with its key in the maintenance store.
- Any cookie decision claiming SameSite/partitioning correctness must use a provider API proven to accept the full request context (top-level site, request role). Without one, the credentialless discovery fallback (§8.3) applies.

## 8. Discovery engine

### 8.1 Sessions and attempts

- Each editor session has one unique, never-reused disposable discovery profile. It is created on the first attempt, registered durably in the maintenance store before creation or attachment, and shared by that session's sequential attempts. Profiles are never shared between sessions.
- Each attempt has an identity. Starting a new attempt closes the previous attempt's WebView and invalidates its callbacks. Each native resource request also has an identity, and late callbacks are ignored.
- Leaving the editor closes the WebView and retires the profile. Deletion starts after the WebView closes, and the record is removed after `true`/`false`. `IllegalStateException` defers deletion to the next start.
- The discovery WebView is attached to a window and laid out at non-zero size while loading (it may be covered by the progress UI) so that `onPageCommitVisible` fires.

### 8.2 Timers

- Initial-document timer: 15 s, started immediately before `loadUrl`. It spans redirects and stops at the first main-frame visible commit or terminal main-frame error. Expiry stops loading and begins fallbacks.
- Overall timer: 30 s from the same `loadUrl`, covering the document and all native fetches. It never resets.
- Both timers pause while a MyPass activity launched for the attempt is outstanding (itself limited to 60 s).
- Limits are measured after content decoding unless marked "encoded", and are build-time configurable. The initial document has no app-level size limit.

### 8.3 DOM extraction

- Runs once after the first main-frame visible commit and DOM readiness. A fixed, non-credential-bearing script returns an immutable snapshot, which is accepted only for the current main-frame document identity.
- It inspects at most the §7.3 node limits (16 manifest links, 128 page icons), caps each string at 8 KiB and the snapshot at 1 MiB, and ignores later mutations. Truncation that prevents safe parsing makes that candidate unusable.
- The snapshot captures manifest and icon links in document order, resolved URLs (base URL honored), matching `media`, declared `type`, `crossorigin`, effective referrer policy, and the document title.

### 8.4 Native discovery client

The native client is the **sole fetcher** of manifest and icon bodies, and it enforces the byte, redirect and per-resource timeout limits and the 20 MiB aggregate budget. Every request and redirect is re-evaluated under the transport policy, obeys the captured referrer policy, and strips cookies and authorization on a cross-origin redirect unless independently eligible.

| Fetch class | Mode | Credentials | Notes |
| --- | --- | --- | --- |
| Manifest | CORS | `include` only for `crossorigin="use-credentials"`, else `same-origin` | Sends `Origin`; validates `Access-Control-Allow-Origin`/`-Credentials`; a wildcard is rejected for credentialed responses; the final response URL becomes the manifest URL |
| Manifest icon | no-CORS image | Cookies only if same-origin with the manifest URL | Cross-origin is credentialless |
| Page `rel="icon"` | no-CORS image | Cookies only if same-origin with the final document URL | Captured link referrer policy |
| `/favicon.ico` | no-CORS image | Eligible disposable-profile cookies | Final document origin only; document referrer policy |

- Cookies are read and written only through the session's disposable-profile adapter, using the full request/response context (URL, top-level site, request role, credentials mode, partitioning). `Set-Cookie` is applied before following a redirect or issuing a later fetch. This is enabled only when the compatibility suite proves both full-context evaluation and disposable-profile scoping. **Otherwise, as the required version-1 fallback, all four classes are credentialless, `Set-Cookie` is ignored, and the UI warns that authenticated metadata may be unavailable.**
- **Documented deviation**: the native fetch does not enforce the document's Content Security Policy. Fetched bytes go only to the native parser, no script is executed, and no state is transferred.
- Discovery Basic auth (see R§7.4): the WebView answers the top-level challenge after the origin check against the typed configured URL. The native client may answer a Basic-scheme `401` for a same-origin, same-realm resource with the already-returned credential, at most once per request and never after a cross-origin redirect. Proxy-auth challenges are always cancelled.

### 8.5 Stage results and draft state

- The editor draft keeps per-stage results (document/finalUrl, DOM, manifest, colors, name, icon). Save cancels the attempt and uses the completed stages plus fallbacks.
- A new commit recomputes `launchUrl` and `scope` atomically from the new `finalUrl` and the effective manifest. The effective manifest is the new one if the manifest stage succeeded, else the retained snapshot for an unchanged URL, else none.

### 8.6 Icon pipeline

- Candidates are fetched sequentially in rank order (R§8.2). Decoding rejects anything over 5 MiB encoded, 16 MP decoded or 4096 px per dimension.
- The SVG renderer runs with scripts, external resources, animation, fonts and network disabled. Failures never expose source content.
- One selected source produces renditions at 48, 72, 96, 144, 192, 288 and 512 px (PNG or WebP), plus a retained higher-resolution source rendition. Maskable sources are cropped to the 80% safe-zone circle and given a rounded-square mask.
- `IconSelection` and `IconPipeline` take resolved candidates and fetch through `IconFetcher`; the discovery native client supplies that interface and remains responsible for transport policy, redirects, timeouts and aggregate budgets. The pipeline tries failed candidates in rank order and returns `null` for the bundled default icon.
- SVG input is checked with Android's pull parser, rejecting DTDs, entities, processing instructions, scripts, event handlers, external references, animation, font and text content, embedded images and foreign objects before AndroidSVG renders it. Local fragment `url(#id)` references are allowed; `<use>` is rejected to avoid recursive expansion. Depth and element counts are capped. ICO supports embedded PNG and uncompressed 32-bit DIB entries.
- `IconStore` writes a complete PNG generation under `noBackupFilesDir/icons/<app ID>/<generation>`, then publishes the generation by directory rename. Configuration stores that reference; old generations are pruned after the new reference is durable. A missing or unreadable reference renders the bundled vector default.

## 9. Shell

### 9.1 Task model

- `ShellActivity` is launched only by stable app ID, carried as a data URI on the base intent. It never accepts an untrusted raw URL intent.
- Document-centric tasks (`documentLaunchMode` / `FLAG_ACTIVITY_NEW_DOCUMENT` with the app-ID data URI), plus `onNewIntent`, find and bring forward the existing task. Dynamically generated task affinities are not used. There is at most one live shell per app.
- `setTaskDescription` sets the app's name, icon and theme color. The shell observes the app record and refreshes this on resume or change.
- Every (re)created shell validates the stable ID and `ACTIVE` state before attaching a profile.
- An origin change, clear or remove finishes the activity and task, closing the WebView, **before** profile retirement.

### 9.2 Lifecycle

- The live WebView is retained across configuration changes through a lifecycle-aware holder. WebView state bundles are never persisted, and navigation URLs are kept only in memory.
- After process death, a new WebView loads the resolved launch URL.
- Renderer crash (`onRenderProcessGone`): dispose the WebView and create a new one on the same active profile. A concurrent `CLEARING` or removal cancels recovery.

### 9.3 Presentation

- Edge-to-edge on all API levels with transparent system bars. Content is inset via `WindowInsets`, and the chosen colors are painted behind the bars.
- The contrast calculation follows R§9.1. `fullscreen` hides only the status bar.
- `background_color` is the window background before the first visible paint.

### 9.4 Navigation policy implementation

- `shouldOverrideUrlLoading` enforces scheme and transport policy: HTTPS stays, cleartext is rejected, dangerous schemes are blocked, and allowlisted external schemes are confirmed only when main-frame, `!isRedirect` and `hasGesture()`.
- Multiple windows: `setSupportMultipleWindows(true)` is used solely to observe new-window requests. `javaScriptCanOpenWindowsAutomatically = false`, so an ungestured `window.open()` fails.
- **New-window probe**: in `onCreateWindow`, create a temporary probe WebView bound to the **same app's active profile** (never the default, another app's, or a discovery profile). Learn the destination in a synchronous pre-load callback and cancel there, before any request is dispatched. Reject when:
  - the first observable callback comes after dispatch;
  - no URL is exposed;
  - `WebResourceRequest.method` is not GET;
  - the destination is `about:blank` or undeterminable.
  Destroy the probe immediately. Route in-scope HTTPS to the existing WebView and out-of-scope HTTPS to an `ACTION_VIEW` browser intent. The compatibility suite verifies zero request bytes and no profile mutation per floor version.
- Browser handoff intents carry no headers, referrer extras or profile state.
- Long-press **Open in browser** uses `WebView.getHitTestResult` / a context menu, for allowed HTTPS URLs only.
- Pull-to-refresh uses a `SwipeRefreshLayout` (or equivalent) that is enabled only when the WebView is at scroll top.
- Downloads: the `DownloadListener` shows the unsupported message.

### 9.5 Initial-load timer

30 s, started immediately before the first `loadUrl` of a new WebView. It spans redirects, stops at first visible commit or terminal error, and pauses while MyPass is outstanding. Expiry shows the error screen and ignores that navigation identity's later callbacks. Retry creates a new identity and timer.

### 9.6 Permissions

- `WebChromeClient.onPermissionRequest` and `onGeolocationPermissionsShowPrompt` show an exact-origin native confirmation first, then request the runtime permission.
- Geolocation decisions are persisted in the permission-decision store (§6.2). Provider permission persistence is not relied upon.
- Camera and microphone are prompted per request and serialized per origin and capability.
- `onShowFileChooser` uses the document chooser (`ACTION_OPEN_DOCUMENT`) and needs a gesture each time.
- Notifications and client-certificate requests are denied promptly.

## 10. MyPass integration

- Manifest: `<uses-permission android:name="nu.staldal.mypass.permission.FETCH_PASSWORD"/>` and `<queries><package android:name="nu.staldal.mypass"/></queries>`.
- Request: `Intent("nu.staldal.mypass.action.FETCH_PASSWORD").setPackage("nu.staldal.mypass")`, with extras `URL` (and `REALM` for Basic). Check `resolveActivity` first. Catch `ActivityNotFoundException` and `SecurityException` as unavailability.
- Each request is bound to a random request identity plus the current WebView, navigation and document identity. Only these non-secret identities may survive lifecycle recreation. There is one outstanding request per flow, with a 60 s timeout.
- The Activity Result registry uses a distinct key for each request. After timeout or cancellation, its callback remains registered to drain a late result without retaining the returned `Intent` in registry saved state. On recreation, only outstanding request keys are restored and registered as discard callbacks before the activity starts. An orphaned result is discarded; the flow must request again under a new identity.
- Credential values are held in memory only and cleared on completion, failure, cancellation, navigation, close or timeout.

## 11. Basic-auth navigation gate

- A new gate identity is armed, with the exact canonical URL, scheme, host and effective port recorded, immediately before the app's own `loadUrl`, reload or Retry of an eligible URL, and in `shouldOverrideUrlLoading` for an allowed main-frame, non-redirect request. `onPageStarted` is **not** used for arming.
- The gate is disarmed on:
  - a new main-frame navigation;
  - commit;
  - a terminal error;
  - cancellation;
  - any `shouldInterceptRequest` whose canonical URL is not the recorded main-document URL (the conservative "a subresource has begun" signal).
- In `onReceivedHttpAuthRequest`, handle the challenge only if exactly one gate is armed and the callback host equals the recorded host. Scheme and port come from the recorded navigation. Otherwise cancel.
- An accepted redirect continuation, recognized by the provider and independently policy-eligible, replaces the recorded URL, scheme, host and port. An unrelated navigation replaces the identity. Ambiguous sequences fail closed.
- The credential is supplied once per (identity, URL, realm) via `HttpAuthHandler.proceed`. Repeats are cancelled. The provider's in-memory per-profile HTTP-auth cache is accepted; `WebViewDatabase` HTTP-auth persistence is never used.
- Proactive `Authorization` headers are never used, because custom-header propagation across redirects cannot guarantee cross-origin non-disclosure.
- Enabled only when the provider is at or above its floor and the self-test Basic-auth probe passed.

## 12. Form-fill transport

- When creating the WebView, and before the first load, register one `addWebMessageListener` under a random, never-reused JS object name, with allowed-origin rules admitting only HTTPS.
- Before each new top-level navigation can commit, remove the listener and register a replacement under a new random name.
- After an eligible main-frame commit (exact origin plus navigation/document identity captured):
  1. Inject the fixed bootstrap and candidate-detection helper (no credential values) into the main frame with a random single-use nonce bound to that document.
  2. The helper observes DOM readiness and mutations for at most 10 s and reports `ready` once exactly one candidate is unambiguous, then disconnects.
  3. Native code accepts `ready` only when `isMainFrame` is true, the source origin exactly equals the committed origin, the document identity is current, and the nonce is unused.
  4. Request MyPass (URL only).
  5. Reply once via `JavaScriptReplyProxy` with the username and password as message data (never as JS source literals). Invalidate the nonce.
  6. The bootstrap fills only the selected fields, dispatches input/change events, clears its references and tears itself down.
- The nonce is invalidated on completion, timeout, navigation, error or close. The listener is removed when the WebView closes.
- No general-purpose JS-to-native bridge (`addJavascriptInterface`) is used. Any future bridge must be narrowly scoped, origin-checked and separately threat-modeled.
- Enabled only when the self-test form-fill probe passed.

## 13. Widgets

- A multi-app widget uses a `RemoteViews` collection (`RemoteViewsService` / `RemoteCollectionItems` on API 31+), is resizable, and orders rows by `sortPosition`. A single-app widget targets 1×1 and is reconfigurable on API 31+.
- `PendingIntent`s are immutable and explicit, carrying only the stable app ID, which is validated against the DB before launch. There are no URLs or credentials in intents or widget state.
- Updates are triggered by add, edit, remove, icon change, reorder, clear state changes, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` and `onAppWidgetOptionsChanged`.
- Rendering never creates a WebView and never needs the network.
- Exported components are minimized and protected. Widget entry points validate every ID and input.

## 14. Test strategy

### 14.1 Unit tests

URL normalization (including every R§6.1 and R§10 case: userinfo, default ports, trailing dot, HTTP/`localhost`/loopback rejection, HTTPS IPv4/IPv6 canonicalization, scheme-less IP rejection, IDN, whitespace, ambiguous colon), secure-context classification, origin matching, manifest resolution, scope checks, icon selection ranking, login-form candidate scoring, credential-like parameter detection (HTTPS and each custom scheme), contrast selection, and the profile-generation state machine.

### 14.2 Instrumented tests (controlled HTTPS test servers)

The instrumented test fixture uses a loopback MockWebServer with a checked-in test certificate. The matching test CA is trusted by debug builds through `debug-overrides`, allowing both WebView and the test-only OkHttp client to use HTTPS without changing release trust settings. CI runs instrumented tests on API 26 and API 36 emulators; unit tests, lint, and the security source check run in the build job.

- **Navigation and transport**: history/Back, redirects, TLS failures, mixed content, and cleartext rejection for configuration, links, redirects, form submissions, manifests, icons, pop-ups and external intents, with zero request bytes transmitted.
- **New windows**: in- and out-of-scope `target="_blank"` and gestured `window.open()`; ungestured `window.open()`; `about:blank` and undeterminable destinations; non-GET requests; the probe committing nothing and leaving no profile state; Open in browser; missing browser; no header or credential transfer.
- **External schemes**: each allowlisted scheme is confirmed; `intent:` and others are blocked; there is no permanent grant; redirected or ungestured navigations are blocked without a prompt.
- **Downloads**: HTTPS, `blob:` and `data:` are unsupported and never delegated.
- **Reload and errors**: pull-to-refresh only at top; renewed form-fill after reload; no scroll conflict. Retry targets (initial load, safe GET, last committed GET, no POST replay). Timers: start/stop, redirects, expiry, late callbacks, fresh identity on Retry, MyPass pause.
- **Basic auth**: exact URL and realm lookup; same-origin redirect URL; cross-origin redirect, subresource and iframe cancellation; one-shot supply; gate behavior against top-level, redirected, iframe and early/late subresource challenges per floor version; cancel-all when below the floor or when the probe fails.
- **Form fill**: listener registered before first load and HTTPS-only; subframe, other-origin, stale-document and bad-nonce messages ignored; new object name per navigation; one reply per document; exact-URL lookup; manual fallback.
- **MyPass contract**: explicit package and action; manifest declarations; URL-only versus URL+realm; result validation; one outstanding request; stale-result rejection; absence, null resolution, disappearance, `SecurityException`, cancel, malformed result and timeout.
- **Secret lifetime**: never persisted, copied, logged, put in saved state, or retained after completion, cancel, navigation, error, close or timeout (excluding the provider auth cache).
- **Isolation**: two same-origin entries separate cookies, local/session storage, IndexedDB, Cache Storage, HTTP cache, service workers, HTTP-auth state, permission grants and logout. Persistence across relaunch. Discovery leaves no state.
- **Profile lifecycle**: atomic reservation; process death before and after deletion; idempotent retry; never targeting the reserved generation; deferred `IllegalStateException` deletion and restart cleanup before any profile loads; clear-data (every category, shutdown, launch exclusion, preservation, failure recovery, isolation); `CLEARING` rows, labels, positions and tap explanation; `CLEARING` editor restrictions; Remove tombstoning both generations; incompatible-provider removal; origin-change journal crash at every step.
- **Discovery**: cancellation, overlapping attempts, late callbacks, process death, orphaned disposable-profile cleanup; durable registration before attach; the cross-origin first commit; failed Refresh retaining the snapshot; first media-matching manifest link only, with no fall-through; native client limits; the four fetch classes (CORS checks, credential modes, referrer, redirect stripping, credentialless fallback, CSP deviation); discovery Basic auth; debounce never requesting MyPass.
- **URL changes**: origin versus same-origin profile and icon behavior; confirmation; later MyPass lookups using only the current URL.
- **Permissions**: grant/deny persistence, per-use prompts, Android denial/revocation, clear versus OS-permission distinction.
- **Third-party cookies**: default off; warning; enable/disable; persistence; same-site versus cross-site; retention until clear; isolation across apps sharing origins.
- **Compatibility and self-test**: fail closed without `MULTI_PROFILE`; floors; runs on first start and on key change; no network; per-feature fail-closed behavior.
- **Task model and presentation**: one shell per app; simultaneous shells; Recents entries; bring-forward. Theme tinting and contrast, background pre-paint, dark mode, no effect on native screens.
- **Sensitive URLs**: key-only redaction, exact-URL consent, backup disclosure, delegation, and decline behavior.
- **Restore**: sentinel detection, interrupted normalization, `ACTIVE` state, new generations, cleared icons, order repair.
- **Ordering**: drag-and-drop, accessible move actions, cancel/save, restart, append, stable on edit, gap removal, transactional rollback, repair of duplicate or gapped positions.
- **Widgets**: multiple instances, resize, canonical order with different subsets, updates and removal, reboot, forged or stale IDs, per-instance records, `ALL_APPS` versus subset, pruning, deletion on `onDeleted`.
- **Accessibility**: main screen, editor, dialogs, errors, widget configuration.

## 15. Provider compatibility matrix

This is the normative evidence for per-package floors. Each entry records:

- provider package and exact version;
- Android version/API and device or emulator image;
- CPU architecture;
- pinned AndroidX WebKit version;
- test date, suite revision and evidence location (version-controlled, immutable revision);
- pass/fail for every provider-sensitive flow: profile isolation and deletion, cleartext rejection before transmission, Basic-auth correlation, exact-origin form-fill messaging, pre-dispatch new-window routing, full-context cookie decisions and their fail-closed fallbacks, discovery, and crash-resumable profile maintenance.

An entry qualifies only if every flow passes. Shipped floors are generated from qualifying entries, and a new entry is required whenever the pinned AndroidX WebKit version changes. **The initial release is blocked until at least one qualifying entry exists.**

| Provider package | Exact provider version | Android/API and image | Architecture | AndroidX WebKit | Test date | Suite revision | Evidence | Result |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| _No provider qualified yet_ | — | — | — | — | — | — | — | `BLOCKED` |
