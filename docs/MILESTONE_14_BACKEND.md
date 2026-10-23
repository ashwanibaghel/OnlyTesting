# Actual financial reporting read models

`FinancialReportsRepository` reads sealed persisted actual postings in one Room transaction at one book revision. Hilt exposes the repository for later native Compose integration. A report provides both posted and cleared operating totals, signed income/expense categories, monthly totals, dated account opening/closing balances, daily tracked-asset trends and unresolved pending count. It never writes journals, allocations, commands or receipts.

## Recognition and financial boundaries

- Posted activity is recognized on its effective date. Cleared activity is recognized on its actual clearing date, including later clearing events for an earlier transaction. A historical end date excludes later clearing; it does not borrow today's cleared status.
- Income and expense come from their ledger roles. Internal transfers and investment principal are not household income/expense. Transfer fees remain expense; opening/reconciliation equity is separate. Every basis/month checks `asset movement = income − expense + equity movement`.
- Refund/reversal legs remain signed on their own recognition date. Reverse-and-replace corrections include original, reversal and replacement exactly once. They do not silently delete an earlier period's activity.
- Pending originals reversed by the report end date are no longer actionable pending entries. Future-dated reversal/clearing events relative to that end date do not rewrite its pending count. The count is outstanding across the book at period end, not just newly pending entries within the period.
- Virtual reservations, budget permissions, goal targets, expected schedules and projected income are excluded from actual reports. Manually recorded investment contributions are actual transfers; their fees alone affect operating expense.
- Trends are **tracked asset book values**, including archived accounts. They are not live investment prices or total personal net worth: Phase 1 does not model liabilities, trading or market valuation. Current account labels/archive metadata accompany dated values. Backdated entries are included in current knowledge of past activity; this is not a persisted knowledge-at-the-time snapshot.
- Savings rate is an exact `(saved, income)` ratio only when net recognized income is positive. The read model does not fabricate a percentage with zero/negative income or silently round integer paise through floating point.

## Bounds and arithmetic

An inclusive report covers 1–366 days and cannot extend into the future in the configured book timezone. Daily/monthly output includes gaps explicitly. Source postings are streamed through a cursor; no full history object graph or SQL floating-point aggregation is used. BigInteger accumulators tolerate intermediate cancellation and convert each displayed amount with the engine's existing checked `toLongChecked` conversion, now exposed for repository reuse. This preserves API 26 compatibility instead of calling the API 31 `BigInteger.longValueExact` method flagged by lint in the first full run. Out-of-range totals, account endpoints or daily points reject the report without changing stored money; a later net zero cannot excuse an overflowing intermediate dated point. Reports cap account/category groups at 10,000 and fail explicitly rather than silently truncate.

Historical account endpoints require scanning relevant persisted history before the report end, so source work still grows with history size. The new reporting boundary bounds Kotlin aggregation state by account/category groups and at most 366 daily/13 monthly points; it does not fix legacy mutation/planning snapshot performance. Caller cancellation is checked during scanning, and all cursors are closed on failure/cancellation. API availability is checked by lint and the [Android BigInteger reference](https://developer.android.com/reference/java/math/BigInteger#longValueExact()).

## Verification status

Sixteen new native Room cases pass: opening equity, splits/transfers/fees, actual clearing recognition, signed cross-period refunds, reverse-and-replace, cancelled pending entries, reconciliation equity, virtual/planned exclusion, funded manual investment, archived/cross-book isolation, dated monthly/daily gaps, checked large cancellation, historical overflow rejection, period/timezone validation, revision observation/read-only behavior and empty/cancelled reads. The existing 10,053-journal fixture also verifies actual report totals and dated assets.

After replacing the API-31-only call identified by lint, the clean full Windows/JDK 17 gate passes **366 tests** (85 finance-engine JVM, 225 Room/NATIVE SQLite, 7 codec JVM and 49 pure ViewModel), zero failures/errors/skips. All prior 350 CI test identities remain present. Lint has zero errors/35 existing warnings; debug assembly passes. All Room V1–V6 schemas are unchanged. Local log: `.tools/phase1-reports-gate.log`.

The full host run reported 429 ms for the 10,053-journal actual report, with posted income/expense 5,026 minor units each and final tracked assets 1,000 minor units, matching the synthetic fixture. This is single-run host evidence, not a timing guarantee or device-performance claim. Fixtures remain explicitly synthetic. Final consumer UI, device/emulator/Compose execution and Phase 1 completion are not claimed.

Commit `1cf74ae332aebc889b1db9e62944ef6293743993` passes [CI run 39888617979](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39888617979). Downloaded reports SHA-256 `e74a90286cd159bbbf1b05a985c4ad340c6889678156ce71c9a3fa4c2c3a073f` and independent JUnit parsing confirm all 366 local test identities with zero failures/errors/skips, lint zero errors/35 warnings and successful schema/debug gates.
