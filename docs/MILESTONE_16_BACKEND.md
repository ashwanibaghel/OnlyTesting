# Consistent home/weekly-review data integration

`FinancialOverviewRepository` combines current financial session/planning, monthly and weekly actual reports, bounded recent/pending history, planned schedule occurrences and current-week review status inside one Room transaction. Hilt exposes the persisted boundary for later native Compose integration. All sources must agree on book, revision and requested dates before a snapshot is published; failed sources propagate an error rather than fabricate zero balances.

Actual reports, funded reservations, pending actual transactions and unconfirmed schedules remain separate typed models. Schedule reading reuses the already-read funded buckets instead of rebuilding planning a second time. Recent history is bounded to ten entries; pending history to twenty with cursor continuation. The unresolved pending count covers the whole book at period end, independently of that bounded page.

Typed attention items cover incomplete setup, pending rollover, pending clearing, current-month budget overspend, reservation/safety shortfalls, uncovered obligations, unsupported goal backing, goal collisions/missed deadlines and weekly review status. Their amounts can overlap and must never be summed as independent liabilities. Goal minimum backing and collision assumptions remain those of the actual planning engine; no new financial amounts or priorities are invented.

Weekly completion is an explicit existing audited `ScheduleAction.WeeklyReview` command. Reads never mark a review complete. The stored completion revision is retained; later financial revisions produce a separate changed-since-review flag. A new week has its own uncompleted status. Overview reads never confirm pending transactions, post planned schedules, perform automatic setup or advance months. A pending rollover remains visible for the existing explicit preparation boundary.

Observation follows book revisions and minute clock pulses, using the book timezone supplied by the session. Date-sensitive reports/deadline warnings refresh even without a financial write. As with the existing session boundary, the default clock refresh can take up to a minute; this is not an exact-time notification guarantee.

## Verification and remaining scope

Nine focused native Room cases pass: empty setup/no fabricated activity, actual/pending/planned/funded separation, budget overrun, observed goal-backing recovery after real transactions, immutable weekly review revision, timezone midnight/month/deadline refresh, read-only/cross-book isolation, source failure and inconsistent source rejection. The full gate additionally checks rejection of a wrong dated report with the same financial revision.

The clean full Windows/JDK 17 gate passes **377 tests** (85 finance-engine JVM, 236 Room/NATIVE SQLite, 7 codec JVM and 49 pure ViewModel), zero failures/errors/skips. All prior 368 CI test identities are retained. Lint reports zero errors/35 existing warnings; debug assembly passes. V1–V6 schemas remain unchanged. Local log: `.tools/phase1-overview-gate.log`.

These are host Robolectric/on-disk native SQLite tests. No consumer layouts or visual choices were added. The repository is backend integration for future Home/Insights/weekly review flows; final polished navigation, consumer UI behavior/accessibility checks, backup/security and other outstanding Phase 1 gates remain incomplete. Legacy full-history planning/session performance also remains a separate gate. No device/emulator/Compose execution, final visual approval or Phase 1 completion is claimed.
