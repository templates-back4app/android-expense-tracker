# Emulator run — 2026-10-23 (12:50–13:30 UTC)

First time the app was executed. Everything ran on one Mac (Apple Silicon): Android emulator `expense35` (Pixel 6
profile, Android 15 / API 35, `system-images;android-35;google_apis;arm64-v8a`), a **local** Parse Server 7.5.4 with
the Postgres adapter (`scripts/dev-server.mjs`, database `expense_dev` on PostgreSQL 17 / port 5433) and `cloud/main.js`
mounted. No Back4app backend was involved — see "What still needs the real backend" at the end.

The app was driven with `adb` only (`input tap/text/keyevent`, `uiautomator dump` for coordinates, `screencap`); no
instrumented tests. Timestamps are UTC from the shell; the emulator's clock shows the same minute in local time
(UTC+2) on the screenshots. Server-side facts come from the dev-server request log and `psql`.

Build under test: commit after `e0b2666` with the fixes listed in `docs/notes/build-diary.md` §12. The run was
started on the unfixed build; where a step was repeated on a fixed build it says so.

## Setup steps

| Time | Step | Outcome |
|---|---|---|
| 12:51:28–12:58:57 | `sdkmanager "emulator" "system-images;android-35;google_apis;arm64-v8a"` | 7 m 29 s; 416 MB + 1,779 MB downloaded; SDK dir 526 MB → 5,660 MB |
| 12:53:01–12:53:32 | `npm install --save-dev parse-server@7.5.4 express@^5.1.0` | 31 s, 692 packages, 193 MB |
| 12:54:51 | `node scripts/dev-server.mjs` | `created database expense_dev`; parse-server 7.5.4 on http://localhost:1339/ (mount `/`) |
| 12:55:10 | `node scripts/setup.mjs` (master key, local) | Entry/AppliedOp/Conflict created; `Status now: LIVE` |
| 12:55:20 | `CREATE UNIQUE INDEX "AppliedOp_opId_uniq" ON "AppliedOp" ("opId")` | index created (on Back4app: Dashboard → Index Manager, UNIQUE) |
| 12:55:33 | `node scripts/check-sync.mjs` (first ever run) | **2 passed, 6 failed** — script compared the REST envelope `{result: …}` instead of the result |
| 12:55:52 | `check-sync.mjs` after the one-line fix | **19 passed, 0 failed** (`check-sync-local-2026-10-23.log`) |
| 12:56:37–12:56:50 | `./gradlew assembleDebug` with `backend.properties` (10.0.2.2:1339) + debug network-security config | BUILD SUCCESSFUL, 13 s, 14,325,116 B |
| 12:59:16 | `avdmanager create avd -n expense35 -d pixel_6` | created |
| 12:59:28–12:59:46 | `emulator -avd expense35 -no-snapshot -no-audio -no-boot-anim -no-window` → `sys.boot_completed=1` | **18 s** |
| 12:59:54 | `adb install -r app-debug.apk` | Success (< 1 s) |
| 12:59:55 | `am start -W …MainActivity` (first launch, cold) | `TotalTime: 1845` ms |

## App flow (unfixed build unless stated)

