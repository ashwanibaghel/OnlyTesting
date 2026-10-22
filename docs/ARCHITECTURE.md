# Architecture and domain

## Dependency direction

`app.ui -> ViewModel -> use case / repository -> Room + finance-engine`. The `finance-engine` JVM module depends only on Kotlin/JDK. It receives immutable inputs and explicit dates; it does not read Room, Android, system time, settings or network. Future iOS follows FINANCE_RULES.md and shared fixture examples rather than Android code.

Two modules keep the foundation maintainable. App packages include `ui/ledger`, `domain/ledger`, `data/ledger`, `data/local`, `data/settings`, and `di`. `LedgerRepository` is the public financial boundary; internal `LedgerDao` is used only by its Room implementation and database integration tests. Compose collects ViewModel StateFlow using lifecycle-aware APIs. Hilt provides the repository, Room and one preferences DataStore. Financial state belongs exclusively in Room; DataStore stores UI preferences. No WorkManager dependency until scheduled work exists.

## Entities and relationships (planned complete model)

| Entity | Key fields / relationships |
| --- | --- |
| Book | currency code, exponent, user timezone; owns all financial entities |
| Account | ID, book, name, type, liquid/archived flags; balance derived from postings |
| JournalEntry | ID, kind, effective date, created instant, command id, reversal-of, reason; owns >= 2 postings |
| Posting | entry, ledger account, signed minor units; account may be asset, liability, income, expense or equity control |
| Category | book, name, archived; expense/income control mapping |
| Split | posting/category association; expense split legs sum to source asset reduction |
| AllocationBucket | category/goal/emergency/investment purpose, protected flag; virtual, not an asset |
| AllocationEntry | balanced virtual source/destination legs and optional consuming journal ID; audit-linked |
| MonthlyBudget | unique category/month, limit, rollover policy; target distinct from funded amount |
| Goal | target, deadline, priority, planned monthly contribution; linked reserve bucket |
| Contribution | journal/allocation links with unique linkage; classify reserved, invested or planned |
| RecurringRule | template, anchor date, frequency, end date, timezone and exception dates |
| Occurrence | unique rule/scheduled-local-date, status, optional journal entry; retries idempotent |
| Reconciliation | account, statement date/balance, computed balance, difference, resolution entry |
| AuditEvent | entity, operation, command ID, timestamp and reason; same DB transaction as mutation |

## Implemented foundation versus deferred schema

Room v1 was account metadata only. Room v2 implements books, owned accounts, asset/category/equity ledger accounts, command receipts, journals, postings, journal seals, clearing events, audit events and reconciliation evidence. There is no cached balance. See [Milestone 2](MILESTONE_2.md) for exact enforcement boundaries and sign conventions.

The repository validates complete commands with exact engine primitives, enforces book/currency/account constraints, inserts journals/postings/seals/audit atomically in `withTransaction`, and exposes consistent snapshots. Unique command IDs, foreign keys and optimistic revisions prevent duplicate/stale writes. Reversal plus replacement is atomic. Virtual allocations, goals, recurring rules and budgets listed above are still planned and have no persisted tables in this milestone. SQLite triggers protect posted immutability and journal conservation; this is not a tamper-proof system against root/file access.

## Database migration strategy

`exportSchema = true`; KSP exports schema JSON under `app/schemas`. The generated v1 and v2 schemas are committed. Never hand-author a Room identity hash. CI fails if compilation changes schema history or generates an untracked schema. Never use destructive migration fallback. Each version bump requires an explicit migration, historical schema fixtures and executed validation. The v1-to-v2 migration is tested by the actual generated Room runtime on native SQLite host tests, including metadata preservation, post-migration posting, rollback, foreign keys and reopen. Physical-device validation remains a separate release gate.

Custom trigger definitions are also part of the database version contract, though Room's exported JSON does not record them. Future trigger changes require a versioned migration that explicitly recreates affected triggers. The on-open `IF NOT EXISTS` installation is not a trigger-upgrade mechanism. No intermediate, unreviewed milestone-2 database format is a supported released upgrade source; the tested released-source path is v1 to final v2.

Room itself is not encrypted. See SECURITY_AND_INTEGRITY.md; milestone 2 now accepts user-entered financial data into app-private storage. Database construction or migration failure remains visible rather than resetting storage. Native SQLite/Room host integration tests exercise migration, rollback, foreign keys, triggers and reopen. Physical-device/instrumented execution remains a separate validation category.
