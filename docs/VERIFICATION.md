# Milestone 2 verification record

## PR #1 review fixes — 2026-10-23

Review base: `df2791073410621762a69338766b2077c60ef62f`, on the existing `milestone-2-accounts-ledger` branch. PR #1 remains open; no merge or milestone 3 work is part of these fixes. The original implementation evidence below is historical. The exact pushed fix SHA and latest CI run are recorded in [PR #1](https://github.com/ashwanibaghel/OnlyTesting/pull/1); [branch CI runs](https://github.com/ashwanibaghel/OnlyTesting/actions/workflows/android-ci.yml?query=branch%3Amilestone-2-accounts-ledger) expose each run's SHA and downloadable reports.

Retry state now contains the exact failed command. Retry callbacks must identify that command; a callback from an earlier failure cannot execute a later command. Input/operation changes, local validation failures and preview failures discard earlier retries. Unchanged failed form resubmission preserves the original command ID, payload and expected revision, including after an acknowledgement or snapshot refresh failure.

Reconciliation review binds an immutable input tuple (account, raw statement date, raw balance, basis) to the repository preview and revision. Edit callbacks invalidate the review synchronously; Apply checks the review ID, current inputs and revision and consumes the review before launching. A late preview result cannot restore a review invalidated by an edit. Book revision changes invalidate existing previews. Failed Apply can retry only its original command.

Additional demonstrated issues fixed: successful form IDs remain remembered after unrelated commands, and an observation failure cannot release the busy lock of a running submission. Dropdown items also respect disabled state while an operation runs. Existing reversal/clearing constraints and repository idempotency remain unchanged.

| Local verification | Actual result |
| --- | --- |
| Finance engine JVM tests | 52 passed |
| Room/NATIVE SQLite integration tests | 43 passed: all original 41 plus two ViewModel-to-Room lost-acknowledgement retry cases |
| Deterministic ViewModel regression tests | 19 passed |
| Total | **114 passed**, 0 failures, 0 errors, 0 skipped; original 93 retained |
| Lint | Passed, 0 errors, 35 existing warnings |
| Debug APK | Assembly passed; 10,778,457 bytes; SHA-256 `573afb7273766e7870f8d3861905ca71c407d0760ac1bf2bfe3e48c088d92fae` (local APK, not CI ZIP) |
| Schema history | Generated v1 and v2 JSON byte-identical to the reviewed commit; no schema, migration or accounting repository changes |
| Whitespace | `git diff --check` passed |
| Physical device / emulator / Compose interaction | Not performed; automated Room tests run on host Robolectric API 28 with NATIVE SQLite |

The 19 ViewModel cases cover invalid financial input after failure, failed/invalid reconciliation previews, stale retry callbacks, edit/operation invalidation, all four reviewed field changes, changed-back fields, late previews, revision invalidation, exact Apply payload/revision, repeat clicks, retry after lost acknowledgement or refresh failure, saved-form retention and observer errors during submission. The two new Room cases commit income or reconciliation, deliberately lose the acknowledgement, retry the exact command and assert unchanged snapshots and exactly one journal/two postings, with one reconciliation record where applicable.

Both test tasks were cleaned before the final full local run, which completed with `BUILD SUCCESSFUL in 4m 56s`:

