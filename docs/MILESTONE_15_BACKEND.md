# Ledger snapshot/account scaling

The original snapshot rebuilt complete leg lists for every account, repeatedly scanning history for posted and cleared balances. Account validation loaded all account metadata; control/category lookup loaded every ledger account. These costs increased with both account count and history size even when a command touched one account.

Snapshot construction now accumulates per-ledger posted amounts, per-asset cleared amounts and income/expense in a single pass while producing the unchanged immutable history read model. BigInteger accumulators retain exact cancellation and final signed-64-bit range checks through the existing API-26-compatible engine helper. Every posted control account total is still validated, archived money remains included, and pending/cleared/reversal semantics are unchanged.

Owned account validation uses a book-scoped ID lookup. Control lookup uses the existing `(bookId, role, name)` index and preserves the first stored identity when legacy duplicate labels exist. Foreign-book category matches cannot be selected. This increment changes no tables, schemas, financial rows, command fingerprints or migration history.

## Measurement and verification

The same synthetic on-disk native SQLite fixture declares 1,000 accounts through one real atomic setup command, then records pending split expense, investment transfer/fee, reversal, clearing and archiving. It checks all account/aggregate totals, immutable history and original setup receipt replay after subsequent revisions. Five equivalent full snapshots provide diagnostic samples with no flaky timing threshold. A separate legacy-metadata case checks original control identity and book isolation.

Before the change, this host fixture reported setup 9,789 ms and snapshot samples `[463, 235, 160, 158, 201]` ms (median 201 ms). Baseline XML is preserved locally at `.tools/scaling-baseline.xml`; `.tools/phase1-scaling-baseline.log` records the successful baseline run. The post-change focused run reports setup 2,034 ms and snapshot median 53 ms. The full gate reports setup 1,980 ms and snapshot samples `[48, 49, 68, 40, 47]` ms (median 48 ms). These diagnostic host runs use the same fixture; runtime/cache/GC differences mean they are not controlled device benchmarks or guaranteed speedups.

Focused verification passes both new cases and all 43 existing ledger cases. The clean full Windows/JDK 17 gate passes **368 tests** (85 finance-engine JVM, 227 Room/NATIVE SQLite, 7 codec JVM and 49 pure ViewModel), zero failures/errors/skips. All prior 366 CI test identities remain present. Lint reports zero errors/35 existing warnings; debug assembly passes, and V1–V6 schemas are unchanged. Full local log: `.tools/phase1-scaling-gate.log`.

This removes account-count multiplication from snapshot balance aggregation. The legacy API still returns the full history graph, and planning/schedule/review paths still perform additional full-history work. This is a measured backend improvement, not completion of the large-history performance gate or proof of Android-device performance. Final consumer UI, physical-device/emulator/Compose execution and Phase 1 completion remain unclaimed.
