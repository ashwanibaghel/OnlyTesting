# Milestone 2: accounts and immutable ledger

Base: verified main `63d60517e930458c95a035de810e56d1988bb9e8`. Branch: `milestone-2-accounts-ledger`. Production application ID remains provisional.

## Accounting contract

Default book `personal` belongs to the local device owner and uses INR, exponent 2. Additional book IDs may be created explicitly through the repository. Milestone 2 supports INR only; reject all other codes/exponents. Every account belongs to exactly one book. BANK and CASH are liquid; INVESTMENT is non-liquid. Negative balances remain visible as overdrafts; this is not a credit-card or loan model.

Actual entry/clearing dates cannot be in the future relative to the injected device-zone clock; future expected activity belongs to a later forecasting workflow. Archived balances remain in totals. Command keys are globally unique; account and book IDs use 1..100 ASCII letters, digits, underscores or hyphens. Categories are exact, trimmed case-sensitive labels. The UI retains a submission key while a form is unchanged and requires editing or an explicit new entry after saving.

Asset ledger accounts link one-to-one to owned accounts. Income/expense ledger accounts are category controls, identified by book, role and exact trimmed category name. Equity controls distinguish opening balances and reconciliation adjustments. Later budgets can reference these stable category-control IDs. No cached monetary balance exists.

Income: asset +amount, income -amount. Expense/splits: asset -total, expense controls +split amounts. Transfer: source -(amount+fee), destination +amount, expense fee control +fee when nonzero. Opening and reconciliation adjustments use equity controls. Zero opening creates an account without a meaningless zero journal. Existing v1 metadata migrates with zero history, not an invented opening amount.

Reports sum signed income and expense control legs, including reversals. Thus opening equity and transfer principal never count as income/expense, fees do count, and reversals cancel the original classification on their effective date. A date-filtered report reflects activity during that interval, not deletion of past activity.

## Atomicity and database enforcement

Within one Room transaction: look up the command receipt, validate fingerprint, compare book revision, create command/audit metadata, insert journal headers and legs, validate with the exact Kotlin engine, seal journals, validate derived account/book totals, and advance revision. All failures roll back the entire command. Identical command/key retries return the original receipt even after later revisions. A different payload with an existing key is rejected. Concurrent writers serialize through SQLite transactions; expected revision rejects stale different commands. No process-local mutex is relied upon for integrity.

The open callback enables foreign keys through Android's connection-pool configuration and asserts the pragma, in addition to Room's generated opening-connection pragma. Exact BigInteger-to-Long conversion uses explicit range comparisons available on API 26; it does not depend on BigInteger.longValueExact (introduced in API 31).

Headers, postings and seals are append-only. A seal is a separate immutable row; only sealed journals contribute to balances/history/reports. SQLite triggers reject post-seal leg changes, header/seal mutation/deletion/replacement, zero/noninteger/unnegatable amounts, mixed books, invalid account types, archived account usage, missing references, and sealing fewer than two or unbalanced legs. Seal validation also enforces income/expense sign and control roles, transfer asset relationships and expense-only fees, asset/equity opening and adjustment pairs, and exact reversal leg mirroring. The supported gross positive amount per journal is <= Long.MAX_VALUE so SQLite integer SUM cannot silently promote financial values to REAL. Kotlin uses exact intermediate integers and enforces representable final account, cleared and book totals. SQLite integer overflow aborts rather than rounding.

SQLite cannot require an arbitrary multi-row draft to be sealed at transaction commit without a commit hook. Draft rows are never exposed as posted truth. The internal DAO is not a public financial write API; the repository is the sole application posting boundary and always seals all journals in the same transaction. File tampering/root access is not a supported security boundary. Integration tests exercise trigger rejection and rollback directly.

## Corrections, clearing and reconciliation

Reversal references one original sealed entry, mirrors all its legs and preserves category classification. A unique index permits only one reversal per original. Reversal of a reversal is rejected; submit a new explicit transaction instead. Reverse-and-replace executes both journals atomically with a mandatory reason. Reversal/correction date cannot predate the original or its clearing date. Archived accounts reject new postings, including corrections; explicitly unarchive through the audited command first.

All accepted entries affect posted balances, including PENDING entries. Cleared balances include initially CLEARED entries and append-only clearing events whose date is <= the statement date. Clearing cannot predate the transaction. Reversed pending pairs remain pending and net to zero; neither side can be separately cleared afterward. No clearing mutation changes a financial posting.

Reconciliation takes account, statement date, exact statement amount and POSTED/CLEARED basis; returns derived balance, pending delta and difference. An adjustment command includes expected book revision and statement details and recomputes the difference transactionally, then adds an equity journal plus immutable reconciliation evidence. A zero difference records the reconciliation without a zero journal. A CLEARED reconciliation adjustment does not clear unrelated pending transactions.

## Migration and verification

Explicit v1 -> v2 rebuild preserves every valid account ID, name, kind, currency and archive state, attaches metadata to the default book and creates matching asset ledger accounts. Unsupported legacy currency/kind/blank metadata aborts migration rather than deleting or guessing. Room validates the migrated schema on reopening. V1 JSON remains unchanged; V2 JSON comes from Room KSP. Fresh creation and migration install identical triggers.

Tests run the actual generated Room implementation using Robolectric NATIVE SQLite, including file-backed reopen and migration from exported v1 schema. These are host integration tests, not DAO mocks and not physical-device instrumentation. On-device runtime/Compose interaction remain separately unverified unless explicitly executed. UI is a temporary manual exerciser; goals, budgets, cloud and advanced reports remain out of scope.
