# AGENTS.md — building, checking and extending this repository without a human unsticking you

For an AI agent (or a developer in a hurry). Lists every step, which ones are unavoidably manual, and what to hand back.

## What this is

An Android app (Kotlin, Jetpack Compose, Room, WorkManager, Parse Android SDK) plus the Cloud Code it talks to
(`cloud/main.js`, Parse Server 7 on Back4app) and two Node scripts for the backend (`scripts/`). The point of the
project is the offline-sync design: every write goes to Room and to an `ops` outbox; `SyncWorker` drains the outbox
through the `syncOp` Cloud function and pulls other devices' changes with `listEntries`. Rules are in
`app/src/main/java/com/back4app/expensetracker/sync/SyncPlanner.kt` (pure Kotlin, unit tested).

## Toolchain that is known to work (2026-10-23, macOS arm64)

| Piece | Version | Where |
|---|---|---|
| JDK | 25.0.3 (JetBrains Runtime bundled with Android Studio) | `/Applications/Android Studio.app/Contents/jbr/Contents/Home` → `JAVA_HOME` |
| Android SDK | cmdline-tools 23.0, platform-tools 37.0.1, platforms;android-35, build-tools;35.0.0 | `~/Library/Android/sdk` → `ANDROID_HOME` / `local.properties` `sdk.dir` |
| Gradle | 9.5.1 via wrapper | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 8.13.2 | `gradle/libs.versions.toml` |
| Kotlin / KSP | 2.4.21 / 2.3.12 | same |
| Compose BOM | 2026.01.00 | same |
| Emulator | 37.2.12 + `system-images;android-35;google_apis;arm64-v8a` r9 (AVD `expense35`, pixel_6) | `~/Library/Android/sdk/emulator`, `~/.android/avd` — 5.1 GB extra; recipe in `docs/notes/build-diary.md` §9 |
| Local backend | parse-server 7.5.4 (npm dev dependency) + PostgreSQL 17 on 5433 | `scripts/dev-server.mjs`, database `expense_dev` |