| Time | Step | Outcome | Evidence |
|---|---|---|---|
| 13:00:07 | Login screen | renders; e-mail, password, Sign in, Create an account instead, Continue as guest | `screens/01-login.png` |
| 13:00:17–13:00:22 | Tap **Continue as guest** | Entries screen in 5 s. Server log: `POST /users` (anonymous) → `ensureUser` → `listEntries` ×6 (two sync runs × 3 passes — bug 1) | dev-server log |
| 13:00:50–13:01:07 | Categories → type `Coffee` → Add → Back | "Coffee" listed with a Remove button; stays after Back | `screens/06-categories.png` |
| 13:01:16–13:01:49 | + → amount `12.34`, category Food (default), note `lunch` → Save | row "Food · 2026-10-23 · lunch · $12.34" in 4 s; server: 1 `syncOp` (create) + 3 `listEntries`. The category field was absent from `uiautomator dump` (bug 4) — tapped by coordinates | `screens/03-entry-form.png` |
| 13:02:11–13:02:30 | + → `45`, Coffee (dropdown), note `beans` → Save | "$45.00"; 1 `syncOp` | |
| 13:02:32–13:02:48 | + → `100.5`, Transport, note `bus pass` → Save | "$100.50"; 1 `syncOp` | |
| 13:02:50 | Entries list | total **$157.84**, bar chart Transport/Coffee/Food with values, three rows | `screens/02-entries-chart.png` |
| 13:02:55 | `psql`: Entry rows | 3 rows, amountCents 1234/4500/10050, version 1, owner = the anonymous user; AppliedOp: 3 × create; Conflict: 0 | |
| 13:04:03–13:04:29 | Settings → budget `500` → Save → Back | "Stored on this device only ($500.00 now)"; Sync card: up to date, 0 pending, 0 conflicts, last sync 15:04:02; Entries: "of $500.00 monthly budget · 32%" with progress bar | `screens/05-settings-sync.png`, `screens/02b-entries-budget.png` |
| 13:05:20 | `cmd connectivity airplane-mode enable` | `airplane_mode_on=1`, `Active default network: none` | |
| 13:05:20–13:08:04 | Offline: 9 operations (matrix cells A–G) | each saved instantly; counter "1 unsynced" … "9 unsynced", rows marked "●"; **0 requests** reached the server | `screens/04-offline-queued.png` |
| 13:08:26 | `am force-stop` + relaunch while offline (cell H) | "9 unsynced" still shown; Settings: Pending operations 9. Status read "syncing…" (bug 3) | `screens/04b-settings-offline.png` (retaken on the fixed build, see 13:24) |
| 13:08:45 | `airplane-mode disable` (cell I) | drained in **2 s**: 9 × `syncOp` at 13:08:45.205–.331, each once, then `listEntries`; "unsynced" gone | dev-server log |
| 13:08:50 | `psql` after the drain | Entry: Food 1600 v3; Coffee tombstone v2; Transport tombstone v2; Leisure 2000 v1; Health 3300 v2; Utilities 999 tombstone v2. AppliedOp 12 rows = 12 distinct opIds (6 create, 3 update, 3 delete). **Conflict: 2 rows, both spurious** (bug 2) | matrix |
| 13:08:55 | Settings | up to date, 0 pending, **2 conflicts** listed ("stale baseVersion · base v0 vs server v1", "base v1 vs server v2"), last sync 15:08:45 | `screens/05b-settings-after-drain.png` |
| 13:09:42 | Device B (`POST /loginAs` with the master key → same guest; `syncOp` update note, base v3) | applied, v4 | |
| 13:09:43–13:09:58 | App: Settings → **Sync now** → Back (cell J1) | row now reads "lunch (edited on device B)" | |
| 13:10:15–13:10:38 | Cell J2: airplane on → edit Food `16.00`→`18.00` (base v4); device B sets 1700 (base v4 → v5); airplane off | app pushed update base v4 vs server v5 → applied (LWW: **1800, v6**) + Conflict "stale baseVersion 4 vs 5"; pull leaves the row at $18.00; Settings: 3 conflicts | matrix |

## Fixed build (bugs 1–5 of the diary), 13:14 onwards

