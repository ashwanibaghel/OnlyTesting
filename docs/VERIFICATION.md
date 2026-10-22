# Verification record

Date: 2026-10-23, Asia/Kolkata. Results below come from executed commands and downloaded CI JUnit/lint reports.

## Reproduction

JDK 17 and Android SDK 35/build-tools 35.0.0, no IDE/emulator required:

```powershell
.\gradlew.bat -PengineOnly=true :finance-engine:test --console=plain
.\gradlew.bat :finance-engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

On Linux use `./gradlew`. Set JAVA_HOME and ANDROID_HOME locally; these are not global machine changes. Unit tests cover exact monetary parsing, precision rejection, overflow, currency mismatches, balanced postings, transfer conservation, split validation, reversal cancellation, 1,000 seeded transfer invariants, reserve consumption, deficits, goal ceiling rounding, diversion preview and deterministic date-based forecast assumptions/shortfalls.

## GitHub setup

Remote requested by user: `https://github.com/ashwanibaghel/OnlyTesting.git`. Initially empty (no branch refs). Local `main` is initialized and origin configured. GitHub connector reported read permission and `push: false`; local Git credentials successfully published the initial foundation, so no user authentication change was needed.

To run remotely, publish the reviewed foundation to this repository with an account that has write access, enable GitHub Actions and permit the pinned actions. Workflow runs on push/PR or manually through Actions -> Android CI -> Run workflow. No custom secret or signing key needed for debug. Require the `Tests, lint and debug APK` check in branch protection when available. Reports and APK are private to whoever can access the repository's artifacts; never upload real financial data. No remote CI success is claimed without a completed run.

## Release gates outside this foundation

No device/emulator runtime test, UI automation, migration test, encrypted backup test, production signing or performance/accessibility certification is implied by a successful debug build. Android unit test task may be NO-SOURCE at this milestone; numerical tests live in the JVM engine module. Room migration tests become mandatory when v2 is introduced. Remaining Phase 1 requirements are tracked in PRODUCT.md.

## Actual results

| Check | Actual result |
| --- | --- |
| Official Gradle wrapper SHA-256 | Passed: `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17` matches Gradle's published checksum |
| Local JDK 17 engine-only Gradle run | BUILD SUCCESSFUL; 47 tests, 0 failures, 0 errors, 0 skipped |
| GitHub Android build | [Run 39837664813](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39837664813), commit `76f2bc91569f203568d1820a276a9fcf3f43ba0e`: success, build took 2m 25s |
| CI finance-engine tests | JUnit XML confirms 47 tests, 0 failures, 0 errors, 0 skipped; includes 1,000 seeded transfer cases inside one test |
| Android module unit tests | `:app:testDebugUnitTest NO-SOURCE`; no Android-specific unit tests claimed |
| Android lint | Passed with **0 errors and 32 warnings**; warnings concern older target/dependency versions and missing launcher icon; release hardening must address them |
| Debug assembly | `:app:assembleDebug` succeeded; debug APK artifact uploaded, 10,137,881-byte ZIP |
| Room schema | Generated v1 downloaded, artifact checksum verified, original JSON committed; identity `1478d2741cb49467d347d74f6822b5a7` |
| Static file checks | Version catalog TOML, all 5 Android XML files and workflow YAML parsed; staged Git whitespace check passed |
| Local full Android build | Initial attempt stopped during dependency downloads; no local APK claimed. Complete Android validation was performed remotely |
| Phone / emulator runtime | Not executed; no device or emulator launched |

Artifacts from the successful run:

- [Debug APK ZIP](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39837664813/artifacts/12452575587), SHA-256 `96dfa968fef34b7fb281f1e5acc1928f7d962c35c30a730af54bbefa39eecdc8`.
- [JUnit and lint reports](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39837664813/artifacts/12451148262), SHA-256 `0693dddf228232da37af96862543d7aec17a15861490345bf6cc92375c422b9a`.
- [Generated Room schema](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39837664813/artifacts/12452381022), SHA-256 `22dfc04797dfbabf7bcf1fb050388cdf6d8c844289181e418c6c6d659b9a40b0`.

Artifacts are retained for 14 days; rerun CI if expired. The first CI run failed because Android setup requested the removed `tools` SDK package. Explicit `platform-tools` configuration fixed that failure; the complete subsequent build passed. Node deprecation notices from third-party setup/upload actions did not fail CI and are separate from app lint warnings.

Next milestone: accounts and immutable ledger with database transactions, opening equity, splits/transfers, idempotency, reversal corrections and reconciliation. Confirm the product proposals in PRODUCT.md before dependent features; they do not prevent reviewing this foundation.