```powershell
$env:JAVA_HOME = 'C:\Users\ashwa\.jdks\temurin-17.0.13'
$env:ANDROID_HOME = 'C:\Users\ashwa\AppData\Local\Android\Sdk'
.\gradlew.bat --max-workers=2 :finance-engine:cleanTest :app:cleanTestDebugUnitTest :finance-engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

## Original implementation verification (historical)

Date: 2026-10-23 (Asia/Kolkata). This section verifies the accounts/ledger implementation; the historical foundation baseline is separate below.

## Source and review

- Branch: `milestone-2-accounts-ledger`, based on main `63d60517e930458c95a035de810e56d1988bb9e8`.
- Verified implementation commit: `01dffe6dea8eac5d0d4655b64f677f1c10530394`.
- [Pull request #1](https://github.com/ashwanibaghel/OnlyTesting/pull/1) targets main and is open for human review. Not merged.
- [Successful implementation CI run 39844459785](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39844459785).
- [Latest branch CI runs](https://github.com/ashwanibaghel/OnlyTesting/actions/workflows/android-ci.yml?query=branch%3Amilestone-2-accounts-ledger) also include documentation-only follow-up commits; each run records its exact SHA.

## Executed checks

| Check type | Result |
| --- | --- |
| Pure Kotlin finance-engine JVM tests | **52 passed**, 0 failures, 0 errors, 0 skipped; includes all 47 baseline tests and 5 new exact-aggregate cases |
| Real Room/NATIVE SQLite host integration tests | **41 passed**, 0 failures, 0 errors, 0 skipped; generated Room implementation, real SQLite files, Robolectric 4.15.1, simulated Android API 28 |
| Total automated tests | **93 passed** locally on Windows/JDK 17 and in Ubuntu/JDK 17 GitHub Actions |
| Migration-specific cases | **3 passed**, included in the 41 integration tests, not additional tests |
| Android lint | Passed: **0 errors, 35 warnings** (1 older target SDK, 3 AGP advisories, 30 dependency advisories, 1 missing launcher icon) |
| Debug APK | `:app:assembleDebug` passed locally and remotely; CI APK artifact uploaded |
| Room schema history | CI committed-schema check passed; v1 unchanged, generated v2 committed |
| Git / source checks | Whitespace check passed; no destructive migration, floating-point money, new internet permission or production ID change |
| Physical device / emulator / Compose interaction | **Not executed / unverified**; host integration is not device instrumentation |

The final local command completed with `BUILD SUCCESSFUL in 3m 3s`, 65 actionable tasks. Reproduce without Android Studio or an emulator:

```powershell
$env:JAVA_HOME = 'C:\Users\ashwa\.jdks\temurin-17.0.13'
$env:ANDROID_HOME = 'C:\Users\ashwa\AppData\Local\Android\Sdk'
.\gradlew.bat --max-workers=2 :finance-engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

Local APK: `app/build/outputs/apk/debug/app-debug.apk`. Local JUnit XML: `finance-engine/build/test-results/test/` and `app/build/test-results/testDebugUnitTest/`. Lint: `app/build/reports/lint-results-debug.html`.

## Migration execution evidence

All three named tests appear as passed in the downloaded CI JUnit XML:

1. `migrationFromExportedV1PreservesAllMetadataAndAllowsRealPosting`: creates a file from the committed v1 schema/identity, migrates through Room to v2, preserves bank/cash/investment IDs, duplicate names and archive state, confirms zero invented monetary history, posts real income, closes/reopens and checks foreign keys plus immutability triggers.
2. `migrationOfEmptyV1ProducesValidV2`: upgrades an empty v1 database and records a genuine opening balance afterward.
3. `unsupportedLegacyCurrencyFailsMigrationWithoutDeletingV1`: attempts migration of USD metadata, verifies rejection and reopens the retained v1 file with original currency and version.

Room performs its normal generated v2 schema validation on opening the migrated file. The tests use the production migration and callback, not a duplicate mock schema. Device-specific migration/upgrade execution remains unverified.

## Persisted acceptance scenario

`acceptanceScenarioPersistsAndReversesWithoutInflatedReports` passed in both host environments. Every result is read from the persisted repository:

| Point in scenario | SBI | Cash | Liquid funds | Income | Expense |
| --- | ---: | ---: | ---: | ---: | ---: |
| After INR 20,000 opening, INR 30,000 salary, INR 5,000 expense, INR 10,000 transfer | INR 35,000 | INR 10,000 | INR 45,000 | INR 30,000 | INR 5,000 |
| After reversing that expense | INR 40,000 | INR 10,000 | INR 50,000 | INR 30,000 | INR 0 |

