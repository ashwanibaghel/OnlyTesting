# Repository and environment assessment

Assessment date: 2026-10-23 (Asia/Kolkata).

`D:\FinTrack` was empty, with no Git metadata, remote, existing project files or applicable AGENTS.md found. No existing source was overwritten. Windows PowerShell 7.4.20 and Git are available. PATH Java/Javac are Oracle 18.0.2; JAVA_HOME, ANDROID_HOME and ANDROID_SDK_ROOT were unset. A separate Temurin 17.0.13 exists at `C:\Users\ashwa\.jdks\temurin-17.0.13`. Android SDK exists at `C:\Users\ashwa\AppData\Local\Android\Sdk`, including platforms 33-36 and build-tools 35.0.0. Gradle 8.9 is cached. `gradle`, `kotlinc` and `gh` are not on PATH. No Android Studio or emulator is required or launched.

## Version baseline

These are fixed stable releases, deliberately not a claim to the latest releases. Upgrade in a dedicated change with CI evidence. Package namespace `com.fintrack` is provisional and must be finalized before release.

| Component | Pin | Reason / primary evidence |
| --- | --- | --- |
| JDK / JVM bytecode | 17 | [AGP 8.7 compatibility](https://developer.android.com/build/releases/agp-8-7-0-release-notes) |
| Gradle wrapper | 8.9 | Required by AGP 8.7; distribution SHA-256 pinned |
| Android Gradle plugin | 8.7.2 | API 35 support; within Kotlin's documented AGP range |
| Kotlin / Compose compiler | 2.1.20 | [Kotlin compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html); compiler plugin matches Kotlin |
| KSP | 2.1.20-1.0.32 | [Matching KSP release](https://github.com/google/ksp/releases/tag/2.1.20-1.0.32) |
| Compile / target SDK | 35 / 35 | Foundation baseline supported by AGP; review store target policy before release |
| Minimum SDK | 26 | Proposal: simpler java.time and smaller compatibility surface |
| Build tools | 35.0.0 | Explicit local/CI installation |
| Compose BOM | 2025.04.01 | [Official Compose BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping); UI and Material 3 use BOM |
| Core / Activity | 1.15.0 / 1.10.1 | Stable AndroidX pins |
| Lifecycle | 2.8.7 | ViewModel and lifecycle-aware Compose state |
| Room | 2.7.1 | [Room release notes](https://developer.android.com/jetpack/androidx/releases/room); Kotlin >= 2.0, KSP2 |
| Hilt | 2.56.2 | [Dagger release](https://github.com/google/dagger/releases/tag/dagger-2.56.2); KSP processing |
| DataStore | 1.1.4 | [DataStore release notes](https://developer.android.com/jetpack/androidx/releases/datastore); nonfinancial settings only |
| Coroutines | 1.10.2 | [Official release](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.10.2) |
| JUnit | 4.13.2 | JVM unit tests |

Published compatibility ranges establish a baseline, not proof that this complete graph builds. See VERIFICATION.md for actual results. The source of truth for dependency versions is `gradle/libs.versions.toml`.

## Build approach

GitHub Actions uses Ubuntu 24.04, Temurin 17, Android setup, explicitly installed SDK packages, wrapper validation, JVM tests, Android unit tests, Android lint and debug assembly. APK upload occurs only after all build checks pass. Reports upload even after failure. Actions use version tags; pin reviewed action commit SHAs before production hardening.

`-PengineOnly=true` excludes the Android module and avoids resolving Android plugins/SDK for finance-only tests. Normal builds always include the app. No machine paths or SDK installations are committed. No mandatory server, cloud, login or analytics dependency.
