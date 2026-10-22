# Verification record

Status is updated from executed commands, not inferred from source inspection. See final record below once verification is complete.

## Reproduction

JDK 17 and Android SDK 35/build-tools 35.0.0, no IDE/emulator required:

```powershell
.\gradlew.bat -PengineOnly=true :finance-engine:test --console=plain
.\gradlew.bat :finance-engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

On Linux use `./gradlew`. Set JAVA_HOME and ANDROID_HOME locally; these are not global machine changes. Unit tests cover exact monetary parsing, precision rejection, overflow, currency mismatches, balanced postings, transfer conservation, split validation, reversal cancellation, 1,000 seeded transfer invariants, reserve consumption, deficits, goal ceiling rounding, diversion preview and deterministic date-based forecast assumptions/shortfalls.

## GitHub setup

Remote requested by user: `https://github.com/ashwanibaghel/OnlyTesting.git`. Initially empty (no branch refs). Local `main` is initialized and origin configured. GitHub connector reported read permission and `push: false`; local Git credentials are checked separately before publishing.

To run remotely, publish the reviewed foundation to this repository with an account that has write access, enable GitHub Actions and permit the pinned actions. Workflow runs on push/PR or manually through Actions -> Android CI -> Run workflow. No custom secret or signing key needed for debug. Require the `Tests, lint and debug APK` check in branch protection when available. Reports and APK are private to whoever can access the repository's artifacts; never upload real financial data. No remote CI success is claimed without a completed run.

## Release gates outside this foundation

No device/emulator runtime test, UI automation, migration test, encrypted backup test, production signing or performance/accessibility certification is implied by a successful debug build. Android unit test task may be NO-SOURCE at this milestone; numerical tests live in the JVM engine module. Room migration tests become mandatory when v2 is introduced. Remaining Phase 1 requirements are tracked in PRODUCT.md.

## Actual results

Pending execution.
