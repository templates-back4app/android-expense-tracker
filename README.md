# Android expense tracker — Kotlin + Jetpack Compose + a Parse backend, with offline sync

Companion repository for the article *"Android expense tracker app with source code (Kotlin + Jetpack Compose + a Parse
backend): download, run, and understand offline sync"*. It is the smallest useful expense tracker that works offline
first and syncs to a Back4app (Parse Server) backend through two Cloud functions — nothing more.

**Screens**: Login (e-mail/password, or *Continue as guest* = anonymous Parse user) · Entries (month filter, spent vs
monthly budget progress bar, one bar chart of category totals drawn with Compose `Canvas`) · Entry form (amount in USD
typed as `12.34`, stored as integer cents; category; date; note) · Categories · Settings (monthly budget, sync status,
sign out).

**Stack**: Kotlin 2.4 · Jetpack Compose (BOM 2026.01, Material 3) · Navigation Compose · Room 2.8 · WorkManager 2.11 ·
Parse Android SDK 4.4 · Cloud Code for Parse Server 7 (Node 19) · AGP 8.13 · Gradle 9.5 · compileSdk 35 / minSdk 26.

## Download → open → paste two keys → run

1. **Download**: `git clone <this repo>` (or *Code → Download ZIP* and unzip).
2. **Open in Android Studio**: *File → Open…* → the repository folder. Let the Gradle sync finish (first time: ~2–4 min,
   it downloads Gradle 9.5.1 and the libraries).
3. **Paste the two keys**: open `local.properties` (Android Studio creates it with `sdk.dir=…`) and add
   ```properties
   parse.appId=PASTE_APPLICATION_ID
   parse.clientKey=PASTE_CLIENT_KEY
   ```
   Both come from your Back4app app → *App Settings → Security & Keys*. Only these two. **Never the master key** — it
   has no place in an app. (Alternative: copy `app/src/main/assets/backend.properties.example` to `backend.properties`
   next to it; both files are gitignored.) Rebuild after changing them: they are compiled into `BuildConfig`.
   An optional `parse.serverUrl=` (or `serverUrl=` in the assets file) points the app at another Parse Server, e.g. the
   local one below.
4. **Run** (▶) on a device or emulator with Android 8.0+ (API 26). Tap *Continue as guest* and add an expense.
   Turn airplane mode on, add another — it saves instantly; turn it off — the "unsynced" counter drops to zero.

The backend needs a one-time setup (classes + Cloud Code), see **Backend** below. Without it the app opens, tells you
the keys are missing on the Login screen and nothing else works; with keys but without Cloud Code you can sign in but
every sync attempt fails with *141 Invalid function* in Settings → Sync.

## Prerequisites (listed separately on purpose)

| What | Version used here | Notes |
|---|---|---|
| Android Studio | 2026.2.1 (build AI-262.9437) | any recent stable should do; it bundles the JDK below |
| JDK | 25 (JetBrains Runtime bundled with Android Studio) | no separate JDK install; on the command line set `JAVA_HOME` to `…/Android Studio.app/Contents/jbr/Contents/Home` (macOS) |
| Android SDK | platform 35, build-tools 35.0.0, platform-tools | Android Studio installs them on first sync; command-line recipe in `docs/notes/build-diary.md` |
| Gradle | 9.5.1 (wrapper, downloaded automatically) | why not 8.x / 9.8: see *Toolchain notes* |
| Device / emulator | Android 8.0+ (API 26) | tested on the emulator (Android 15, arm64) — see *What was verified* |
| Node.js | 20+ | only for the two backend scripts |
| Back4app account | free plan is enough | https://www.back4app.com/ |