The test closes the file-backed database and creates a new Room/repository instance, then compares the full reopened snapshot with the saved snapshot. This proves storage reopen, not manual phone/process-death UI behavior.

## Schema changes and financial guarantees

Room version 2 has 10 tables and 14 declared secondary indexes. New tables: `books`, `ledger_accounts`, `commands`, `journals`, `postings`, `journal_seals`, `clearing_events`, `audit_events`, `reconciliations`. Existing `accounts` gains a book foreign key/index and retains original metadata. V2 identity: `0cc0bf451de72816a077c1310a3dfd56`. The original v1 JSON is unchanged.

- Balances derive from sealed signed postings; no editable cached balance. Negative cash is represented honestly. Investment accounts are non-liquid; archived balances remain in totals.
- Opening/adjustment equity and transfer principal do not inflate income/expense. Transfer fees are expenses. Signed reversal control legs cancel original report classification.
- Every command is one database transaction, including journal legs, seals, audit, reconciliation evidence, receipt and book revision. A failed replacement rolls back its preceding reversal.
- Unique global command keys plus canonical payload fingerprints make retries idempotent; different payload/key reuse and stale revisions are rejected. Concurrent duplicate submissions were tested through both one Room instance and two separate connections.
- Foreign keys are enabled across Android's connection pool and asserted on open. Real orphan inserts fail. Cross-book, missing, archived and unsupported-currency operations are rejected.
- SQLite triggers prevent mutation/deletion/replacement of immutable history, adding legs to sealed journals, unbalanced seals, invalid ledger roles and invalid income/expense/transfer classification. Reversals must mirror the original and cannot be repeated.
- Exact integer aggregation checks final signed 64-bit bounds. Gross positive journal amount is limited to Long.MAX_VALUE; overflow aborts. Compatible exact range conversion avoids BigInteger API 31 calls on the API 26 baseline.
- Clearing events are append-only. Reconciliation distinguishes posted versus cleared balances at the statement date and only adjusts through an explicit equity journal with a reason.

See [MILESTONE_2.md](MILESTONE_2.md) for the precise SQLite versus repository enforcement boundary, including why unsealed drafts are invisible and only the repository may commit financial commands.

## CI artifacts and integrity

- [Debug APK ZIP](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39844459785/artifacts/12454482629): 10,289,513 bytes, SHA-256 `d1a964a9519b30987aac009662fe1defe1fddf431932924f014027e13bcebe03`.
- [JUnit and lint reports](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39844459785/artifacts/12454327928): SHA-256 `6c61efb204c871334d55e6e4b3288b5b396fc64e66df7a9932ebc8ba744faef0`; downloaded, checksum verified, XML parsed to establish the counts above.
- [Generated Room schema history](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39844459785/artifacts/12454797517): SHA-256 `3619598fc6c7de75ca158e321b2e662614dbbfa892d0404fdbee34f3262d407e`.

These hashes describe artifact ZIPs. Artifacts are retained for 14 days. Actions is enabled; no custom secret or production signing key was required. The GitHub connector could not create the PR (403); existing local Git authentication successfully created it through GitHub's API, without a user authentication change.

## Limitations and remaining checks

There are no known failing automated checks in the verified implementation run. Earlier development failures (plugin isolation, connection-pool foreign keys and an API 31 BigInteger call) were corrected and all checks rerun; no financial assertion or lint error was suppressed.

The UI is temporary. Reverse-and-replace corrections and complete account/date search are repository APIs; the screen exposes reversal and basic text search. It shows the latest 100 matching history rows. Snapshot aggregation currently loads the book history in memory; large-book performance/pagination needs a later optimization without changing ledger truth.

Actual/future date restrictions and archive/correction policy are documented in MILESTONE_2.md. INR only; no FX/debt/credit-card semantics. App-private storage uses Android device protection; application-level database encryption and protected export/restore remain future work. The 35 lint advisories and provisional application ID/SDK targets require release hardening. No phone UI, device upgrade, physical-device migration or encrypted-backup verification is claimed.