| Time | Step | Outcome |
|---|---|---|
| 13:13:27–13:13:36 | `./gradlew test assembleDebug` | 29 tests, 0 failed; 8 s |
| 13:14:23 | `adb install -r`, relaunch | **1** `listEntries` per sync run (was 3) |
| 13:14:51 | *(operator error: `pkill -f` matched the tool shell; emulator + dev servers died)* | emulator rebooted in 14 s; app, Room data and Parse session intact ($71.00 shown) |
| 13:19:56–13:21:03 | Cells D′/F′ (create+edit, edit+edit) on fix attempt 1 | values right, but conflicts 3 → **5**: rows were rebased, the in-memory queue was not |
| 13:22:13–13:22:42 | Fix attempt 2 (`SyncEngine` rebases its list too), `test assembleDebug assembleRelease` | 29 tests, 28 s |
| 13:23:17–13:24:34 | Cells D″/F″ again: create `5`→edit `6.50`; Leisure `22`→`23`→`24`; airplane off | drained in 4 s; Food 650 **v2**, Leisure 2400 **v5**; conflicts **still 5** (no new ones); AppliedOp 23 = 23 distinct |
| 13:24:05 | Settings while offline (fixed build) | "waiting for network / retry", Pending operations 4 — `screens/04b-settings-offline.png` |
| 13:24:35–13:25:07 | Cell K: dev server killed, create `3.33` online, server restarted after 30 s | the Parse SDK's own in-call retries covered the outage; 1 `syncOp`, 1 AppliedOp |
| 13:25:42–13:27:09 | Cell K2: server down **60 s**, create `2.22` | logcat 13:26:14 `attempt 1 failed: i/o failure`; Settings "waiting for network / retry", Last error "i/o failure"; worker `Result.retry()` → backoff → `syncOp` at 13:27:05 with the **same opId**; 1 AppliedOp row; conflicts still 5 |
| 13:27:15–13:28:00 | Cold start ×10 (`am force-stop`, 2 s, `am start -W`) | TotalTime 919, 930, 991, 939, 934, 876, 951, 1021, 872, 910 ms → **p50 932 ms** (min 872, max 1021). Debug build, arm64 emulator on an M-series Mac — a number to compare against itself, not a device figure |

## Final state of the local database

Entries 9 (3 tombstones), AppliedOp 24 rows = 24 distinct opIds (every op applied exactly once across all cells),
Conflict 5 rows: 2 spurious from the unfixed build (kept as evidence) + 1 genuine (J2) + 2 spurious from fix attempt 1.
Zero on the final build.

## Screenshots (`docs/evidence/screens/`)

| File | What |
|---|---|
| `01-login.png` | Login screen |
| `02-entries-chart.png` | Entries, three rows, Canvas bar chart, no budget yet |
| `02b-entries-budget.png` | Same with the $500 budget bar at 32% |
| `03-entry-form.png` | New expense form filled in (12.34 / Food / today / lunch) |
| `04-offline-queued.png` | Airplane mode: "9 unsynced", rows marked ●, totals already updated |
| `04b-settings-offline.png` | Settings while offline (fixed build): "waiting for network / retry", 4 pending |
| `05-settings-sync.png` | Settings after the budget was saved: up to date, 0 pending, 0 conflicts |
| `05b-settings-after-drain.png` | Settings after the first drain on the unfixed build: 2 (spurious) conflicts listed |
| `06-categories.png` | Categories screen with "Coffee" added |

There is no dedicated offline banner in the UI; the queued state is the "N unsynced" counter on the budget card, the
● marker on dirty rows, and the Sync card in Settings. That was enough to read the state from a screenshot.

## What still needs the real backend

- `check-sync.mjs` and the app against `parseapi.back4app.com` (TLS, Back4app's Node 19 Cloud Code runtime, and
  MongoDB instead of Postgres — the `DUPLICATE_VALUE` (137) mapping of the unique index was verified here on the
  Postgres adapter only).
- The dashboard steps: Cloud Code upload of `cloud/main.js` **and** `cloud/config.js`, the "Files pending deploy" quirk,
  the Index Manager UNIQUE index, and switching `SETUP_ENABLED` off after setup.
- `setupSchema` through `scripts/setup.mjs` without `MASTER_KEY` against the live app (verified locally only).
- A physical device: cold start, keyboard behaviour and the date picker were only seen on the emulator.