### Command line (no IDE)

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew test               # JVM unit tests, no device needed
./gradlew assembleRelease    # see "Release build"
```

### Release build

`assembleRelease` minifies with R8 and signs with the **debug keystore** when no release keystore is configured, so the
command always produces an installable APK (`app/build/outputs/apk/release/app-release.apk`) — fine for testing, not for
Play distribution. For a real signature add to `local.properties`:

```properties
release.storeFile=keystore/release.jks
release.storePassword=...
release.keyAlias=...
release.keyPassword=...
```

(`*.jks` is gitignored.) Create the keystore with `keytool -genkeypair -v -keystore keystore/release.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`.

## Backend (one-time, ~10 minutes)

1. Create a Back4app app (*Build your Backend*). Copy the Application ID, Client key and REST key. The master key stays
   in the dashboard: nothing below needs it on your machine.
2. `cp .env.example .env` and paste `APP_ID` and `REST_KEY` (scripts only; the app never sees this file).
3. Dashboard → **Cloud Code** → *Upload* → `cloud/main.js` **and** `cloud/config.js` → confirm the badge *Files pending
   deploy (2)* → *Deploy*. (A freshly created backend sometimes reports "Success" while deploying nothing — reload the
   page, make the files dirty, deploy again; `node scripts/setup.mjs` prints `Status now: LIVE` once the functions answer.)
4. `node scripts/setup.mjs` — without `MASTER_KEY` in `.env` it calls the Cloud function `setupSchema` (REST key), which
   creates the classes `Entry`, `AppliedOp`, `Conflict` with their fields and class-level permissions using the master
   key *inside* Cloud Code (signed-in users can *read*, nobody can *write* from a client). Idempotent: rerun at will;
   it only adds missing fields and never touches indexes. With `MASTER_KEY` set, the same table is applied through the
   schema API directly (plus a few plain performance indexes).
5. `cloud/config.js` → `SETUP_ENABLED: false` → *Deploy* again. While the flag is on, anyone with the Application ID can
   call `setupSchema`; it re-applies the intended permissions and nothing else, but close it before real users sign up.
6. Dashboard → **Database** → `AppliedOp` → *Index Manager* → new index `{opId: 1}` with **Unique**. The schema API
   cannot create unique indexes, and this one is what makes retries idempotent (details below).
7. `node scripts/check-sync.mjs` — with the REST key alone (cleanup runs through the `testCleanup` Cloud function while
   `SETUP_ENABLED` is on; with `MASTER_KEY` in `.env` it cleans up directly) — creates a throw-away user, runs the whole contract (create, duplicate retry, update, stale update, delete, edit-after-delete, pull with
   tombstones, client-write lockdown), deletes everything it made and prints `19 passed, 0 failed`.
8. Optional: schedule the `purgeTombstones` job daily (Dashboard → Cloud Code → Jobs, or `POST /jobs/purgeTombstones`
   with the master key from any cron).

### No Back4app account yet? Run the backend locally

`scripts/dev-server.mjs` starts Parse Server 7.5.4 with the Postgres adapter on `http://localhost:1339/` and mounts the
same `cloud/main.js`. Needs Node 20+ and a PostgreSQL on port 5433 (`brew install postgresql@17`).

```bash
npm install                                  # parse-server + express (dev dependencies, ~190 MB)
cp .env.example .env                         # APP_ID=anything, random CLIENT_KEY/REST_KEY/MASTER_KEY, SERVER_URL=http://localhost:1339/
npm run dev:server                           # creates the database expense_dev if missing
node scripts/setup.mjs                       # master path (MASTER_KEY is in .env)
psql -p 5433 expense_dev -c 'CREATE UNIQUE INDEX IF NOT EXISTS "AppliedOp_opId_uniq" ON "AppliedOp" ("opId")'
node scripts/check-sync.mjs                  # 19 passed, 0 failed
```

Then point the app at it: `app/src/main/assets/backend.properties` with `appId`, `clientKey` from `.env` and
`serverUrl=http://10.0.2.2:1339/` (the host as seen from the Android emulator). Debug builds allow plain HTTP to
`10.0.2.2`/`localhost` only (`app/src/debug/res/xml/network_security_config.xml`); release builds stay HTTPS-only.
This is how everything in `docs/evidence/emulator-run-2026-10-23.md` was produced.

Users: e-mail/password accounts are created from the app's Login screen (*Create an account instead*); guests are
anonymous Parse users. Each user only ever sees their own rows (object ACL set by the Cloud function).

## The offline sync, in plain words

