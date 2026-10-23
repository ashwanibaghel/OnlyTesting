# Workflow integration with actual reviewed command effects

The reusable `FinancialWorkflowViewModel` now obtains prospective effects through the real `FinancialReviewRepository` and applies the exact reviewed object. Both normal financial commands and protected-diversion workflows use the production review/apply boundary. State exposes explicitly prospective before/after positions for the future approved frontend.

Preparing a review captures inputs synchronously, consumes prior retry/review state and starts one asynchronous evaluation. Rapid preparation callbacks cannot start another preview while busy. Late generic/diversion results after editing cannot restore an old form. Failed previews never expose a previous financial command as retryable.

Confirmation and retry retain the original command, fingerprint and prospective result. An unchanged failed form can recover its already-saved receipt using the original command/revision; it cannot silently rebase. Acknowledged receipts survive refresh failures and prevent another form submission. Form identity still needs final navigation/lifecycle retention; this increment does not claim automatic restoration of unsaved input contents after process death.

The observer automatically retries transient source failures with a bounded 1–30 second backoff. Storage errors remain visible while unavailable, reviews are invalidated and recovery resumes financial/goal-warning updates. This recovery never releases an independent preview/write lock or automatically resubmits a financial command. ViewModel cancellation stops the observer and its retry timer.

## Verification

All 24 existing workflow test names are retained. Six additional JVM cases cover repository-effect exposure/exact application, late generic results, failed previews after failed writes, rapid preparation, saved-receipt recovery and resumed observation after a transient error. The 30-case JVM fixture closes its ViewModel lifecycle inside each coroutine test; a retry-timer fixture cleanup hang was diagnosed with a thread dump and corrected without skipping assertions or disabling production recovery.

Three additional Room/NATIVE SQLite tests construct the real ViewModel with the real session, planning, financial-review and ledger repositories. They verify prospective income plus rapid confirmation, invalid financial preview followed by corrected expense, and a lost response after completed real application followed by exact retry. Actual persisted balances/history are asserted; preview and stale callbacks do not create postings.

The focused 33-case run passes. The full Windows/JDK 17 gate cleaned both test outputs and passed in 3m 23s: **310 tests** (78 finance-engine JVM, 176 Room/NATIVE SQLite including the three real workflow cases, 7 codec JVM and 49 pure ViewModel), zero failures/errors/skips. All prior 301 CI test names are retained. Lint reports zero errors and 35 existing warnings; debug assembly passes. Historical/generated schemas remain unchanged. Local log: `.tools/phase1-reviewed-workflow-gate.log`.

No schema changes, consumer visual implementation, physical-device/emulator/Compose execution or Phase 1 completion is claimed. Investment return/ownership management, schedule unlinking, reports/performance, backup/security/privacy/reminders and final consumer UI remain outstanding.

Commit `617a61d534ca069137e31f3392ef4e21d98656e0` also passes [CI run 39877952770](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39877952770). Downloaded report SHA-256 `3782934dc0560616399999c6f1c4c6c7bc8a89aee13ab1289a6857c1f457d9ca` and independently parsed JUnit XML confirm 310 tests, zero failures/errors/skips.
