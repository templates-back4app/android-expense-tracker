# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0] — 2026-10-23

First release — the companion repository for the article "Android expense tracker app with source code (Kotlin +
Jetpack Compose + a Parse backend): download, run, and understand offline sync".

### Added
- Single-activity Jetpack Compose app (Material 3, Navigation Compose): Login (e-mail/password, create account, continue
  as guest), Entries (month filter, spent-vs-budget progress bar, Canvas bar chart of category totals), Entry form
  (USD typed as `12.34`, stored as integer cents; category; date picker; note), Categories, Settings (monthly budget,
  sync status with pending ops / conflicts / last sync / last error, sign out).
- Offline-first storage in Room: `entries`, `ops` (outbox), `conflicts` (evidence), `categories` (local only).
- Sync: `SyncWorker` (WorkManager, network constraint, exponential backoff) drains the outbox in order per entry through
  the `syncOp` Cloud function and pulls with `listEntries`; `SyncPlanner` holds the rules (last arrival wins per field,
  tombstones, idempotent retries with a reused `opId`, cursor, 90-day full-pull rule) as pure Kotlin.
- Cloud Code (`cloud/main.js`, Parse Server 7): `syncOp`, `listEntries`, `ensureUser`, `beforeSave`/`beforeDelete`
  guards on `Entry`, `AppliedOp`, `Conflict`, and the `purgeTombstones` job.
- Backend scripts: `scripts/setup.mjs` (classes, indexes, class-level permissions) and `scripts/check-sync.mjs`
  (end-to-end contract check with a throw-away user).
- JVM unit tests: money math, sync planner, fields codec, response parser.
- Documentation: README, AGENTS.md, `docs/notes/build-diary.md`, `docs/evidence/README.md`.

### Known limitations
- Not run on a device or emulator before release (built with command-line tools only).
- The unique index on `AppliedOp.opId` must be created in the dashboard; it is the only database-level guarantee.
- Categories and the monthly budget are not synced.
