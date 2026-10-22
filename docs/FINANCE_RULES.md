# Platform-neutral financial contract v1

These are deterministic bookkeeping and planning rules, not financial advice. Implemented primitives are named below; the remaining workflow rules are milestone specifications.

## Money and exact arithmetic

`Money(minor, currency)` uses signed 64-bit integer minor units. Currency includes uppercase ISO-style code and explicit exponent (0..3). A book selects one validated supported currency; the engine does not infer exponents or perform FX. Every operation requires identical currency metadata. Checked addition/subtraction/negation and exact conversion reject overflow, never wrap or silently clamp. Decimal parsing uses BigDecimal with UNNECESSARY rounding; excess nonzero precision is rejected. No binary floating point, locale guessing, NaN or infinity. Splits must sum exactly; future proportional splits use integer quotient plus largest remainder, ties by stable ID, with explicit signed refund policy. A result outside Long range is a visible calculation error.

## Actual ledger (implemented validation and account delta)

Each journal has >= 2 nonzero signed postings in one currency and sums to zero. Positive asset leg increases actual balance. Income uses negative income-control and positive asset; expense uses negative asset and positive expense-control. Opening balance uses asset/equity; reconciliation adjustments use explicit adjustment equity. Transfer uses equal and opposite asset legs and is excluded from income/expense reports. Transfer fees are separate expense legs, not hidden in transfer amounts. Engine account aggregation uses exact intermediate integers and validates final bounds. Repository validates identifiers, account types and complete command semantics before persistence; a mathematically balanced journal alone is not authorization to post.

Example in INR minor units: salary asset +100000 / income -100000; expense asset -20000 / expense +20000; bank-to-cash bank -10000 / cash +10000. Total assets = 80000, income = 100000, expenditure = 20000. Control accounts are not user cash wallets.

Corrections append a complete reversal plus replacement in one transaction, preserving the original; effective and recorded dates are separate. Reports exclude reversed originals and their reversals consistently or include both signed legs, never mix policies. Transfers to tracked investments are asset transfers. External investment contributions use a tracked investment asset/control path and contribution report, not household consumption expenses. A contribution linked to both allocation and ledger appears once in progress/reporting.

## Virtual allocation and monthly rules

Virtual buckets reserve ownership of existing liquid money. They never modify an actual account balance. Virtual journal conservation includes an unassigned source/destination. Funding cannot exceed unassigned funds; negative actual cash remains a visible allocation deficit. Protected balances cannot be silently reduced. Reservations do not assert that money sits in a particular bank account.

Monthly category target and funded reserve differ. Income adds unassigned money only after it posts. Funding plans cannot use expected future income. Monthly rollover creates an auditable allocation event: off releases category leftovers to unassigned; on retains leftovers. Goal/emergency reserves always persist. Spending atomically posts actual expense and consumes matching funded category reserves. Example: L=100000, R=20000; spending 5000 from that reserve yields L=95000, R=15000, preserving free funds. Unfunded spending reduces free funds and exposes overspend. Refunds reverse associated reserve consumption up to the refunded amount, with explicit allocation handling if the original period is closed. Never deduct both the original funded reservation and the same bill again as uncovered commitment.

## Safe-to-Spend (implemented primitive)

Inputs are a consistent same-currency snapshot:

- `L`: sum of posted liquid asset balances; exclude investment assets, credit limits and future income. Negative balances reduce L.
- `R`: nonnegative existing funded reservations across **disjoint** category, goal, emergency and investment buckets.
- `U`: nonnegative unpaid near-term mandatory obligations **not already covered by R** (explicit horizon supplied by the calling use case).
- `B`: nonnegative additional user safety buffer, also disjoint from R and U.

`raw = L - R - U - B`; `available = max(0, raw)`; `deficit = max(0, -raw)`.

UI must disclose horizon and components, show deficits even with zero available, and never label a negative position healthy. Posting/allocation query layer must enforce disjointness by stable obligation/bucket IDs. The numerical primitive cannot infer duplicate inputs. Example: L=100000, R=30000, U=10000, B=5000 -> available 55000, deficit 0.

## Goals, collisions and diversion

Goal progress = actually funded linked reserve (plus explicitly eligible completed contributions, never the same money twice); planned contributions are projection only. Remaining target = max(0, target - funded). Given N explicit remaining contribution periods, minimum monthly amount = ceil(remaining/N), integer units. N must be > 0; expired/unfunded goals are infeasible, not division by zero. Overfunding requires 0. Priority ordering is explicit; stable ID breaks ties. If total planned monthly funding exceeds free monthly capacity, return shortage and all affected goals rather than fabricate a success path.

Implemented `previewDiversion` validates positive amount <= funded balance and returns remaining reserve plus a protected-funds warning flag. It does not mutate balances. The future committing use case requires confirmation containing affected goal IDs, preview snapshot revision, lost funded progress, revised contribution requirement and deadline impact. Reject stale confirmation and recompute. Moving between reserves changes no L; releasing a reserve increases free funds, spending released money decreases L.

## Cash-flow forecast and purchase simulation (implemented dated cash projection)

Forecast receives explicit as-of date, inclusive end date, opening liquid balance, dated signed cash events and **nonempty assumptions**. No hidden clock, guessed income or investment returns. Reject events outside horizon and duplicate IDs. On each date process outflows before inflows to surface conservative intraday liquidity risk; stable ID breaks same-sign ties. Return closing and minimum balance, first negative date, and supplied assumptions. It is a liquidity forecast, not a second computation of Safe-to-Spend. Future UI must show separate reserve-aware availability series; protected funds cannot become spendable merely because the forecast is positive. Future income can appear in forecasts but not current availability. No events -> closing/minimum equal opening. Negative opening -> first shortfall is as-of date.

Purchase simulation adds a hypothetical outflow to an immutable snapshot, compares minima and goal-funding impact and labels excluded taxes/fees/returns. It never writes a real transaction. Recurring future income is assumed, not guaranteed; unconfirmed expected entries remain separate from actuals.

## Recurrence and dates

Rules use local calendar dates in the book timezone, with explicit created instants for audit. Monthly anchor is preserved: Jan 31 -> Feb last day -> Mar 31, not a permanent shift to the 28th. Leap-day yearly behavior proposed: last day of February. Expand only within a bounded horizon, respecting start/end and exceptions. Clock and timezone enter as explicit parameters. Unique (rule, scheduled date) occurrence prevents duplicate posting on retry, DST changes or worker catch-up. Default occurrence is pending confirmation, not an automatic actual cash mutation. Tests and implementation are scheduled for milestone 4.

## Required invariants / edge cases

- Journal sum zero, splits conserved, transfer net zero, no income/expense from opening equity.
- Virtual movement preserves actual balances and total reservations; consumption changes both correctly.
- No future plans in funded progress; no duplicate contribution or obligation deduction.
- Nonnegative bucket reservations, explicit deficits, exact cents/paise, zero-decimal and three-decimal currencies.
- Reject mixed currencies, blank IDs, same-account transfer, negative reservation inputs, malformed decimals, unsupported precision and integer overflow.
- Test Long bounds/intermediate cancellation, refunds/reversals, duplicate commands, stale revision, rollback, pending versus cleared and archived accounts.
- Test month/year boundaries, leap years, DST, expired goals, zero capacity, same-day cash troughs and restore interruption.
- Engine is deterministic for equal inputs. Property-style seeded loops supplement concrete numerical examples; no random nondeterminism.