The app never waits for the network. Every write — add, edit, delete — is one Room transaction that updates the
`entries` table **and** appends an operation to the `ops` table (the outbox). A WorkManager job (`SyncWorker`,
network-constrained, exponential backoff from 30 s) drains the outbox and then pulls. That is the whole design; the rest
is detail:

- **Entries have a client-made identity.** `entryKey` is a UUID generated on the phone, so a new expense exists and can
  be edited before the server has ever heard of it.
- **Every operation has an idempotency key.** Each op carries its own UUID (`opId`). Retries send the *same* `opId`. The
  server keeps an `AppliedOp` row per `opId` behind a unique index: the second arrival fails to insert, and the server
  answers "duplicate opId" with the current state instead of applying twice. Lost responses, double taps, worker restarts
  — all absorbed.
- **Order matters per entry, not globally.** Ops are sent oldest first; if one fails, only later ops of the *same* entry
  wait — other entries keep syncing.
- **Last arrival wins, per field.** An update carries only the fields that changed plus the `version` the phone had seen
  (`baseVersion`). The server applies it regardless and bumps `version`. If `baseVersion` was stale (someone else wrote
  in between) the server still applies it — "last" means *server arrival order* — but records a `Conflict` row, and the
  phone stores it in its `conflicts` table. Nothing is auto-merged, no "resolved" badge is shown; the evidence is kept
  (Settings → Sync lists the most recent ones).
- **Deletions are tombstones.** Delete sets `deletedAt` and bumps `version`; the row stays so other devices learn about
  it on their next pull. A tombstone beats any later edit ("tombstone wins", recorded as a conflict). Locally, a queued
  delete throws away queued edits of the same entry — but keeps a queued create, so the server reaches the tombstone too.
  Tombstones are purged after **90 days** by the `purgeTombstones` job; a device offline longer than that does a full
  pull and drops the clean rows the server no longer returns.
- **Chained offline ops stay conflict-free.** Create → edit → edit of one entry while offline queues three ops whose
  base versions were all taken before anything synced (0, 0, 0). When an op completes with server version *V*, the
  still-queued ops of that entry are rebased to *V* (`SyncPlanner.rebase`), because on one device they were built on
  top of each other. Without that rule the emulator run recorded a "stale baseVersion" conflict for every chained op.
- **Pull reconciles other devices.** `listEntries({since})` returns the caller's entries (tombstones included) with
  `updatedAt >= since`, 500 per page; the phone merges them (`SyncPlanner.apply`) and stores the newest `updatedAt` as
  its cursor. While an entry still has queued ops, the pull keeps the local field values and only adopts the version.

The rules live in one pure-Kotlin object, `sync/SyncPlanner.kt`, and are exercised by `SyncPlannerTest` — ordering,
idempotency-key reuse on retry, tombstone precedence, applying server responses, cursor movement. The server half is
`cloud/main.js`, exercised end to end by `scripts/check-sync.mjs`.

```
 UI ──► EntryRepository ──┬──► Room: entries   ◄─── SyncPlanner.apply ◄──┐
                          └──► Room: ops ──► SyncWorker ──► syncOp ──────┤ Parse Cloud Code
                               Room: conflicts ◄── conflict rows        │ (Entry, AppliedOp, Conflict)
                               cursor (prefs) ◄──► listEntries ──────────┘
```

### Limits, stated plainly

- The unique index on `AppliedOp.opId` is the **only** database-level guarantee. `version` compare-and-increment is
  read-then-write inside the Cloud function; two simultaneous updates of the same entry can both see the same version
  (both are applied, one conflict row may be missing).
- `beforeSave`/`beforeDelete` and the class-level permissions stop client writes; they are validation, not constraints.
- Conflicts are *recorded*, not resolved. The article explains why that is the honest default for a template.
- Categories and the monthly budget are local to the device. They are not synced.
- Guests (anonymous users) cannot sign back in after signing out or reinstalling: their data is unreachable then.

## Repository map

