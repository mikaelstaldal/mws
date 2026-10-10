# Mobile Web Shell — project administration

Specification, acceptance procedures and test evidence for Mobile Web Shell, a
native Android launcher and chromeless runtime for web applications. The source
code, build, user documentation and website are in the source repository.

## Specification

- `spec/REQUIREMENTS.md`: functional requirements and acceptance criteria (what the app must do).
- `spec/ARCHITECTURE.md`: technical design, constraints, data model, test strategy and the provider compatibility rules (how it is built).
- `spec/ACCEPTANCE.md`: criterion-to-test traceability, device acceptance procedures and WebView provider qualification.

These documents describe the current behavior of the app. When a bug fix or
feature changes requirements or architecture, update them together with the
source change. A new acceptance criterion needs a traceability row in
`ACCEPTANCE.md`. Do not change the security invariants in the spec without an
explicit decision.

The provider compatibility matrix is `provider-compatibility.md` in the source
repository, because the build generates provider floors from it. A§15 describes
its rules; the evidence behind each row is kept here in `provider-evidence-*/`.

## Issues

Bugs, features and maintenance tasks are tracked as issues in AWB workspace
`mws`. Issues reference the relevant spec sections (R§ = REQUIREMENTS.md,
A§ = ARCHITECTURE.md). The tests listed in an issue are part of its definition of
done.

## Evidence

Provider qualification and device acceptance evidence directories are committed
here and record the source revision they tested. A change that affects WebView
provider behavior or bumps the AndroidX WebKit version needs a new provider
qualification run before release (see "Provider qualification" in
`spec/ACCEPTANCE.md`).
