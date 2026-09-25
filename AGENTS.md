# Mobile Web Shell

Native Android launcher and chromeless runtime for web applications.

## Specification

- `spec/REQUIREMENTS.md`: functional requirements and acceptance criteria (what the app must do).
- `spec/ARCHITECTURE.md`: technical design, constraints, data model, test strategy and the provider compatibility matrix (how it is built).

Keep both documents up to date when a change affects requirements or architecture.

## Tasks

Implementation tasks are tracked as issues in AWB workspace `mws`. Each task references the relevant sections of the spec documents (R§ = REQUIREMENTS.md, A§ = ARCHITECTURE.md). The tests listed in a task are part of its definition of done.

## Build

No Gradle wrapper — uses the system `gradle` command. `local.properties` must
point `sdk.dir` at the Android SDK.

## Testing

You don't have access to test on a device or emulator. Ask the user to do that if necessary.