```
app/src/main/java/com/back4app/expensetracker/
  ExpenseApp.kt            Application: Parse.initialize + manual dependency wiring (AppContainer)
  MainActivity.kt          single Activity, Compose content
  backend/                 BackendConfig (where the keys come from), Auth (ParseUser wrappers), ParseSyncGateway (Cloud calls)
  data/                    Room: Entities, Daos, AppDatabase, EntryRepository (writes = row + op), Settings (prefs)
  domain/                  Money (cents, parsing, budget %), Dates
  sync/                    SyncPlanner (the rules, pure), SyncEngine (push then pull), SyncWorker, SyncScheduler, models, codecs
  ui/                      AppNav (routes), ViewModels, screens/ (Login, Entries, EntryForm, Categories, Settings, CategoryBarChart)
app/src/test/              JVM tests: MoneyTest, SyncPlannerTest, FieldsCodecTest, ResponseParserTest
app/src/debug/             debug-only manifest + network security config (cleartext to 10.0.2.2 for the local server)
app/schemas/               Room schema JSON (exported; version 1)
cloud/main.js              Cloud Code: syncOp, listEntries, ensureUser, setupSchema, beforeSave/beforeDelete guards, purgeTombstones job
cloud/config.js            deploy-time switch SETUP_ENABLED (upload next to main.js)
scripts/                   setup.mjs (schema + CLP, via setupSchema or the master key), check-sync.mjs (contract check), dev-server.mjs (local Parse Server), lib.mjs
docs/notes/build-diary.md  every download, install, build and fix step, dated, with sizes
docs/evidence/             build/test logs, the emulator run, the offline matrix, screenshots, cold start
AGENTS.md                  the same information, written for an agent that has to redo it
```

## What is excluded (on purpose)

Recurring expenses, multiple currencies, income, attachments/receipts, charts beyond one bar chart, widgets, biometrics,
password reset UI, account linking of guests to e-mail accounts, multi-user sharing, the Parse Local Datastore (the
outbox is Room, by design), Hilt/Koin, and any conflict-resolution UI.

## What was verified, and what was not

On 2026-10-23 the app ran on the Android emulator (Pixel 6 profile, Android 15, arm64) against a **local** Parse Server
7.5.4 + PostgreSQL, driven with `adb`: guest sign-in, categories, three entries (one with a decimal amount), totals,
budget bar and chart, nine offline operations (create/edit/delete and their chains) drained once after reconnecting,
a second client editing the same account (pull reconciles; a genuine concurrent edit is applied last-arrival-wins and
recorded as a conflict), the server being unreachable for 60 s (worker retry with the same `opId`), and an app kill /
emulator reboot with queued ops. Details, timestamps and screenshots: `docs/evidence/emulator-run-2026-10-23.md` and
`docs/evidence/offline-matrix.md`. Cold start on that emulator: p50 932 ms over 10 runs (debug build — compare only
against itself). Five bugs found by that run are fixed and listed in `docs/notes/build-diary.md` §12.

On the same day the backend side ran against the real Back4app app `expense-tracker` (Parse Server 7.5.2, MongoDB):
`setupSchema` through the REST key, the UNIQUE index in the Index Manager, `check-sync.mjs` 19/19
(`docs/evidence/check-sync-live-2026-10-23.log`) and the same `opId` sent twice and in parallel
(`docs/evidence/idempotency-live-2026-10-23.txt`). That run found one difference between the two servers — the object
in memory does not carry the incremented `version` after `save()` on Back4app — fixed in `cloud/main.js` (diary §15).

**Not verified**: the app itself on a physical device or against the live backend from a phone, session expiry on the
worker path, and the 90-day tombstone purge / full pull (unit-tested rule only).

## Toolchain notes

- Gradle **8.x does not start on JDK 25**, which is what Android Studio 2026.2 bundles; Gradle 9.1+ does. AGP **8.x
  does not run on Gradle ≥ 9.6**. Hence Gradle 9.5.1 + AGP 8.13.2.
- compileSdk is 35; every androidx version in `gradle/libs.versions.toml` was checked to accept it (newer lines require 36).
- Parse Android SDK comes from JitPack (`settings.gradle.kts`).

## License

MIT — see `LICENSE`.
