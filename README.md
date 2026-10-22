# FinTrack

Local-first native Android personal finance application. **Milestone 2** adds manual accounts and an immutable, database-backed transaction ledger. The temporary Compose UI can create accounts, post income/split expenses/transfers with fees, review balances/history, reverse entries, clear pending entries, archive accounts and reconcile statements. No sample financial data is seeded.

## Start here

- [Environment and pinned toolchain](docs/ENVIRONMENT.md)
- [Phase 1 specification and milestones](docs/PRODUCT.md)
- [Architecture and domain relationships](docs/ARCHITECTURE.md)
- [Milestone 2 accounting, database enforcement and test contract](docs/MILESTONE_2.md)
- [Platform-neutral financial rules](docs/FINANCE_RULES.md)
- [Integrity, security and backup](docs/SECURITY_AND_INTEGRITY.md)
- [Build instructions and verification evidence](docs/VERIFICATION.md)

## Build without Android Studio

Preferred: push this folder to your own GitHub repository, enable Actions, then run **Android CI** (also triggered on pushes and pull requests). Download `fintrack-debug-apk` from the successful run. No signing secret, backend, Android Studio or emulator is required for the debug build.

With JDK 17 and Android SDK platform 35/build-tools 35.0.0 available locally:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
.\gradlew.bat :finance-engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The pure JVM module can also run without configuring the Android project:

```powershell
.\gradlew.bat -PengineOnly=true :finance-engine:test
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Reports: `finance-engine/build/reports/tests/test/`, `app/build/reports/`. A debug APK is for development, not a production release.

## Scope boundaries

Money uses checked signed 64-bit minor units and an explicit currency. The default local book uses INR; no FX, credit cards or loans are implemented. The finance engine has no Android, database, network, clock or AI dependency. Room v2 adds sealed balanced journals, append-only postings/audit history, command idempotency and optimistic book revisions, with an explicit non-destructive v1 migration. Reverse-and-replace corrections and full search/date filtering are also available through the repository API.

Budgets, goals, reservations, backups, cloud sync and advanced reports remain future milestones. This is a development build with provisional application ID and minimum API 26. Device interaction testing and release hardening are separate from native SQLite host integration tests.
