# Atomic financial setup and current-day session boundary

This increment supplies persisted setup/session APIs for the future approved Compose frontend. It does not implement or approve consumer screens, and Phase 1 remains incomplete.

## Financial setup

`LedgerAction.Setup` creates explicitly declared accounts and opening equity together with the financial profile in one Room transaction, command receipt, audit event and book revision. The selected book timezone controls opening-date validation from the first posting. An invalid later account, unsupported date or aggregate overflow rolls back the entire setup.

Metadata-only accounts must each receive an explicit opening declaration, including zero. Their IDs, labels, account kinds and archive states are retained. Accounts with actual history cannot be redeclared: their profile is completed through `PlanningAction.Configure`, leaving all financial history and balances intact. A completed profile cannot be reset to permit a second onboarding opening. Exact command retries return the original receipt without duplicate accounts or postings.

Initial account equity is neither income nor budget funding. Investment account balances do not become liquid Safe-to-Spend. No categories, income, savings ownership or reservations are fabricated.

## Session APIs

`FinanceSessionRepository` supplies a consistent current-day planning snapshot and explicit setup modes: new book, metadata-only legacy book, existing financial book and complete. Book revisions refresh balances, allocations, obligations and goal warnings together. A cancellable minute clock pulse also refreshes date-sensitive warnings after the book's local midnight, even without a financial transaction. Date refresh does not itself post money.

Current cash-flow forecasts and purchase simulations take their start date from the book timezone; callers cannot pair today's actual opening money with a misleading historical start date. The application boundary limits these scenarios to 0–366 days. Future income remains projected and never funds current Safe-to-Spend.

`prepareCurrentPeriod` atomically applies only already-configured rollover policies when an older open budget exists. It produces an audited financial command, preserves actual account money and is a no-op after preparation. Concurrent preparations produce one rollover. Observing the session is read-only; the eventual lifecycle coordinator must explicitly invoke period preparation.

## Verification

Fourteen native Room setup tests cover declared equity, idempotency, duplicate declarations, profile/account/audit rollback, overflow, timezone boundaries, metadata preservation, existing-history protection, completed-zero setup and close/reopen persistence. Eight native Room session tests cover setup routing, current-day forecasts, invalid scenarios, audited/no-op/concurrent rollover and clock/revision-driven goal-warning updates.

The full Windows/JDK 17 gate cleaned both test outputs and passed: **236 tests** (78 finance-engine JVM, 132 Room/NATIVE SQLite, 7 template-codec JVM and 19 original ViewModel regressions), zero failures/errors/skips. All 214 test names from the previous verified CI report remain present. Lint reports zero errors and 35 existing warnings; debug APK assembly passes. Historical/compiler-generated schemas remain unchanged. The local log is `.tools/phase1-setup-session-gate.log`.

No database version or historical schema changes are needed for these APIs. No physical-device or emulator execution is claimed.

Commit `9bbc2b136d70b209084dc3710a280cdccb330ca0` also passed [CI run 39872118649](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39872118649). Its downloaded report artifact has SHA-256 `3e88fb80ea611a263017c8c4a4b7af669bad3141709158965969d00d0602dd1f`; independent JUnit XML parsing confirms all 236 tests, zero failures/errors/skips. CI retains the debug APK as build evidence, not a partial-screen acceptance request.

## Remaining work

Further backend work includes investment return attribution, schedule replacement/unlinking, reports and large-history handling, encrypted backup/validated restore, privacy/reminders and reusable ViewModel workflow controllers. Final consumer UI remains on hold pending explicit review of high-fidelity visual mockups. Engineering reference specifications and unreviewed mockup files do not constitute visual approval.
