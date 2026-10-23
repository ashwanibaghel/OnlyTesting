# Actual funding backing and retained ownership claims

Two native Room regressions first reproduced overstated goal progress: a reservation of 800 after an actual bank statement reduced available cash to 500, and a recorded investment contribution of 300 after 200 had returned to cash. Historical reservations/contributions remained unchanged, but progress incorrectly treated their entire recorded amount as current backing.

## Backing without silent write-offs

The pure `assessBacking` engine retains recorded claims and calculates actual shared backing, unassigned backing and the exact backing shortfall using checked integer money. It never edits account or allocation history. A negative actual balance backs zero claims; the original negative account value is still retained, and existing Safe-to-Spend/reservation deficit semantics remain unchanged.

For total claims `T`, physically available nonnegative backing `A`, and a claim `c`, the shared deficit is `D = T - min(T,A)`. That claim's sharp backing bounds are:

- minimum: `max(0, c - D)`;
- maximum: `min(c, A)`.

For one 800 claim backed by 500, both bounds are 500 and the recorded claim remains 800. For two 400 claims sharing 500, each has a 100–400 range, while the actual shared backed total is exactly 500. Per-claim upper bounds overlap and must never be added to invent 800 of backing. No priority-based loss allocation is silently imposed.

An exhaustive small-allocation test independently enumerates all feasible allocations and verifies both sharp endpoints and conservation. Additional tests cover full/partial backing, empty/unassigned capacity, negative balances, currency/input validation and checked 64-bit extremes.

## Planning integration

Reserve and budget read models expose both recorded funding and backing bounds. Goal funding separates recorded reservations/contributions, minimum actual backed progress, maximum possible backed progress and posted versus cleared investment backing. Investment backing is assessed per actual owned account across all linked claims, including non-goal investment reserves.

Goal contribution/deadline projections use minimum backed progress and explicitly require funding review when claims are unsupported. Under shared uncertainty, conservative per-goal monthly requirements are not a committed aggregate funding plan; typed flags and assumptions require a reviewed allocation of the shortfall. The eventual frontend must display that uncertainty and the actual shared total, rather than summing overlapping bounds or presenting historical claims as fully backed cash. Remaining requirements already reflect unsupported funding and must not have the same deficit added a second time.

Protected-diversion previews recalculate backing and consequences for every affected goal. Releasing an unsupported claim can improve another goal's guaranteed backing without creating money or changing its recorded claim. A deficit-resolution move therefore does not blindly subtract its nominal amount from a possibly smaller backed-progress value. Exact reviewed consent, book revision/date, atomicity and Safe-to-Spend rules remain in force.

General command review includes the new bounds, per-account investment backing and assumptions in its before/after outcome comparison. All figures derive from actual persisted ledgers; historical rows and schemas are preserved.

## Verification and remaining scope

The initial two-case native run failed before implementation. Seven new engine tests and all 39 planning Room tests pass in the focused gate. Eight new Room cases cover cash and investment shortfalls, shared backing, reviewed deficit resolution, indirect protected-reserve consequences, pending/cleared distinction and recovery only after real money arrives.

The full Windows/JDK 17 gate cleaned both test outputs and passed in 4m 15s: **325 tests** (85 finance-engine JVM, 184 Room/NATIVE SQLite, 7 codec JVM and 49 pure ViewModel), zero failures/errors/skips. All prior 310 CI test names are retained. Lint reports zero errors and 35 existing warnings; debug assembly passes. Historical/generated schemas remain unchanged. Local log: `.tools/phase1-funding-backing-gate.log`.

This read-model fix does not complete explicit investment withdrawal/cost-ownership attribution. Cumulative recorded contribution claims remain visible and can require review; forthcoming ownership APIs must record intended returns/diversions without fabricating transfers. Schedule unlinking, reports/performance, backup/security/privacy/reminders and final approved consumer UI also remain outstanding. No physical-device/emulator/Compose execution or Phase 1 completion is claimed.