Next: human review of PR #1 and manual phone acceptance, then milestone 3 budgets and virtual allocations with reservation conservation, default rollover off and protected persistent savings/goal funds. Do not merge before review.

## Files created or changed

Inventory relative to verified main:

- [README.md](../README.md)
- [app/build.gradle.kts](../app/build.gradle.kts)
- [app/schemas/README.md](../app/schemas/README.md)
- [app/schemas/com.fintrack.data.local.FinTrackDatabase/2.json](../app/schemas/com.fintrack.data.local.FinTrackDatabase/2.json)
- [app/src/main/java/com/fintrack/MainActivity.kt](../app/src/main/java/com/fintrack/MainActivity.kt)
- [app/src/main/java/com/fintrack/data/ledger/CommandFingerprint.kt](../app/src/main/java/com/fintrack/data/ledger/CommandFingerprint.kt)
- [app/src/main/java/com/fintrack/data/ledger/RoomLedgerRepository.kt](../app/src/main/java/com/fintrack/data/ledger/RoomLedgerRepository.kt)
- [app/src/main/java/com/fintrack/data/local/AccountEntity.kt](../app/src/main/java/com/fintrack/data/local/AccountEntity.kt)
- [app/src/main/java/com/fintrack/data/local/FinTrackDatabase.kt](../app/src/main/java/com/fintrack/data/local/FinTrackDatabase.kt)
- [app/src/main/java/com/fintrack/data/local/LedgerDao.kt](../app/src/main/java/com/fintrack/data/local/LedgerDao.kt)
- [app/src/main/java/com/fintrack/data/local/LedgerEntities.kt](../app/src/main/java/com/fintrack/data/local/LedgerEntities.kt)
- [app/src/main/java/com/fintrack/data/local/LedgerSchema.kt](../app/src/main/java/com/fintrack/data/local/LedgerSchema.kt)
- [app/src/main/java/com/fintrack/di/StorageModule.kt](../app/src/main/java/com/fintrack/di/StorageModule.kt)
- [app/src/main/java/com/fintrack/domain/ledger/LedgerModels.kt](../app/src/main/java/com/fintrack/domain/ledger/LedgerModels.kt)
- [app/src/main/java/com/fintrack/ui/ledger/LedgerScreen.kt](../app/src/main/java/com/fintrack/ui/ledger/LedgerScreen.kt)
- [app/src/main/java/com/fintrack/ui/ledger/LedgerViewModel.kt](../app/src/main/java/com/fintrack/ui/ledger/LedgerViewModel.kt)
- [app/src/test/java/com/fintrack/data/ledger/RoomLedgerRepositoryTest.kt](../app/src/test/java/com/fintrack/data/ledger/RoomLedgerRepositoryTest.kt)
- [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)
- [docs/ENVIRONMENT.md](../docs/ENVIRONMENT.md)
- [docs/MILESTONE_2.md](../docs/MILESTONE_2.md)
- [docs/PRODUCT.md](../docs/PRODUCT.md)
- [docs/SECURITY_AND_INTEGRITY.md](../docs/SECURITY_AND_INTEGRITY.md)
- [docs/VERIFICATION.md](../docs/VERIFICATION.md)
- [finance-engine/src/main/kotlin/com/fintrack/engine/Money.kt](../finance-engine/src/main/kotlin/com/fintrack/engine/Money.kt)
- [finance-engine/src/main/kotlin/com/fintrack/engine/Planning.kt](../finance-engine/src/main/kotlin/com/fintrack/engine/Planning.kt)
- [finance-engine/src/test/kotlin/com/fintrack/engine/AggregateTest.kt](../finance-engine/src/test/kotlin/com/fintrack/engine/AggregateTest.kt)
- [gradle/libs.versions.toml](../gradle/libs.versions.toml)

## Historical foundation baseline

Before milestone 2, main `63d6051` had 47 finance-engine tests, no Android module tests, Room v1 metadata only and 32 lint warnings. [Baseline CI run 39838616052](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39838616052) passed. Those older results do not establish milestone 2 verification.
