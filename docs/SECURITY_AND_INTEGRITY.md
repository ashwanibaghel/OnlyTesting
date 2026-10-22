# Integrity, reconciliation, security and backup

## Data integrity contract

Room is the source of posted truth; aggregates are derived projections and never independently editable. Use foreign keys, unique command/occurrence IDs and validation before writes. Every related journal/posting/allocation/audit change runs in one database transaction. Overflow, unknown references, insufficient funding and stale revision abort the whole command with an actionable error. Never catch an integrity error and return a successful balance. Transactions from multiple screens serialize through the repository; reads requiring a combined snapshot also use one database transaction.

Reconciliation compares statement balance with posted account balance as of an explicit date and clearing status. Show statement, calculated amount and difference. Do not silently set a cached balance. Resolution is an explicit correction or adjustment journal with reason and audit record; original entry history remains inspectable. Account deletion with history is prohibited; archive instead. Audit history supports user review but is not a cryptographic tamper-proof claim on a rooted device.

## Foundation security posture

No INTERNET permission, authentication, backend, analytics, finance logging or financial fixtures in the startup UI. Room database and preferences remain in app-private storage under Android's device protection. **Room does not itself encrypt the database.** Full device access, root and unlocked-device compromise remain outside this initial protection. Do not claim application-level encryption until implemented and tested. Production app lock/database encryption and recovery are release decisions; biometric access alone is not portable backup encryption.

Disable Android automatic cloud backup and device-to-device app-data transfer through manifest plus XML exclusion rules; financial exports must be deliberate. Private data must never enter CI artifacts or version control. Capture only synthetic data in tests. Future notifications use generic private-lockscreen text by default. App lock must handle process restart, timeout, screenshots and accessibility thoughtfully.

## Proposed encrypted export (not implemented)

Use Storage Access Framework for a user-selected destination; export a consistent SQLite snapshot through supported database/transaction mechanisms, never copy only the main file while WAL is active. Versioned container includes schema/app version, currency, created date, KDF parameters and authenticated payload. Use a standard, reviewed password KDF (proposal: PBKDF2-HMAC-SHA256 with benchmarked work factor and random salt) and AES-256-GCM with fresh 96-bit nonce, authenticating the header as associated data. No custom cipher, hardcoded secret or plaintext temporary export. Keystore may protect local keys but cannot be the only key for a cross-device export. Forgotten export passwords are not recoverable by the developer.

Restore into a separate app-private staging database; authenticate before trusting data, enforce size/KDF limits, reject unknown future versions/path traversal, migrate staging through supported versions, run SQLite integrity/foreign-key checks, validate ledger conservation, allocation funding, IDs and currency consistency. Only then close current DB, atomically replace it with rollback protection, reopen and notify the UI to reload. Failed auth, migration, cancellation, disk full or process death must preserve the old database. Audit the restore operation without leaking payloads. Do not merge books in Phase 1.

Required milestone-6 tests: round trip, wrong password, altered/truncated header/payload, unsupported version, repeated nonce prevention, malicious excessive KDF params, migration from each supported version, missing references, unbalanced ledger, stale WAL, insufficient storage and interruption before/after swap. No backup/restore feature ships until these pass.