Constraints that decided these numbers — do not "upgrade" blindly:
- Gradle **8.x does not start on JDK 25** (fails with a bare `25.0.3` error); Gradle 9.1+ does.
- AGP **8.x does not work on Gradle ≥ 9.6** (`InternalProblems` removed, see Gradle's `upgrading_version_9` page); 9.5.x is the last one.
- compileSdk is **35**. androidx lines newer than the ones in the catalog (core 1.17, activity 1.11, Compose BOM 2026.02+)
  declare `minCompileSdk=36` in their AAR metadata and fail `checkDebugAarMetadata`. Check the AAR metadata before bumping.

## Commands

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"          # or sdk.dir in local.properties
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # JVM unit tests (no device needed)
./gradlew assembleRelease      # minified; signed with the debug key unless release.* is set in local.properties
npm run dev:server             # LOCAL Parse Server on http://localhost:1339/ (needs .env with local values, Postgres on 5433)
node scripts/setup.mjs         # backend schema + CLP: via the setupSchema Cloud function (REST key) or, with MASTER_KEY in .env, the schema API
node scripts/check-sync.mjs    # backend contract check (creates and deletes a throw-away user; needs MASTER_KEY)
```

Expected: `BUILD SUCCESSFUL` for the three Gradle commands; `19 passed, 0 failed` for `check-sync`.

Emulator (all headless, see `docs/notes/build-diary.md` §9 and `docs/evidence/emulator-run-2026-10-23.md`):

```bash
yes | sdkmanager --sdk_root=$ANDROID_HOME "emulator" "system-images;android-35;google_apis;arm64-v8a"   # ~2.2 GB, 7-8 min
echo no | avdmanager create avd -n expense35 -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_6
emulator -avd expense35 -no-snapshot -no-audio -no-boot-anim -no-window &
adb wait-for-device; until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done   # ~18 s
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n com.back4app.expensetracker.debug/com.back4app.expensetracker.MainActivity   # debug applicationId has the .debug suffix
adb shell cmd connectivity airplane-mode enable|disable      # offline tests
adb exec-out uiautomator dump /dev/tty                         # element bounds for `input tap`; Compose text/contentDescription appear as text/content-desc
adb emu kill                                                   # stop it; kill dev servers by PID (pgrep -f '^node scripts/dev-server.mjs'), never with pkill -f from the tool shell
```

## Steps to a working backend (in order)

| # | Step | Manual? | Where |
|---|---|---|---|
| 1 | Back4app account + app ("Build your Backend") | **yes** | https://www.back4app.com/ |
| 2 | Keys into `.env` (scripts) and `local.properties` (app) | **yes** | App Settings → Security & Keys. App gets Application ID + Client key only. Scripts need APP_ID + REST_KEY; MASTER_KEY only for `check-sync.mjs` (and the master path of `setup.mjs`). |
| 3 | Deploy Cloud Code | **yes** (dashboard) | Cloud Code → Upload → `cloud/main.js` **and** `cloud/config.js` → badge "Files pending deploy (2)" → Deploy. Prove it with `setup.mjs` ("Status now: LIVE") or `curl -X POST .../functions/ensureUser` → `{"code":209,...}`; `{"code":141,...}` means not deployed. A freshly created backend may report "Success" while deploying nothing — reload, make the files dirty, deploy again. |
| 4 | Classes, CLPs | no | `node scripts/setup.mjs` — no MASTER_KEY → `POST /functions/setupSchema` (REST key; master key used inside Cloud Code); MASTER_KEY set → schema API + plain indexes. Idempotent, adds only, never touches indexes. |
| 5 | Close `setupSchema` | **yes** (dashboard) | `cloud/config.js` → `SETUP_ENABLED: false` → Deploy. Verify: `setup.mjs` without MASTER_KEY now prints `setupSchema refused … 119`. |
| 6 | Unique index on `AppliedOp.opId` | **yes** (dashboard) | Database → AppliedOp → Index Manager → `{opId: 1}` UNIQUE. The schema API cannot express UNIQUE and `setupSchema` does not declare indexes on purpose. Without it `check-sync` fails the "duplicate opId" line and retried ops can be applied twice. Locally: `CREATE UNIQUE INDEX … ON "AppliedOp" ("opId")`. |
| 7 | Verify | no | `node scripts/check-sync.mjs` (works on a backend that already has rows: it only counts its own throw-away user's rows) |
| 8 | Schedule `purgeTombstones` | no (REST) or dashboard | `POST /jobs/purgeTombstones` with the master key from any cron, daily |

Steps marked **yes** need a browser session that belongs to the account owner; hand them back with the exact path above.

## Secrets and where they live

- `local.properties` (gitignored): `sdk.dir`, `parse.appId`, `parse.clientKey`, optional `release.*` signing values.
- `app/src/main/assets/backend.properties` (gitignored): runtime alternative for the two keys; the `.example` is committed.
- `.env` (gitignored): `APP_ID`, `REST_KEY`, `MASTER_KEY` (optional), `SERVER_URL` for the Node scripts only; plus
  `CLIENT_KEY`, `DATABASE_URL`, `PARSE_PORT` for the local dev server.
- `cloud/config.js` is committed with `SETUP_ENABLED: true` (needed for a first setup); on a backend with real users it
  must be deployed with `false`.
- Never print, log, commit or paste the master key. `check-sync.mjs` prints a throw-away username, never a password.

## Guarantees and limits (do not overstate them)

- `syncOp` is the only write path. `beforeSave`/`beforeDelete` reject non-master writes on Entry, AppliedOp, Conflict; CLPs
  block them before that. These are validation hooks, not database constraints.
- Idempotency is a **database** guarantee only with the unique index of step 5. Version compare-and-increment is
  read-then-write.
- Conflicts are **recorded** (Conflict class on the server, `conflicts` table on the device), never auto-"resolved" in the UI.
- Tombstones are kept 90 days; a device offline longer does a full pull (`SyncPlanner.needsFullPull`) and drops clean rows the
  server no longer returns.
- `listEntries` pages 500 rows (max 1000); the cursor is inclusive (`updatedAt >= since`), so the newest row is re-sent once.
- Categories and the monthly budget are device-local; they are not synced.
- Verified on the emulator against a local Parse Server (Postgres), not against Back4app (MongoDB): the `DUPLICATE_VALUE`
  mapping of the unique index, TLS and the dashboard steps are still unverified there. See `docs/evidence/README.md`.
- `SyncPlanner.rebase` keeps chained same-device ops conflict-free; a response of `duplicate opId` also rebases to the
  returned version, so a write by another device that landed *between* the original send and the retry is adopted
  without a conflict row (narrow window, documented, not fixed).

## Permission boundaries

- Do not run `scripts/check-sync.mjs` against a backend you did not create for this purpose (it creates and deletes a user).
- Do not push or create remote repositories unless asked.
- From an agent tool shell, stop processes by PID; `pkill -f <pattern>` also matches the shell running it and took
  the emulator and the dev servers down once (diary §12).
- Do not downgrade the security posture to make something work (public CLPs, client writes, master key in the app).
