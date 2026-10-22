# Reusable financial workflow ViewModel

`FinancialWorkflowViewModel` prepares and submits real `LedgerRepository` commands and obtains protected-diversion consequences from `PlanningRepository`. Its Hilt dependencies use the actual Room repositories and current-day session boundary. No consumer screens, visual designs or preview fixtures are introduced.

## Submission and review contract

Each form has a stable `FinancialOperation` key, retained across edits and recreation. New form instances use `FinancialOperation.newForm()`. That key is also the durable financial command ID. Reusing it with changed financial values cannot silently create another posting: the repository's canonical fingerprint rejects conflicting payloads.

Every relevant edit must call `edited` synchronously. Review tokens bind the form identity, input version, exact captured command, book revision and book date. Input changes, date changes and financial revisions invalidate reviews. Confirmation consumes the review synchronously before launching a coroutine, then rereads current state before attempting a write. A stale preflight read/date/revision failure does not expose an unattempted financial command as retryable.

Retries refer only to the matching failed form and command, retaining the exact payload, command ID and expected revision. They are not rebased onto refreshed financial state. Invalid input, unrelated preview failures, late callbacks and switching forms cannot restore an earlier retry. Caller-owned split lists, setup declarations, recurrence exceptions and consent impacts are detached before review/fingerprinting/suspension.

An acknowledged command receipt is retained before refreshing data. A later read failure is a storage error; it does not enable another submission of that form. Lost acknowledgements retry the original command and obtain the repository's existing receipt. An observer failure cannot release an independent submission lock. Older observer emissions cannot regress accepted financial revisions.

The current-day observer applies previously configured monthly rollover before accepting reviews for the new period. Rollover errors remain separate from financial-command errors and do not stop observation. No scheduled income or expense is automatically confirmed.

## Lifecycle and integration boundary

This is an API/controller increment for the pending approved frontend. Final navigation must retain form identities in its lifecycle state and wire every relevant field edit to invalidation. Exact pending command serialization, recovery messaging and final Compose interaction/accessibility tests are still part of frontend integration. Recreation with the same form key and changed revision/payload is safely rejected; this increment does not claim automatic recovery of unsaved form contents after process death.

The original temporary ledger screens and their 19 regression tests are retained. They are not presented as the Phase 1 consumer product. High-fidelity visual approval is still required.

## Verification

Twenty-four new JVM ViewModel behavior cases exercise invalid-input/preview failures after failed writes, form/review identity, changed-back fields, revision/date invalidation, fresh preflight failures, lost acknowledgement without duplicate posting, rapid confirmation, acknowledged-write/read failure, receipt retention, mutable collection capture, late preview/write results, observer failure, older emissions, retained form identity and monthly preparation/recovery.

The complete Windows/JDK 17 clean gate passes in 3m 21s: **260 tests** (78 finance-engine JVM, 132 Room/NATIVE SQLite, 7 codec JVM and 43 ViewModel), retaining all 236 previous CI test names, with zero failures/errors/skips. Lint reports zero errors and 35 existing warnings; debug assembly passes. The local log is `.tools/phase1-workflow-gate.log`. All historical/compiler-generated schemas remain unchanged.

No physical-device, emulator or rendered Compose testing is claimed. Reports/performance, remaining investment/schedule management, security/backup/privacy/reminders and final consumer UI remain outstanding.

Commit `d53319e2f03c82ab91c9316fb9bcf98c85d36763` also passes [CI run 39873139064](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39873139064). Downloaded report artifact SHA-256 `56ebae8cd60b381d84d1cf4671fa7c171062d43713f6001319ac08ff174886dd` and independently parsed JUnit XML confirm 260 tests, zero failures/errors/skips.
