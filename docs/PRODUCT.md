# Phase 1 functional specification

Status: Phase 1 destination; accounts and the ledger are implemented in milestone 2. Other rows remain planned unless explicitly stated in MILESTONE_2.md.

| Capability | Required behavior / acceptance |
| --- | --- |
| Accounts | Create manual bank, cash and investment accounts; opening balance is a ledger event; archive without deleting history; show cleared versus pending figures |
| Transactions | Income, expense, transfer and category splits; splits total the parent exactly; search/filter; reverse-and-replace corrections with reasons |
| Recurrence | Daily, weekly and anchored monthly rules; preview occurrences; missed-run catch-up; unique rule/date key; no automatic assumption that a bill was paid |
| Categories / budgets | Monthly category limits, explicit rollover choice, refund handling, overspend warnings and reports based on posted expense legs |
| Buckets | Virtual assignment of existing liquid funds; conservation across moves; visible unassigned money and deficits |
| Savings / emergency | Protected reservations distinct from bank location; withdrawal/diversion preview and explicit confirmation |
| Goals | Target, deadline, priority and monthly plan; reserved progress separate from projected progress; collision list under funding shortfall |
| Investments | Plan and record contributions; transfers to tracked investment accounts do not become expenses; no trading, market return prediction or advice |
| Safe-to-Spend | Show liquid resources, reservations, uncovered commitments, available result and any deficit; no future income credited to current availability |
| Forecast / purchase | Dated cash-flow projection with assumptions, minimum liquidity, shortfall dates and goal impacts; simulation never posts data |
| Dashboard / reports | Real ledger-derived totals; no fabricated seed money; exclude transfers and opening equity from income/expense |
| Reminders / review | Optional notifications, due items and weekly review; denied notification permission never blocks bookkeeping; WorkManager added only at this milestone |
| Backup / restore | Explicit encrypted export, password recovery limitations and full validation before atomic replacement; test corruption, wrong password and interrupted restore |

## User journeys

1. Choose currency and timezone, create an account and explicit opening balance; receive an empty, truthful dashboard.
2. Record a split expense; confirm account decrease and category totals match exactly.
3. Transfer between owned accounts; cash location changes, net worth and income/expense do not.
4. Reserve funds for two goals; Safe-to-Spend decreases while account balances remain unchanged.
5. Preview diverting savings for a purchase; see affected goals and deadline feasibility before confirming.
6. Reconcile against a statement; difference remains visible until an explicit adjustment or correction resolves it.
7. Export and restore an encrypted backup onto a clean install with full balance and audit verification.

## Milestones and exit gates

1. **Foundation (this assignment):** architecture, minimal startup UI, Hilt/Room/DataStore wiring, deterministic engine primitives, invariant tests and CI. No full financial UI.
2. **Accounts and ledger:** account creation and opening equity, balanced postings, repository transactions, duplicate-command protection, splits, transfers, reversals, reconciliation and repository integration tests. Persist v2 schema and test v1 migration before money-entry UI ships.
3. **Budgets and reserves:** monthly budgets/rollover, allocation journal, goal funding, protected diversions, Safe-to-Spend explanations; prove snapshot consistency and concurrent-write rollback.
4. **Plans and forecast:** recurrence preview/confirmation, collision analysis, purchase simulations and date-based forecast with assumption disclosure. Table-driven cross-platform fixtures.
5. **Review and reports:** accessible dashboard/reports, optional reminders, WorkManager catch-up and timezone tests; real-device QA without requiring an emulator.
6. **Backup and hardening:** authenticated encrypted backup, restore staging/rollback, app lock decision, migration suite, process-death/accessibility/performance/device checks and signed release process.

## Open product decisions (proposals, not silent assumptions)

- Confirmed for milestone 2: INR default, one currency per book, no FX. Currency metadata is immutable; unsupported currencies/exponents are rejected.
- Confirmed provisionally: minimum Android 8.0/API 26; revisit coverage before distribution.
- Credit cards, loans and debt repayment require liability semantics; defer rather than present negative cash as a complete debt model. Negative asset balances are still represented honestly and flagged.
- Confirmed default category rollover: off; goal/emergency reserves will persist. These features are outside milestone 2.
- Monthly goal plan due on a selected anchor day, conservatively counting the current month; arrears are a visible separate commitment, never silently added twice.
- Investments initially use manual cost/contribution tracking, with liquid status explicitly false; valuation and returns are outside Phase 1.
- No analytics or cloud sync. App lock and database encryption need a threat-model and recovery decision before production release.
- Backup format/KDF work factor and account/package identity require review before release. No backup button until protected round-trip tests pass.
