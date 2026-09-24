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
if violations:
    print("\n".join(violations), file=sys.stderr)
    sys.exit(1)
