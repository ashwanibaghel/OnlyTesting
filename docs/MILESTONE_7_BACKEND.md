# Atomic recurring-plan replacement

`ScheduleAction.Replace` stops the retained original rule from the new rule's start date and creates a separately identified replacement inside the same financial command/transaction. The receipt identifies both the replacement and the original rule. The command fingerprint includes the complete replacement template, calendar, exceptions, mandatory status and reason; old command encodings remain unchanged.

Replacement does not post expected income/expenses, move money or change existing payments. Earlier overdue occurrences remain obligations under the original plan. A replacement cannot erase past due dates, reuse the original identity or extend an already-stopped plan. Existing confirmed, skipped or reopened occurrences on/after the new start cause rejection so a reviewed decision cannot be duplicated or silently resurrected. Choose a start after those decisions or explicitly resolve them first.

An invalid replacement, duplicate identity, conflict or failed validation rolls back the stop, rule insertion, command, audit and book revision together. Exact retries return the original receipt. Collections are detached before review/fingerprinting, including replacement splits and exceptions. All data persists in the existing V4 tables; no migration/schema rewrite is needed.

Eight new native Room cases cover overdue/history retention, failed replacement rollback, exact retry/different-command duplicate rejection, early future confirmation, skipped/reopened conflicts, prior pending confirmation, backdating/identity/stopped-rule protection and persisted receipt replay. The complete 48-case schedule suite passes locally.

The full Windows/JDK 17 gate cleaned both test outputs and passed in 3m 4s: **268 tests** (78 finance-engine JVM, 140 Room/NATIVE SQLite, 7 codec JVM and 43 ViewModel), zero failures/errors/skips. All prior 260 CI test names are retained. Lint has zero errors and 35 existing warnings; debug assembly passes. Historical/generated schemas remain unchanged. Local log: `.tools/phase1-replacement-gate.log`.

Final consumer UI remains pending approved visual mockups. Unlink/relink management, investment return ownership, reporting/performance, security/backup/privacy/reminders and final product integration are still outstanding. This increment does not claim Phase 1 completion or physical-device/emulator execution.
