# Evidence

Everything in this folder is produced by a command in this repository; nothing is typed in by hand. File names carry the
date they were produced. Rerun the commands to refresh them.

| File | Produced by | What it shows |
|---|---|---|
| `assembleDebug-YYYY-MM-DD.log` | `./gradlew assembleDebug --console=plain` | the debug APK builds from the command line with the toolchain in `docs/notes/build-diary.md` |
| `assembleRelease-YYYY-MM-DD.log` | `./gradlew assembleRelease --console=plain` | the minified release APK builds (debug-signing fallback when no keystore is configured) |
| `test-YYYY-MM-DD.log` | `./gradlew test --console=plain` | the JVM unit tests (money math, sync planner, codec, parser) pass |
| `test-results-YYYY-MM-DD/` | copy of `app/build/test-results/testDebugUnitTest/` | per-test JUnit XML (names, durations) |
| `apk-sizes-YYYY-MM-DD.txt` | `ls -l app/build/outputs/apk/*/*.apk` | APK sizes |
| `check-sync-YYYY-MM-DD.log` | `node scripts/check-sync.mjs` | the Cloud Code contract against a real backend: idempotent opId, LWW with conflict rows, tombstone precedence, pull with tombstones, client-write lockdown. **Not produced yet**: needs a Back4app app with `cloud/main.js` deployed and the unique index on `AppliedOp.opId` (see README "Backend"). |

What is **not** here, and why: no screenshots, no on-device run, no instrumented tests. The repository was built on a
machine with the Android SDK command-line tools only (no emulator system image, no device). Anything that needs a
running app — the Login → Entries flow, WorkManager actually draining the outbox, the Canvas chart rendering — was
compiled and type-checked but not executed. The sync *rules* are executed by the JVM tests (`SyncPlannerTest`) and the
server side by `check-sync.mjs`; the glue between them (`SyncEngine`, `EntryRepository`) is not covered by a test.
