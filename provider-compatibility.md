# Provider compatibility matrix

Release builds accept a WebView provider package only at or above a version with
a `PASS` row below. `gradle :app:generateProviderCompatibilityTable` generates the
per-package floors from this table, and release verification fails unless at
least one qualifying row exists. A `PASS` row must use the AndroidX WebKit version
pinned in `gradle/libs.versions.toml`.

Rows are added only after a provider qualification run
(`scripts/run-provider-qualification.sh`) and review of its evidence. The
qualification procedure, the evidence and the architecture behind this matrix
(A§15) are kept in the steward repository.

| Provider package | Exact provider version | Android/API and image | Architecture | AndroidX WebKit | Test date | Suite revision | Evidence | Result |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| com.google.android.webview | 153.0.8010.36 | Android 16 / API 36; Samsung Galaxy S25 SM-S931B | arm64-v8a | 1.17.1 | 2026-09-28 | `c03ceeadbafc55a2196689f5b1aec8257a463bac` | 37 suite tests and four restart phases in steward `provider-evidence-20260928T084927Z/`, first committed at `374f8e2e9b8a801a75bda1da3780e851850983f4` | `PASS` |
