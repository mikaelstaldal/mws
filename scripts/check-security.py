#!/usr/bin/env python3
"""Fail CI on transport and debugging APIs that need explicit review."""

from pathlib import Path
import re
import sys

source = Path("app/src")
patterns = {
    "SSL error callback": re.compile(r"\bonReceivedSslError\b"),
    "custom hostname verifier": re.compile(r"\bsetHostnameVerifier\b|\bHostnameVerifier\b"),
    "custom trust manager": re.compile(r"\bX509TrustManager\b|\bTrustManagerFactory\b"),
    "WebView debugging": re.compile(r"\bsetWebContentsDebuggingEnabled\b"),
    "cleartext exception": re.compile(r"cleartextTrafficPermitted\s*=\s*['\"]true['\"]"),
    "cleartext manifest flag": re.compile(r"usesCleartextTraffic\s*=\s*['\"]true['\"]"),
    "user certificate trust": re.compile(r"<certificates\s+src\s*=\s*['\"]user['\"]"),
    "permissive mixed content": re.compile(r"\bMIXED_CONTENT_(?:ALWAYS_ALLOW|COMPATIBILITY_MODE)\b"),
    "debuggable manifest flag": re.compile(r"debuggable\s*=\s*['\"]true['\"]"),
}
# A§5.1: no WebView, cookie or storage access may use the default/global profile.
patterns.update({
    "global cookie manager": re.compile(r"\bCookieManager\s*\.\s*getInstance\b"),
    "global web storage": re.compile(r"\bWebStorage\s*\.\s*getInstance\b"),
    "global geolocation permissions": re.compile(r"\bGeolocationPermissions\s*\.\s*getInstance\b"),
    "global service worker controller": re.compile(r"\bServiceWorkerController(?:Compat)?\s*\.\s*getInstance\b"),
    "WebView database": re.compile(r"\bWebViewDatabase\b|\b[sg]etHttpAuthUsernamePassword\b"),
})
layout_webview = re.compile(r"<(?:android\.webkit\.)?WebView\b")
# Only the profile manager may construct WebViews, bind profiles or call ProfileStore.
profile_owner = Path("app/src/main/java/nu/staldal/mws/ProfileManager.kt")
profile_patterns = {
    "WebView construction outside ProfileManager": re.compile(r"\bWebView\s*\("),
    "profile binding outside ProfileManager": re.compile(r"\bsetProfile\b|\bProfileStore\b"),
    "default profile outside ProfileManager": re.compile(r"\bDEFAULT_PROFILE_NAME\b"),
}
violations = []
for path in source.rglob("*"):
    if path.parts[2] in {"test", "androidTest"}:
        continue
    if path.suffix not in {".kt", ".java", ".xml"}:
        continue
    data = path.read_text()
    for label, pattern in patterns.items():
        if pattern.search(data):
            violations.append(f"{path}: {label}")
    if path.suffix == ".xml" and layout_webview.search(data):
        violations.append(f"{path}: WebView in layout")
    if path != profile_owner:
        for label, pattern in profile_patterns.items():
            if pattern.search(data):
                violations.append(f"{path}: {label}")
if violations:
    print("\n".join(violations), file=sys.stderr)
    sys.exit(1)
