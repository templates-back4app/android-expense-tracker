# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased] — 2026-10-23 (versionName still 0.1.0; bump when releasing)

First run on an emulator (Android 15, arm64) against a local Parse Server 7.5.4 + PostgreSQL; evidence in
`docs/evidence/emulator-run-2026-10-23.md`, `docs/evidence/offline-matrix.md` and `docs/evidence/screens/`.

### Fixed
- `SyncEngine` ran all three push/pull passes on every run (`return@repeat` never left the loop): three `listEntries`
  per write. Now one.
- Chained offline operations on one entry (create → edit, edit → edit) were reported as "stale baseVersion" conflicts.
  `SyncPlanner.rebase` rebases still-queued ops of an entry to the version the completed op produced, in Room and in the
  engine's in-memory queue. Unit test added.
- Settings showed "syncing…" while offline; WorkManager's ENQUEUED (constraints unmet / backoff) is no longer counted as
  running — the screen says "waiting for network / retry".
- The category picker on the entry form was invisible to accessibility services (an unnamed clickable overlay); it now
  exposes "Category: <name>" with a dropdown role.
- `scripts/check-sync.mjs` compared the REST envelope `{"result": …}` instead of the Cloud function result (it had never
  been run); 19/19 now.

### Added
- Cloud function `setupSchema` (classes, fields, CLPs through `Parse.Schema` with the master key inside Cloud Code;
  adds only, never touches indexes), guarded by `SETUP_ENABLED` in the new `cloud/config.js`. `scripts/setup.mjs` uses
  it when `MASTER_KEY` is absent, so a Back4app backend can be set up with the REST key alone.
- `scripts/dev-server.mjs` (`npm run dev:server`): local Parse Server 7.5.4 with the Postgres adapter and the same Cloud
  Code; `cloud/package.json` (`"type": "commonjs"`) so Node loads `cloud/*.js` the way Back4app does.
- Debug-only network security config allowing cleartext to `10.0.2.2`/`localhost`; optional `parse.serverUrl` in
  `local.properties`.
- Evidence: emulator run log, offline matrix, 9 screenshots, cold-start measurements, local `check-sync` log.

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
