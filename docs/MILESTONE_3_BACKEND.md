# Budgets, reservations and goal persistence — backend gate

This is a verified backend milestone of the Phase 1 development branch, not a finished consumer application. Final frontend implementation is held for the approved Antigravity visual handoff; supplied design specifications are drafts.

## Persisted behavior

- Budget limits, monthly policy and carried permission are distinct from actually funded category reserves. Off is the default rollover policy. Explicit month advancement audits release of remaining funding or transfers it to the next category month; goals/emergency/savings persist.
- Virtual journals contain signed, balanced ownership legs and an immutable seal. They never change an actual account balance. Funded ownership is derived from sealed legs; unassigned/deficit derives from real liquid money minus reservations.
- Actual expense/split/transfer-fee posting and matching funded-category consumption share the original Room transaction, command receipt and book revision. Principal transfers remain excluded from expenses. Unfunded portions consume free actual money.
- Reversal restores at most the original consumed funding in the same open period. A closed-period refund increases actual unassigned money and does not resurrect a closed reservation. Reverse-and-replace and induced allocation failures roll back every dependent change.
- Savings, emergency and goal/investment reserves are protected. Reducing one requires a reason and a reviewed consent containing book revision, date, source/destination, exact amount and recomputed goal impacts. Stale or mismatching confirmations abort. All changed goal assessments, including indirect priority effects, are included.
- Goals persist target/deadline/priority/monthly contribution/anchor. Progress is funded money; monthly capacity is an explicitly configured projection, never current cash. Goal funding and diversion recalculate required contribution and projected completion without market assumptions.
- Financial profiles persist timezone, disjoint safety buffer, explicit projected monthly capacity, obligation horizon and setup-completion flag. Scheduled-obligation persistence and timezone-bound schedule processing are the next gate; no future income is credited as current money.

## Migration and enforcement

V3 adds seven tables: `financial_profiles`, `reserve_buckets`, `monthly_budgets`, `financial_goals`, `allocation_journals`, `allocation_legs`, `allocation_seals`. Explicit `2→3` migration composes with the original `1→2` migration. Both historical exported schemas remain byte-identical to verified PR #1. New schema is compiler-generated; no destructive fallback.

SQLite protects virtual journal immutability, sealed-leg exclusion, book ownership, foreign keys and audited journal conservation. Repository validation additionally enforces nonnegative final funded balances, available funding, protected consent, complete action semantics and exact aggregate arithmetic. The database is not a substitute for the public financial repository; raw SQL clients are not authorized financial writers.

Existing command hashes retain their canonical encoding. Planning uses a separate new tag. A populated v2 fixture made from the committed v2 export migrates with its original journal and receipt, and replay of its original command still returns the same receipt.

## Executed local gate

Full Gradle tests/lint/debug build passed on Windows/JDK 17 (`BUILD SUCCESSFUL in 7m 2s`):

| Suite | Actual result |
| --- | --- |
| Pure finance-engine JVM | 72 passed (original 52 plus 20 budget/goal/recurrence cases) |
| Original Room integration | 43 passed |
| New planning Room/NATIVE SQLite | 24 passed |
| Original ViewModel regression | 19 passed |
| Total | **158 passed**, zero failures/errors/skips |
| Lint | Zero errors; 35 existing warnings |
| Debug APK assembly | Passed |
| Schema checks | V1/V2 unchanged; compiler-generated V3 committed |

The new persisted cases cover conservation, target-versus-funding, insufficient funding rollback, partial/split spending, fees, same/closed-period refunds, atomic corrections, injected failure, both rollover policies, protected persistence, exact/stale diversion, reserve moves, receipt retries, concurrent funding, reopen/immutability, cross-book/currency rejection, disjoint buffer, deficits and populated-v2 migration. Initial fixture parsing of optional index metadata was corrected; no financial assertion was removed or suppressed.

No physical-device, emulator or consumer UI testing is claimed. Subsequent recurrence, investment, forecast, backup/security, performance and final UI gates remain outstanding. See `PHASE_1_EXECUTION.md` and draft PR #2 for current source/CI links.
