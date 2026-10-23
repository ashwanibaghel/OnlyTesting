# Phase 1 integrated delivery

The complete product is the delivery gate. No acceptance testing is requested for partial developer screens. Automated tests, migration verification, lint and APK CI continue throughout development.

## Foundation and review strategy

`phase-1-integrated` starts at verified PR #1 head `5a99ef839b1ca90711f9a8a161026c517fdb4fc4` (114 passing tests). The accounting foundation and retry/review fixes are inherited without merging PR #1 or modifying main. Subsequent commits remain reviewable on this branch. Existing v1/v2 schema files are historical contracts; new persisted functionality receives explicit migrations.

## Implementation gates

1. Budget periods and rollover, immutable virtual allocations, protected reserves, goal intelligence and atomic spend/refund integration.
2. Persistent recurring schedules, due confirmations, investment contributions, current Safe-to-Spend and dated forecasts/purchase impact.
3. Native Compose onboarding, account/transaction workflows, responsive navigation, budgets/goals, dashboard, insights/reports and weekly review.
4. Optional private notifications, privacy controls, authenticated encrypted export and fully validated restoration with rollback.
5. Domain/Room/ViewModel/UI regression suites, historical migrations, large-history/performance evidence, lint, CI/APK and final integrated verification.

These are implementation gates, not claims that the features are already complete. Status and measured evidence are updated as each gate is verified. The application is presented for manual acceptance only after all gates pass.

## Verified backend increments

- `ac38972`: pure budget/goal/recurrence engines; 20 new JVM tests; [successful CI](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39859499448).
- Budgets/reserve/goal persistence gate: 24 additional Room tests, all original tests retained, 158 tests total; full local lint/debug gate passed. See [MILESTONE_3_BACKEND.md](MILESTONE_3_BACKEND.md) for financial policies and migration/enforcement details.
- [Draft PR #2](https://github.com/ashwanibaghel/OnlyTesting/pull/2) is stacked on PR #1. Neither is merged. The draft does not claim Phase 1 completion.
- `259e23a`: independent clean local run and [CI run 39863038386](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39863038386) both verify 161 tests, zero failures/errors/skips, lint zero errors/35 warnings and debug/schema gates. CI report ZIP was checksum-verified and XML parsed; no Android-host Windows file-lock block occurred in that run.
- `22efec9`: scheduled-plan/contribution/projection backend core: 214 passing tests, full local gate and [CI run 39870057528](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39870057528). Downloaded CI report SHA-256 `2a1c6c271b9dfaa9512215a4fcaac2d83d62533f7dbf7864acdb8e536caedc48` and parsed JUnit XML independently confirm 214 tests with zero failures/errors/skips. See [MILESTONE_4_BACKEND.md](MILESTONE_4_BACKEND.md). Remaining backend gates and final consumer UI are explicitly outstanding.
- Atomic setup/current-day session APIs: 22 new native Room cases; 236 tests pass with zero failures/errors/skips, full lint/debug gate and unchanged schemas. All prior 214 test names retained. See [MILESTONE_5_BACKEND.md](MILESTONE_5_BACKEND.md).
- Reusable financial workflow ViewModel: 24 new behavior cases; 260 tests pass, full lint/debug gate and unchanged schemas. Exact reviewed values, form-specific retries, lost acknowledgement and duplicate-submission protections are ready for eventual frontend integration. See [MILESTONE_6_BACKEND.md](MILESTONE_6_BACKEND.md).
- Atomic recurring-plan replacement: 8 new native Room cases, 268 tests pass, full lint/debug gate and unchanged schemas. Original rules, reviewed decisions and actual history are retained. See [MILESTONE_7_BACKEND.md](MILESTONE_7_BACKEND.md).
- Current planning/forecast consistency fixes: 9 independently reproduced native Room regressions now pass; full gate has 277 passing tests, unchanged schemas and retained prior tests. Derived current planning overflow rolls back the whole command; non-liquid schedules do not fabricate liquid cash or consume its coverage. See [MILESTONE_8_BACKEND.md](MILESTONE_8_BACKEND.md).
- General command effect review: 24 new native Room cases, 301 tests pass, full lint/debug gate and unchanged schemas. Preview executes real ledger logic inside a rolled-back transaction; reviewed application rechecks exact state/outcomes and preserves idempotent receipts. See [MILESTONE_9_BACKEND.md](MILESTONE_9_BACKEND.md).
- Reviewed workflow integration: 6 further JVM behavior cases plus 3 actual ViewModel/Room integration cases; 310 tests pass, full lint/debug gate and unchanged schemas. Normal/diversion forms use real prospective effects and exact reviewed application; observer recovery and retry/form identity protections remain intact. See [MILESTONE_10_BACKEND.md](MILESTONE_10_BACKEND.md).

## Design handoff

The user verified design branch commit `8ab65cbc405f2d9bec30b0b2cc0ed4c5e6a53630` and approved its eight specifications as **engineering reference specifications only**, not final visual designs. Design-only commit `343d4bf` imported exactly those eight blobs without merging unrelated code or changing existing uncommitted financial work. High-fidelity visual screens must still be reviewed before consumer UI implementation resumes. Backend, security, tests, clean ViewModels/state models and reusable APIs continue independently. Do not invent a final UI, describe temporary developer screens as the finished product, fabricate final visual approval, or request acceptance of partial screens.

## Financial policies carried forward

- INR, integer minor units, checked arithmetic, explicit dates and one book; no FX, liability accounting, market projections or trading.
- Posted/pending account money is separate from planned schedules, virtual funded ownership and projected income.
- Budget targets are not funded money. Category rollover defaults off. Goal/savings/emergency ownership persists.
- Safe-to-Spend uses a consistent repository snapshot and disjoint funded reservations, uncovered obligations and safety buffer; future income never funds current spending.
- New dependent ledger/allocation/occurrence changes share one transaction, command identity and book revision. History is preserved; no destructive migration or implicit restore/erase is permitted.
- Device/emulator execution is reported separately from host Robolectric tests. No physical-device claim without execution.
