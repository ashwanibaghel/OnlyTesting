# Room schema history

KSP generates `com.fintrack.data.local.FinTrackDatabase/1.json` during the first successful compilation. Commit the generated JSON. Every future schema version must preserve previous JSON files and supply tested non-destructive migrations. Never fabricate schema identity hashes. See `docs/ARCHITECTURE.md` for migration gates.
