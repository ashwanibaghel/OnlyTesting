# General financial command effect review

`FinancialReviewRepository` provides real command previews and reviewed application through the production Room ledger implementation. Hilt supplies `RoomFinancialReviewRepository` with the actual database and ledger. This is a backend API for future approved frontend integration; no consumer UI is introduced.

## Prospective effects without persisted preview money

Preview captures the command's caller-owned collections and canonical fingerprint, reads the current financial position, executes the actual command implementation inside an outer Room transaction and reads its prospective result. A dedicated rollback exception exits that outer transaction before the result is returned. The nested command, audit, postings, allocation journals, occurrences, metadata and book revision are all rolled back.

The returned result is explicitly prospective. It includes before/after account positions, household income/expense, reserve and budget positions, current Safe-to-Spend/obligations, goal funding and consequences. It does not return pretend journal IDs or a pretend successful command receipt. An already-saved exact command is separately identified with its existing real receipt.

Category/month identities make monthly rollover effects stable even though newly created period rows receive different random IDs during preview and eventual application. Forecasts and longer-horizon purchase scenarios remain separate explicitly projected APIs.

## Reviewed application

Application checks the captured command fingerprint and rereads the complete before position, including book date and revision. It executes the command and verifies the after position inside the same outer transaction. A changed command, stale date/revision, failed write or changed outcome rejects/rolls back the whole operation. The command is never rebased silently.

Exact receipt lookup precedes stale-date/revision checks for an already-saved command, preserving acknowledgement-loss retries without another posting. Cancellation and failures before outer transaction completion roll back; a response lost after the completed transaction retrieves the existing receipt.

Preview uses a write transaction while evaluating the real implementation, so cancellation and large-history latency remain part of the later performance gate. It does not publish uncommitted financial data to observers or export hypothetical postings as actual history.

## Verification and remaining scope

Twenty-four native Room cases cover income/expense/fee effects, funded reservation consumption, protected diversion, invalid input, timezone-selected setup, generated rollover identities, reverse-and-replace correction, reconciliation equity, pending clearing, fingerprint/revision/date changes, lost acknowledgements, caller-owned collections, incorrect-outcome rollback, saved-command lookup, cancellation, observer isolation, schedule confirmation/replacement, investment funding without duplicate progress and interruption after nested execution.

The full Windows/JDK 17 gate cleaned both test outputs and passed in 3m 26s: **301 tests** (78 finance-engine JVM, 173 Room/NATIVE SQLite, 7 codec JVM and 43 ViewModel), zero failures/errors/skips. All prior 277 CI test names are retained. Lint reports zero errors and 35 existing warnings; debug assembly passes. Historical/generated schemas remain unchanged. Local log: `.tools/phase1-financial-review-gate.log`.

No schema changes are required. No physical-device/emulator/Compose execution, final visual approval or Phase 1 completion is claimed. Workflow UI integration, complete investment return ownership, reporting/performance, backup/security/privacy/reminders and final consumer UI remain outstanding.
