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
node scripts/setup.mjs         # backend schema + CLP (needs .env with APP_ID, MASTER_KEY)
node scripts/check-sync.mjs    # backend contract check (creates and deletes a throw-away user)
```

Expected: `BUILD SUCCESSFUL` for the three Gradle commands; `N passed, 0 failed` for `check-sync`.

## Steps to a working backend (in order)

| # | Step | Manual? | Where |
|---|---|---|---|
| 1 | Back4app account + app ("Build your Backend") | **yes** | https://www.back4app.com/ |
| 2 | Keys into `.env` (scripts) and `local.properties` (app) | **yes** | App Settings → Security & Keys. App gets Application ID + Client key only. Never the master key. |
| 3 | Classes, indexes, CLPs | no | `node scripts/setup.mjs` |
| 4 | Deploy Cloud Code | **yes** (dashboard) | Cloud Code → Upload → `cloud/main.js` → badge "Files pending deploy (1)" → Deploy. Prove it with `setup.mjs` ("Status now: LIVE") or `curl -X POST .../functions/ensureUser` → `{"code":209,...}`; `{"code":141,...}` means not deployed. A freshly created backend may report "Success" while deploying nothing — reload, make the file dirty, deploy again. |
| 5 | Unique index on `AppliedOp.opId` | **yes** (dashboard) | Database → AppliedOp → Index Manager → `{opId: 1}` UNIQUE. The schema API cannot express UNIQUE. Without it `check-sync` fails the "duplicate opId" line and retried ops can be applied twice. |
| 6 | Verify | no | `node scripts/check-sync.mjs` |
| 7 | Schedule `purgeTombstones` | no (REST) or dashboard | `POST /jobs/purgeTombstones` with the master key from any cron, daily |

Steps marked **yes** need a browser session that belongs to the account owner; hand them back with the exact path above.

## Secrets and where they live

- `local.properties` (gitignored): `sdk.dir`, `parse.appId`, `parse.clientKey`, optional `release.*` signing values.
- `app/src/main/assets/backend.properties` (gitignored): runtime alternative for the two keys; the `.example` is committed.
- `.env` (gitignored): `APP_ID`, `REST_KEY`, `MASTER_KEY`, `SERVER_URL` for the Node scripts only.
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
- Nothing here was run on a device or emulator (none installed). See `docs/evidence/README.md`.

## Permission boundaries

- Do not run `scripts/check-sync.mjs` against a backend you did not create for this purpose (it creates and deletes a user).
- Do not push or create remote repositories unless asked.
- Do not downgrade the security posture to make something work (public CLPs, client writes, master key in the app).
