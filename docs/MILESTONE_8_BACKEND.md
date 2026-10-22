# Current planning validity and liquid projection scope

Two financial edge cases were independently reproduced before changing implementation. Nine targeted native Room regression tests initially failed at their expected financial assertions; the same nine tests then passed after the fixes. The initial failure log and XML copies are retained under `.tools/phase1-derived-liquidity-reproduction.log` and `.tools/derived-liquidity-reproduction-reports/` locally.

## Derived totals participate in command atomicity

Individually representable amounts could previously combine into an unrepresentable current planning result after the command committed. Examples include aggregated required goal contributions, a safety deficit after an actual expense, carried budget allowance plus a new limit, refunded category spending plus a maximum budget limit, or several uncovered obligations.

The owning command now validates its complete current-day planning snapshot after actual/virtual/occurrence changes and before advancing the book revision. Checked arithmetic failures roll back the entire transaction, including the actual transaction, metadata, command receipt, audit and revision. Inputs are never clipped, rounded, converted to floating point or silently deleted. This guard concerns the current configured planning view; optional longer-horizon forecasts still validate their own requested aggregate arithmetic when computed.

Five regression cases verify each demonstrated overflow combination and exact pre-command snapshot/audit retention.

## Non-liquid schedule activity stays separate

Schedules can describe activity directly in an owned non-liquid investment account. Its income must not inflate projected bank/cash money, and its expense must not deduct liquid cash or take funded coverage away from a bank/cash bill.

Each occurrence now explicitly carries `affectsLiquidCash`, derived from the actual template's owned account kind. Liquid projections and liquid obligation coverage use that scope. Non-liquid activity remains in schedule history and can still be manually confirmed as real non-liquid account activity. No expected movement between investment and liquid accounts is fabricated. Projection assumptions disclose the exclusion; coverage values for a non-liquid occurrence do not claim that an investment account has sufficient backing.

Four regression cases verify non-liquid income, non-liquid expenses, mixed-account coverage and pending actual confirmation without invented liquid movement.

## Verification and remaining scope

The focused nine-case native Room run passes. The full Windows/JDK 17 gate cleaned both test outputs and passed in 2m 55s: **277 tests** (78 finance-engine JVM, 149 Room/NATIVE SQLite, 7 codec JVM and 43 ViewModel), zero failures/errors/skips. All prior 268 CI test names are retained. Lint reports zero errors and 35 existing warnings; debug assembly passes. Historical/generated schemas remain unchanged. Local log: `.tools/phase1-derived-liquidity-gate.log`.

No database schema or migration changes are needed for these fixes. No physical-device/emulator/Compose execution or Phase 1 completion is claimed. Investment ownership return/diversion, schedule unlinking, reporting/performance, backup/security/privacy/reminders and final approved consumer UI remain outstanding.
