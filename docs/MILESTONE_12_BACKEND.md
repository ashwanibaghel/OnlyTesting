# Immutable schedule payment unlink/relink

`ScheduleAction.Unlink` corrects a mistaken association using the exact rule, scheduled date, current journal ID and an explicit reason. It appends a resolution; it never reverses the actual payment, refunds funded category consumption or edits the original association. An active occurrence reopens using its original planned template rather than an unrelated payment's confirmed amount. A stopped future occurrence remains skipped, while original overdue obligations remain visible.

The same actual journal can then be associated with the correct occurrence without another posting or allocation consumption. Exact unlink retries return their original receipt and cannot unlink a later corrected association. A stale journal ID after reverse-and-replace correction, unrelated/foreign-book occurrence or invalid reason is rejected atomically. General command effect review previews and applies the metadata/obligation consequences through the actual repositories.

## Necessary V5 index/migration change

The original historical unique `schedule_occurrences.journalId` index prevented legitimate correction: even after an immutable unlink event, the original row retained its journal ID. A native test reproduced that unique-index failure before this migration; the other three initial unlink tests passed.

V5 changes that index to non-unique and adds SQL guards for one **current** confirmed occurrence per payment across both initial associations and resolution events. Guards resolve the latest event by audited command revision and retain historical references. Repository checks remain in force. Original association/resolution rows remain immutable.

Explicit `4→5` migration validates existing current links before changing the index. Conflicting, missing, cross-book or unsealed current payment associations abort migration without deleting financial history. No data rows are replaced or erased, and no destructive fallback is introduced. V1/V2/V3/V4 exported schemas remain unchanged. Compiler-generated V5 identity is `7a611e7f0f0323694c66f2544a67feff`; table count remains 23. The only declared structural change is the historical index's uniqueness flag.

## Verification

Fourteen native Room tests pass in the focused run: original-template restoration, actual/reserve retention, correct relink without posting, stale association protection, reviewed preview rollback, current-link uniqueness, exact retry, invalid/foreign input, stopped-future and overdue semantics, both raw SQL duplicate guards, immutable historical rows, populated V4 migration/receipt replay and conflicting V4 migration preserving rows/version.

The full Windows/JDK 17 gate cleaned both test outputs and passed in 3m 48s: **339 tests** (85 finance-engine JVM, 198 Room/NATIVE SQLite, 7 codec JVM and 49 pure ViewModel), zero failures/errors/skips. All prior 325 CI test names are retained. Lint reports zero errors and 35 existing warnings; debug assembly passes. The generated schema was structurally compared to V4 after accounting for the reviewed index/version/identity change; no other schema changes were found. Local log: `.tools/phase1-unlink-gate.log`.

No physical-device/emulator/Compose testing or Phase 1 completion is claimed. Investment return/ownership management, reports/performance, backup/security/privacy/reminders and final approved consumer UI remain outstanding.
