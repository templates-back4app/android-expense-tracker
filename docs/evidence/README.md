# Evidence

Everything in this folder is produced by a command in this repository; nothing is typed in by hand. File names carry the
date they were produced. Rerun the commands to refresh them.

| File | Produced by | What it shows |
|---|---|---|
| `assembleDebug-YYYY-MM-DD.log` | `./gradlew assembleDebug --console=plain` | the debug APK builds from the command line with the toolchain in `docs/notes/build-diary.md` |
| `assembleRelease-YYYY-MM-DD.log` | `./gradlew assembleRelease --console=plain` | the minified release APK builds (debug-signing fallback when no keystore is configured) |
| `test-YYYY-MM-DD.log` | `./gradlew test --console=plain` | the JVM unit tests (money math, sync planner, codec, parser) pass |
| `test-results-YYYY-MM-DD/` | copy of `app/build/test-results/testDebugUnitTest/` | per-test JUnit XML (names, durations) |
| `apk-sizes-YYYY-MM-DD.txt` | `ls -l app/build/outputs/apk/*/*.apk` | APK sizes (current build, with the previous build's numbers for comparison) |
| `check-sync-local-YYYY-MM-DD.log` | `node scripts/check-sync.mjs` against `scripts/dev-server.mjs` (local Parse Server 7.5.4 + PostgreSQL) | the Cloud Code contract: idempotent opId (unique index), LWW with conflict rows, tombstone precedence, pull with tombstones, client-write lockdown. 19/19. |
| `check-sync-live-YYYY-MM-DD.log` | `MASTER_KEY= node scripts/check-sync.mjs` with `.env` pointing at the live app `expense-tracker` (parseapi.back4app.com, MongoDB) | the same contract on Back4app, cleanup through `testCleanup`; first run 16/19 (the `increment()` finding, diary §15), second run 19/19 |
| `emulator-run-YYYY-MM-DD.md` | typed from the shell transcript, server request log and `psql` while driving the app with `adb` | every step of the first run on the emulator with timestamps and outcomes; what still needs the real backend |
| `offline-matrix-YYYY-MM-DD.md` → `offline-matrix.md` | same run | one outcome per offline cell (create/edit/delete and their chains, kill-and-relaunch, reconnect, second device, server outage), unfixed vs fixed build where they differ |
| `screens/NN-name.png` | `adb exec-out screencap -p` | login, entries with chart, entries with budget bar, entry form, offline queued state, Settings offline / in sync / after the first drain, categories |
| `idempotency-live-YYYY-MM-DD.txt` | two `syncOp` calls with the same `opId`, sequential and in parallel, against the live Back4app app `expense-tracker` (ordinary session, unique index created in the Index Manager) | the unique index decides: second call / loser answers `duplicate opId`, one row |
| `coldstart-YYYY-MM-DD.txt` | `adb shell am start -W` × 10 after `am force-stop` | `TotalTime` per run; p50 932 ms on the arm64 emulator (debug build — not a device number) |

What is **not** here, and why: no instrumented tests (the flow was driven with `adb`, which is reproducible from the
commands in `emulator-run-2026-10-23.md` but not a test suite), no run of the *app* against the real Back4app backend (only the
Cloud Code side was, see the live logs), no physical device. The run exposed five bugs
that the JVM tests and `check-sync` could not see (`docs/notes/build-diary.md` §12); the glue between the sync rules
and the server (`SyncEngine`, `EntryRepository`) is still only covered by that manual run, not by an automated test.
