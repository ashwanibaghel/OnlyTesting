# Isolated backup reconstruction and historical migration inspection

`EncryptedBackupRepository.inspectStorageEncrypted` adds a real storage-validation step after authenticated EOF. It reconstructs typed rows in a fresh private SQLite database, opens that database through the production Room migration chain, checks integrity/foreign keys and returns source metadata plus migrated table counts. It does not accept a live destination path or replace user data.

## Trust and resource boundaries

- Only canonical compiler-packaged V1–V6 schema SQL and indices create the isolated database. Row values use prepared binding, never SQL interpolation; no executable SQL is read from a backup.
- Primary/unique keys, non-null/type framing and deferred foreign keys are checked while importing, followed by explicit foreign-key checks before commit and after migration. Deferred relationships allow the canonical export order, which is not parent-first.
- A 512 MiB SQLite-page cap bounds initial reconstruction. A snapshot whose indexed reconstruction exceeds that cap is rejected. This is separate from the existing 512 MiB logical payload limit; valid logical size alone does not guarantee sufficient indexed storage.
- The staged Room database uses explicit production migrations with no destructive fallback. Migration failures abort inspection. Original source payload metadata/digest and migrated table counts remain distinct; V1 migration legitimately introduces the personal book and owned ledger accounts without inventing balances or transactions.
- Staging handles, prepared statements and transaction resources close on success, failure or cancellation; the owned database and SQLite sidecars are removed. First use removes only owned interrupted `db-<digits>.db` files/sidecars, preserving unrelated files. The authenticated plaintext staging file remains owned/cleaned by the outer cryptography boundary.

## Meaning of this gate

Storage validation is **not complete financial semantic validation**. A SQL-valid snapshot can still contain unbalanced or misclassified financial histories. A regression fixture deliberately demonstrates this boundary rather than treating a successful SQL check as permission to restore. Full financial validation, a reviewed replacement digest/epoch and atomic live restoration/rollback remain subsequent gates. No restore/apply API or consumer UI is introduced.

## Verification

The fresh, single-build clean local gate passed in **8m18s**: **404 tests, zero failures/errors/skips** (85 finance-engine, 254 native Room, nine host cryptography, seven receipt codec and 49 ViewModel cases). All 392 previous CI test identities are retained. Lint passed with zero errors/35 existing warnings; historical/current V1–V6 schemas are unchanged. Debug assembly passed, and the resulting APK's six canonical schema SHA-256 hashes, backup/staging DEX classes and absence of network permission were independently checked.

Twelve new native SQLite tests cover isolated snapshot/original receipt replay, genuine historical V1–V6 migration, unsupported V1 currency rejection, broken foreign keys, duplicate primary/owner keys, SQL-like labels, malformed payload rollback, consumer failure/cancellation, reconstruction page bounds and narrowly owned interrupted-file cleanup. An additional deliberate unbalanced-journal fixture documents the storage/financial-validation distinction. The existing encrypted export test now also exercises the real encrypted storage-inspection API and exact table counts.

An initial exception test incorrectly asserted object identity across a coroutine boundary and was corrected to check the preserved exception type/message. An accidentally overlapping clean run hit the active test-output file lock; both processes were allowed to exit, and the clean single-build gate above was rerun independently. The overlap was not used as final evidence or bypassed by skipping tests. Remote CI evidence is appended after that independent gate finishes.

Synthetic host Robolectric/native SQLite execution is distinct from device/emulator/UI execution. Final consumer UI remains on hold for approved high-fidelity visuals; Phase 1 is not complete.
