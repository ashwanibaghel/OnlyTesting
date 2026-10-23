# Revision-bound, paged transaction history

`HistoryRepository` is a persisted read boundary for future Activity screens. It returns sealed actual journals and their complete split/transfer/fee legs, with account/date/kind/clearing filters and optional reversal visibility. Archived account history remains readable. It does not post transactions, change financial totals or mix virtual reservations with actual money.

Pages use the existing descending `(effectiveDate, createdAt, id)` order and keyset continuation rather than OFFSET. A cursor binds the book, all filters and the book revision. Changed financial state or filters require a fresh first page, preventing a mixture of different revisions. Tied dates/timestamps retain a deterministic ID ordering. First-page observation follows real book revision changes.

Page size is 1–200. Unicode case-insensitive substring search preserves literal `%` and `_` behavior and never interpolates user text into SQL. Each search request examines at most 10,000 candidate journals, fetching legs in batches of at most 200 journal IDs. A page with no matches can still return a continuation cursor: consumers must check `exhausted`, not treat an empty intermediate page as proof that no match exists. This bounds per-request work; searching an entire large book can require multiple requests. Memory also depends on the actual split count of individual journals.

## Necessary V6 migration

The initial 10,053-journal native SQLite fixture passed functional paging/search assertions but reproduced a temporary B-tree sort for chronological browsing. V6 adds only `index_journals_bookId_effectiveDate_createdAt_id`. Explicit `5→6` migration creates that index without changing financial rows or book revision. V1–V5 exported schemas remain historical contracts. The regression checks the actual production window query's plan, including its seal/reversal/clearing joins.

The populated V5 migration case preserves the complete ledger snapshot, pre-migration cursor continuation and replay of an original canonical command receipt. Existing V1→current and intermediate migration cases remain in the full suite.

SQLite documents [row-value comparisons and scrolling-window keyset queries](https://www.sqlite.org/rowvalue.html). Android's [SQLite version table](https://developer.android.com/reference/android/database/sqlite/package-summary) lists versions supporting these comparisons from the app's minimum API 26; manufacturer versions can vary. Host tests do not establish physical-device compatibility or performance.

## Verification and boundaries

Eleven focused Room/NATIVE SQLite tests pass: tied order/no duplicates, revision/filter/book cursor isolation, account/date/kind/clearing filters, literal Unicode search, reversal visibility, clearing invalidation, terminal/empty pages, invalid input, bounded large-history search/index plan and populated V5 migration/receipt preservation.

The clean full Windows/JDK 17 gate verifies **350 tests** (85 finance-engine JVM, 209 Room/NATIVE SQLite, 7 codec JVM and 49 pure ViewModel), with zero failures/errors/skips. All prior 339 CI test names remain present. Lint reports zero errors/35 existing warnings; debug assembly passes. Structural comparison confirms V6 changes only the added ordering index, version and generated identity, with V1–V5 byte-for-byte unchanged. Local log: `.tools/phase1-history-gate.log`.

In that full host run, synthetic seeding of 10,053 total journals took 11,880 ms, the bounded 10,000-row search took 812 ms and the 100-entry browse took 12 ms. The actual production query used the new composite index without a temporary ordering B-tree. These single-run measurements are diagnostic evidence, not timing guarantees or Android-device benchmarks.

These are on-disk host Robolectric tests with synthetic, explicitly labeled test fixtures. The new read API does not remove the legacy full-history snapshots still used by mutation/planning/session paths; optimizing those paths remains a separate backend gate. This increment does not implement final consumer UI, demonstrate device performance or complete Phase 1. Unreviewed visual mockups remain outside this change.

Commit `fb0aafc44e114817b5ef5b8e7773123466df2f10` passes [CI run 39886580501](https://github.com/ashwanibaghel/OnlyTesting/actions/runs/39886580501). Downloaded reports SHA-256 `6946afb98b5a6a00e066664678e182dc252fa9ea103b964d4bdc3d33d21f8ceb` was verified; independent JUnit parsing matches all 350 local test identities with zero failures/errors/skips. CI lint has zero errors/35 warnings and schema/debug gates pass.
