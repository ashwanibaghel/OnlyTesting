# FinTrack

Local-first native Android personal finance application. **Foundation milestone only**: the startup screen verifies application wiring; account entry, financial dashboards, backup and reminders are not implemented yet. No sample financial data is presented as real.

## Start here

- [Environment and pinned toolchain](docs/ENVIRONMENT.md)
- [Phase 1 specification and milestones](docs/PRODUCT.md)
- [Architecture and domain relationships](docs/ARCHITECTURE.md)
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

Money uses checked signed 64-bit minor units and an explicit currency. Actual ledger balances and virtual reservations are separate. The finance engine has no Android, database, network, clock or AI dependency. Database v1 stores account metadata only; production ledger writes are deliberately deferred until the tested posting/reconciliation milestone.
