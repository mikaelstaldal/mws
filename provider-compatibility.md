# Provider compatibility matrix

Release builds accept a WebView provider package only at or above a version with
a `PASS` row below. `scripts/generate-provider-floors.py` generates the
per-package floors in `GeneratedProviderFloors.kt` from this table; rerun it and
commit the result after changing a row. It fails unless at least one qualifying
row exists, and a `PASS` row must use the AndroidX WebKit version pinned in
`gradle/libs.versions.toml`. CI runs it with `--check` to catch a stale file.

Rows are added only after a provider qualification run
(`scripts/run-provider-qualification.sh`) and review of its evidence. The
qualification procedure, the evidence and the architecture behind this matrix
(A§15) are kept in the steward repository.

| Provider package | Exact provider version | Android/API and image | Architecture | AndroidX WebKit | Test date | Suite revision | Evidence | Result |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| com.google.android.webview | 153.0.8010.36 | Android 16 / API 36; Samsung Galaxy S25 SM-S931B | arm64-v8a | 1.17.1 | 2026-09-28 | `c03ceeadbafc55a2196689f5b1aec8257a463bac` | 37 suite tests and four restart phases in steward `provider-evidence-20260928T084927Z/`, first committed at `374f8e2e9b8a801a75bda1da3780e851850983f4` | `PASS` |
