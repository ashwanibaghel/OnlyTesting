# Architecture and domain

## Dependency direction

`app.ui -> ViewModel -> use case / repository -> Room + finance-engine`. The `finance-engine` JVM module depends only on Kotlin/JDK. It receives immutable inputs and explicit dates; it does not read Room, Android, system time, settings or network. Future iOS follows FINANCE_RULES.md and shared fixture examples rather than Android code.

Two modules keep the initial foundation maintainable. App packages are `ui/startup`, `data/local`, `data/settings`, and `di`. Add `domain/repository`, `domain/usecase` and feature packages as real workflows arrive; split a data module only when useful. Compose collects ViewModel StateFlow using lifecycle-aware APIs. Hilt provides Room and a single preferences DataStore. Financial state belongs exclusively in Room; DataStore stores UI preferences. No WorkManager dependency until scheduled work exists.

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

Room v1 contains **account metadata only**, with ID, name, explicit currency code and type. There is no cached balance and no financial write API. The DAO exposes observation only. The startup screen does not create accounts or seed money. This keeps the foundation from accepting incomplete financial mutations before the ledger exists.

V2 will add the financial tables above and a repository posting service: validate a complete command with the engine, enforce book/currency/account constraints, insert the balanced journal and allocation consumption plus audit atomically in `withTransaction`, and expose one consistent snapshot. No composable or ViewModel may call a posting DAO directly. Unique command IDs, foreign keys and optimistic revisions prevent duplicate/stale writes. Posted rows are append-only at the repository boundary; SQLite constraints/triggers and tamper detection are designed and tested at milestone 2.

## Database migration strategy

`exportSchema = true`; KSP exports schema JSON under `app/schemas`. The compiler-generated v1 baseline is committed from successful CI, with its artifact checksum verified. Never hand-author a Room identity hash. CI fails if compilation changes committed schema history or generates an untracked schema. Never use destructive migration fallback. Each version bump requires an explicit migration (auto only when provably safe), historical schema fixtures and MigrationTestHelper or equivalent device-backed validation. Test row counts, balances, audit continuity, foreign keys and rollback. CI runs compile/lint/JVM checks now; database migration/device tests are a separately disclosed release gate, not claimed by the initial workflow.

Room itself is not encrypted. See SECURITY_AND_INTEGRITY.md; foundation contains no user-entered financial data. Database construction failure must remain visible rather than resetting storage.
