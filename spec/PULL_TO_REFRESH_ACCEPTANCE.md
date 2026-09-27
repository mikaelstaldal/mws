# Pull-to-refresh device acceptance (R§9.2)

Run on a supported provider with a configured HTTPS app containing a long page and a login form.

1. At scroll top, drag downward and release past the refresh threshold. Verify the current page reloads and Back history is unchanged.
2. Scroll down, then drag downward. Verify the page scrolls normally without reloading. A gesture starting below the top must not become a refresh when it reaches the top; begin a new gesture there.
3. At the top, try an upward drag, a horizontal drag, a short downward drag, and a cancelled gesture. Verify none reloads.
4. Complete a form-fill attempt, then refresh. Verify the reloaded document permits a fresh attempt and stale credential results cannot fill it.
5. For an HTTP Basic-auth site, refresh and verify a new eligible challenge can look up the exact current URL through MyPass.
6. Verify refresh is unavailable during initial loading, on an error overlay, and during Retry recovery. Rotate a loaded shell and verify pull-to-refresh still works without an automatic reload.

JVM coverage in `ShellActivityTest` verifies scroll-top gating, gesture interception, reload dispatch, form-fill preparation, and Basic-auth rearming. Actual WebView scrolling and provider/network behavior require this device pass.
