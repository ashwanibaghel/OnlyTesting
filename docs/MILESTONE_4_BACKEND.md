# Scheduled plans, contributions and cash projection — backend core gate

This is another verified backend increment, not Phase 1 completion or consumer UI approval. High-fidelity visual review is still pending. Existing developer screens are not presented for acceptance.

## Persistent planned versus actual money

- Recurring income and expense templates support anchored daily, weekly, monthly and yearly rules, intervals, optional end dates and exception dates. Stops preserve past due items and all actual history. A schedule never automatically posts money.
- Awaiting confirmation/reopened occurrences are plans. A user's manual confirmation posts actual income/expense with a separate pending/cleared state. Future actual dates are rejected; early actual payments are allowed and settle the future occurrence without a projected duplicate.
- A `(rule, scheduled date)` identity plus command receipts/revisions prevents duplicate confirmation. Skip and reopen decisions are audited. An already recorded real payment can be explicitly linked without posting or consuming category funding a second time; the same current payment cannot settle two occurrences.
- Confirmation, category reserve consumption, occurrence decision and audit share one Room transaction and revision. Late confirmations use the current live category period; early confirmations can use a funded future period. Reversing an early payment can restore its still-open future reserve.
- Immutable resolution events follow reverse-and-replace to the new journal. Returned payments reopen the known bill amount; reconfirmation can post once. Cancelling a returned bill is an explicit skip decision. Original confirmation/skip history is retained.

## Availability and projections

- Mandatory unconfirmed expense obligations within the configured horizon use stable category/period funding identities. Coverage is consumed only once per funded bucket across bills; partial coverage becomes uncovered obligation. Confirmed pending payments already affect actual posted money and are not deducted again.
- Safe-to-Spend uses actual liquid assets, funded reservations, uncovered obligations and a separate safety buffer from one transaction snapshot. Future salary cannot fund current availability. Diverting/releasing a bucket recomputes obligation coverage as well as goal consequences.
- Forecasts use scheduled events and explicit assumptions. Overdue expenses are assumed at projection start, overdue income is excluded, and outflows precede inflows on the same day. Confirmed/skipped occurrences are excluded. Daily timeline points retain intraday troughs, not just healthy-looking closing balances.
- Purchase simulation writes no data. It can include a hypothetical reviewed protected-reserve diversion and show changed contribution/deadline projections, cash shortfalls and availability measured from current actual funds. It does not automatically release a goal or credit future income.

## Manual investment contributions and review

- A contribution transfers liquid account money to an owned investment account and consumes the linked funded investment reserve atomically. Transfer principal is excluded from household income/expense; fees retain expense classification and matching fee-reserve consumption.
- Progress separates liquid reserved money, posted contributions, cleared contributions and their eligible recorded total. Contributions are never added to current reservations again. Original receipts prevent duplicate contribution credit.
- Reversal returns the original consumed ownership; correction retains the goal link and records only the actual corrected contribution. A correction may include an actual unfunded remainder without creating a negative reserve. Checked progress overflow rolls back attempted new funding.
- These values are recorded contribution/cost data, not current market valuations or return projections. Explicit partial-withdrawal attribution and reviewed return-to-reserve/diversion workflows remain a follow-up backend gate; do not present recorded contributions as a complete remaining investment wealth model.
- Weekly review decisions persist once per week with a note and reviewed revision, without changing money.

## Schema and enforcement

V4 adds six tables: `scheduled_rules`, `schedule_exceptions`, `schedule_occurrences`, `occurrence_resolutions`, `investment_contributions`, `weekly_reviews`. Explicit `3→4` migration composes with `1→2→3`. V1/V2/V3 exported files are byte-identical to the previous verified source. V4 is compiler-generated: identity `356f7c51c22f7ef43c160ccc98e32069`, 23 tables and 34 declared secondary indexes overall.

New SQLite triggers protect immutable decisions/contribution/review records, occurrence identity, book ownership, sealed journal links and contribution asset-leg matching. V4 adds explicit metadata validation triggers for budgets/goals/profiles/buckets without changing the historical V3 trigger definitions. Repository validation additionally enforces schedule calendar membership, allowed state transitions, current-payment uniqueness, confirmation amounts, reserve backing rules and checked contribution totals.

The populated V3 fixture preserves every existing actual/virtual row across migration, then defines a real schedule afterward. Earlier V1/V2 migration and original accounting/idempotency tests are retained. No destructive fallback or schema-history rewrite.

## Executed full local gate

Both test outputs were cleaned; Windows/JDK 17 full run completed with `BUILD SUCCESSFUL in 3m 57s`:

| Suite | Actual result |
| --- | --- |
| Finance engine JVM | 78 passed |
| Original Room/NATIVE SQLite | 43 passed |
| Planning Room/NATIVE SQLite | 27 passed |
| New schedules/investment Room/NATIVE SQLite | 40 passed |
| Template codec JVM | 7 passed |
| Original ViewModel regressions | 19 passed |
| Total | **214 passed**, zero failures/errors/skips |
| Android lint | Zero errors, 35 existing warnings |
| Debug assembly | Passed |

The prior 161 CI test names were compared against this run and all retained. Two new returned/corrected-payment cases initially reproduced stale occurrence state and now pass with append-only resolutions. A native Windows fixture failed consistently at a 252-character database path while creating a journal sidecar; shortening only the synthetic fixture filename fixed the same financial test. Native SQLite, on-disk storage and all financial assertions remain enabled; no skip/retry suppression was introduced.

There is no physical-device/emulator/Compose execution claim. Remaining gates include schedule replacement/unlink management, explicit investment return ownership, complete reporting/paged-history/performance, atomic onboarding/session controllers, backup/security/privacy/reminders and the approved final frontend.
