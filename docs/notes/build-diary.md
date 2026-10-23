# Build diary — 2026-10-23

Machine: MacBook, Apple Silicon (arm64), macOS 26.5.2 (Darwin 25.5.0). Android Studio 2026.2.1 (build
AI-262.9437.185.2621.16467767) freshly installed with Homebrew at `/Applications/Android Studio.app`; never opened.
No system JDK (`/usr/bin/java` is the macOS stub), no Android SDK, no Gradle. Node v25.8.1 was present (used only for
the backend scripts). All times UTC.

## 1. JDK — the one bundled with Android Studio (08:47)

```
$ /Applications/Android\ Studio.app/Contents/jbr/Contents/Home/bin/java -version
openjdk version "25.0.3" 2026-04-21
OpenJDK Runtime Environment (build 25.0.3+-15898627-b508.16)
```

`JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home` for everything below. Nothing downloaded.
Consequence discovered later (step 5): Gradle 8.x cannot run on this JDK.

## 2. Android SDK command-line tools (08:49–08:51)

Picked from the official index `https://dl.google.com/android/repository/repository2-3.xml` (fetched 08:49, 423,137 B):
the newest `cmdline-tools` is **23.0**, archive `commandlinetools-mac_arm64-16111833_latest.zip`
(155,384,151 B, SHA-1 `ad03dc49bfacfd52c110b14104ea548b8a07e830` per the index). Since 23.0 Google ships separate
`mac_arm64` and `mac_x86_64` zips; the older `commandlinetools-mac-*` names are universal builds.

```
$ curl -sS -o commandlinetools-mac_arm64-16111833_latest.zip https://dl.google.com/android/repository/commandlinetools-mac_arm64-16111833_latest.zip
  34.0 s · 155,384,151 B · shasum -a 1 = ad03dc49bfacfd52c110b14104ea548b8a07e830  (matches the index)
$ unzip -q commandlinetools-mac_arm64-16111833_latest.zip      # extracts a folder named cmdline-tools/
$ mkdir -p ~/Library/Android/sdk/cmdline-tools
$ mv cmdline-tools ~/Library/Android/sdk/cmdline-tools/latest   # the layout sdkmanager insists on
```

`~/Library/Android/sdk/cmdline-tools/latest` = 172 MB on disk (`source.properties`: `Pkg.Revision=23.0`).
`ANDROID_HOME=$HOME/Library/Android/sdk`.

## 3. Licenses and the minimal SDK set (08:51–08:52)

```
$ yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME --licenses
Warning: The --licenses option is no longer needed.
$ $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Two things worth knowing about cmdline-tools 23.0: `sdkmanager` prints "deprecated — Android CLI will be used
instead" (the new binary is `android`, `android sdk …`), and it accepts licenses by itself (it also announces usage
metrics; `--no-metrics` disables them). The install took 58 s:

| Package | Revision | Archive (from the index) | Download | On disk |
|---|---|---|---|---|
| platform-tools | 37.0.1 | `platform-tools_r37.0.1-darwin.zip` | 16,110,554 B | 37 MB |
| platforms;android-35 | 2 | `platform-35_r02.zip` | 64,273,788 B | 130 MB |
| build-tools;35.0.0 | 35.0.0 | `build-tools_r35_macosx.zip` | 76,857,898 B | 186 MB |
| cmdline-tools;latest | 23.0 | (step 2) | 155,384,151 B | 172 MB |

Total `~/Library/Android/sdk`: **526 MB**. No system images, no emulator, no NDK — on purpose. (AGP later warns
`Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so` — stripping needs
the NDK; the unstripped `.so` is simply packaged. Harmless.)

## 4. Picking versions that fit compileSdk 35 (08:54–08:57)

Fetched `maven-metadata.xml` for every library (Google Maven / Maven Central / JitPack). Newest at the time: AGP
9.6.0-alpha01 (stable 8.x line ends at **8.13.2**), Kotlin 2.4.21 (2.5.0-Beta2 pre-release), KSP 2.3.12, Compose BOM
2026.09.00, Room 2.8.5, WorkManager 2.12.0, Navigation 2.10.2, activity 1.13.0, core 1.19.1, Parse-SDK-Android 4.4.0.

Not all of them accept compileSdk 35. Checked by downloading the AARs and reading
`META-INF/com/android/build/gradle/aar-metadata.properties`:

| Artifact | minCompileSdk |
|---|---|
| androidx.core:core-ktx 1.16.0 / **1.17.0** | 35 / **36** |
| androidx.activity:activity-compose 1.10.1 / **1.11.0**, **1.12.0** | 35 / **36** |
| androidx.compose.ui:ui-android 1.8.3, 1.9.1, 1.10.1 | 35 |
| androidx.compose.material3:material3-android 1.3.2 / 1.4.0 | 34 / 35 |
| androidx.navigation:navigation-compose-android 2.9.6 | 35 |
| androidx.lifecycle:*-compose-android 2.9.4 | 35 |
| androidx.room:room-runtime-android 2.7.2 / 2.8.4 | 34 / 34 |
| androidx.work:work-runtime 2.10.5 / 2.11.2 | 35 |

Chosen (in `gradle/libs.versions.toml`): AGP 8.13.2, Kotlin 2.4.21, KSP 2.3.12, Compose BOM 2026.01.00 (ui 1.10.1,
material3 1.4.0), material-icons-core 1.7.8 (material3 1.4 no longer pulls it transitively — first compile failed on
`Unresolved reference 'icons'`), activity-compose 1.10.1, navigation 2.9.6, lifecycle 2.9.4, Room 2.8.4, WorkManager
2.11.2, core-ktx 1.16.0, coroutines 1.10.2, kotlinx-serialization-json 1.9.0, Parse 4.4.0 (JitPack; depends on
`bolts-tasks` 4.4.0, okhttp 4.10.0).

## 5. Gradle: 8.x refuses JDK 25, AGP 8.x refuses Gradle 9.6+ (08:53, 09:06–09:16)

The brief asked for Gradle 8.x. Test with a throw-away project and the wrapper from the Gradle repo (tag v8.14.6):

```
$ ./gradlew --version          # launcher OK: Gradle 8.14.6, Launcher JVM 25.0.3
$ ./gradlew -q hello
FAILURE: Build failed with an exception.
* What went wrong:
25.0.3
```

That bare "25.0.3" is Gradle's way of saying the daemon JVM is unsupported (8.14 supports up to Java 24). With no other
JDK on the machine and the brief saying to use the bundled one, the choice was Gradle 9.x (Java 25 support arrived in
9.1.0).

First attempt: Gradle **9.8.2** (newest) + AGP 8.13.2. Distribution download 165 MB (35 s). Configuration failed:

```
Plugin 'com.android.internal.application' relies on 'org.gradle.api.problems.internal.InternalProblems', a Gradle
internal API that was removed in Gradle 9.6.0. Update the plugin … or use Gradle 9.5.
```

Second attempt: Gradle **9.5.1** (151 MB, in `~/.gradle/wrapper/dists/gradle-9.5.1-bin`). Works. The wrapper files
(`gradlew`, `gradlew.bat`, `gradle-wrapper.jar` 48,749 B) were taken from `github.com/gradle/gradle` at tag v9.8.2 —
the wrapper jar is version-independent and only the `distributionUrl` matters.

Window that works today: **JDK 25 → Gradle 9.1–9.5 → AGP 8.x**. Moving to AGP 9 would lift the Gradle ceiling but AGP
9 changes the Kotlin/Compose plugin wiring; out of scope for this template.

## 6. Builds (09:09–09:20)

| Run | Result | Wall time | Note |
|---|---|---|---|
| `assembleDebug` #1 (Gradle 9.8.2) | FAILED at configuration | 51 s | AGP 8.x vs Gradle 9.6+ (above) |
| `assembleDebug` #2 (Gradle 9.5.1) | FAILED at `compileDebugKotlin` | 3 m 26 s incl. all dependency downloads | missing `material-icons-core`; a stray import of `matchParentSize` |
| `assembleDebug testDebugUnitTest` #3 | APK built; 3 of 28 tests failed | 34 s | two wrong epoch constants in `ResponseParserTest`; `Money.parseCents("12,34")` accepted a decimal comma as a thousands separator — the parser now accepts commas only in groups of three |
| `test assembleDebug assembleRelease` #4 | BUILD SUCCESSFUL | 1 m 5 s | 28 tests, 0 failed |

Warnings seen and left alone: `SDK processing. This version only understands SDK XML versions up to 3 but an SDK XML
file of version 4 was encountered` (AGP 8.13 vs cmdline-tools 23 — cosmetic), `Deprecated Gradle features were used …
incompatible with Gradle 10` (from AGP/KGP), two `Icons.Filled.KeyboardArrow*` deprecations (switched to AutoMirrored).

Outputs (also in `docs/evidence/apk-sizes-2026-10-23.txt`):

```
app/build/outputs/apk/debug/app-debug.apk       13,846,018 B   (debug, not minified; 35,010,978 B uncompressed, 152 files)
app/build/outputs/apk/release/app-release.apk    2,082,828 B   (R8 minified + resource shrinking, signed with the debug key; 3,892,387 B uncompressed, 137 files)
app/schemas/com.back4app.expensetracker.data.AppDatabase/1.json   Room schema export (KSP)
```

Gradle user home after everything: `~/.gradle` = 2.5 GB, of which `caches` 2.1 GB (dependencies, transforms, build
cache) and `wrapper/dists`: gradle-8.14.6-bin 148 MB (the JDK-25 test, step 5), gradle-9.8.2-bin 165 MB (first
attempt), gradle-9.5.1-bin 151 MB (the one in use). The first two can be deleted.

## 7. Evidence captured (09:20–09:25)

`./gradlew clean`, then `assembleDebug`, `test`, `assembleRelease` once more with plain console output redirected to
`docs/evidence/*-2026-10-23.log`; JUnit XML copied to `docs/evidence/test-results-2026-10-23/`. The first capture came
back `FROM-CACHE` in about one second per command (`org.gradle.caching=true` in `gradle.properties`), which proves
nothing, so it was redone with `--no-build-cache --no-configuration-cache` — those are the logs in the folder
(warm daemon: assembleDebug 5 s, test 4 s, assembleRelease 20 s; 28 tests, 0 failed).

## 8. Not done

- No emulator or device: the app was never launched. `docs/evidence/README.md` lists what that leaves unverified.
- No Back4app app was created for this repository: `scripts/setup.mjs` and `scripts/check-sync.mjs` are syntax-checked
  (`node --check`) and follow the same REST calls as the sibling cashflow-tracker scripts, but were not run against a
  live backend. The Cloud Code `syncOp` is the feasibility-lab function (which *was* exercised there) with the four
  expense fields and plain-JSON responses.
- Nothing pushed; no GitHub repository created.

---

# Build diary, part 2 — emulator run against a local Parse Server (2026-10-23, 12:50–13:30 UTC)

Same machine as part 1. Goal: run the app for the first time, against a *local* Parse Server 7.5.4 (no Back4app
account was available), exercise the whole flow with `adb`, capture screenshots and record the offline matrix.
Outcomes per step are in `docs/evidence/emulator-run-2026-10-23.md`; per-cell results in
`docs/evidence/offline-matrix.md`. This section is about what had to be installed, built and fixed.

## 9. Emulator and system image (12:51–12:59)

```
$ yes | sdkmanager --sdk_root=$ANDROID_HOME "emulator" "system-images;android-35;google_apis;arm64-v8a"
```

`--no-metrics` is not accepted by cmdline-tools 23.0 (`Unknown option`), despite what the deprecation banner suggests;
the plain invocation works and accepts the licenses itself. Wall time **7 m 29 s** (12:51:28 → 12:58:57), both
archives downloaded in parallel, then unzipped:

| Package | Revision | Archive | Download (`Content-Length`) | On disk |
|---|---|---|---|---|
| emulator | 37.2.12 (build 16428233) | `emulator-darwin_aarch64-16428233.zip` | 416,112,708 B | 1,250 MB |
| system-images;android-35;google_apis;arm64-v8a | 9 | `sys-img/google_apis/arm64-v8a-35_r09.zip` | 1,778,933,980 B | 3,884 MB |

`~/Library/Android/sdk` grew from 526 MB to **5,660 MB**. The `google_apis` image (not `google_apis_playstore`) is
enough: no Play Services are needed, and it allows `adb root` if ever required.

```
$ echo no | avdmanager create avd -n expense35 -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_6
$ emulator -avd expense35 -no-snapshot -no-audio -no-boot-anim -no-window &      # headless
$ adb wait-for-device; until [ "$(adb shell getprop sys.boot_completed)" = 1 ]; do sleep 2; done
```

Pixel 6 profile: 1080×2400 @ 420 dpi, 2 GB RAM in `config.ini` (the emulator raised it to 2,560 MB on its own).
Cold boot to `sys.boot_completed=1`: **18 s** (12:59:28 → 12:59:46); a second boot later in the session took 14 s.
Android 15 (API 35), `sdk_gphone64_arm64`. The app, its Room database and the Parse session survived an emulator
restart (`-no-snapshot` only skips the QEMU snapshot; the userdata image persists).

## 10. Local backend: parse-server 7.5.4 + PostgreSQL 17 (12:53–12:56)

`npm install --save-dev parse-server@7.5.4 express@^5.1.0` — 31 s, 692 packages, 193 MB in `node_modules/`
(`npm audit` reports 29 advisories in that tree, all in server-side dev dependencies; nothing of it ships in the app).
`scripts/dev-server.mjs` is the same pattern as the sibling starters: Postgres adapter on `localhost:5433`,
`createdb expense_dev` when missing, `cloud/main.js` mounted, `allowClientClassCreation: false`, anonymous users on,
master key accepted from localhost only, one log line per request so the diary can show what the app really sent.
`SERVER_URL=http://localhost:1339/` mounts Parse at the root, which is the URL shape the emulator uses
(`http://10.0.2.2:1339/` in `app/src/main/assets/backend.properties`, gitignored).

```
$ node scripts/setup.mjs                      # 3 classes created; "Status now: LIVE"
$ psql -p 5433 expense_dev -c 'CREATE UNIQUE INDEX IF NOT EXISTS "AppliedOp_opId_uniq" ON "AppliedOp" ("opId")'
$ node scripts/check-sync.mjs                 # 19 passed, 0 failed  (docs/evidence/check-sync-local-2026-10-23.log)
```

The unique index is the one thing the schema API cannot create; locally it is one SQL statement, on Back4app it is
Dashboard → Database → `AppliedOp` → Index Manager → `{opId: 1}` UNIQUE. Postgres maps the violation (`23505`) to
Parse error 137 `DUPLICATE_VALUE`, exactly what `syncOp` expects; `check-sync` proves it ("same opId again →
duplicate opId").

Bug found on the first run of `check-sync.mjs` (it had only ever been syntax-checked): the REST API wraps a Cloud
function's return value as `{"result": …}` and the script compared the envelope — 6 of 8 checks failed with
`FAIL create applied with version 1`. One line (`fn` now returns `.result`); after that 19/19.

## 11. Pointing the debug build at 10.0.2.2 (12:56)

Android 9+ blocks cleartext HTTP by default, and the local server has no TLS. Added, **debug source set only**:
`app/src/debug/res/xml/network_security_config.xml` (cleartext permitted for `10.0.2.2`, `localhost`, `127.0.0.1`,
everything else stays HTTPS-only) and `app/src/debug/AndroidManifest.xml` that references it. Release builds do not
contain the file (`apkanalyzer manifest print app-release.apk` shows no `networkSecurityConfig`). `local.properties`
gained an optional `parse.serverUrl` (→ `BuildConfig.PARSE_SERVER_URL`); the assets file already had `serverUrl`.
`assembleDebug` with these changes: 13 s (warm daemon), APK 14,325,116 B; `adb install -r`: < 1 s.

## 12. Bugs found by running the app (13:00–13:25)

Everything below was invisible to `assembleDebug`, `test` and `check-sync`; it took the emulator.

1. **Every sync run pulled three times.** The dev-server log showed `syncOp` followed by three `listEntries` for a
   single write. `SyncEngine.run()` used `repeat(MAX_PASSES) { …; if (nothing left) return@repeat }` — `return@repeat`
   only ends the current iteration, so all three passes always ran. Now a `for` loop with `break`. After the fix: one
   `listEntries` per run (log at 13:14:23).
2. **Spurious conflicts for chained offline ops.** Offline create → edit of the same entry, or edit → edit, produced a
   `Conflict` row ("stale baseVersion", base v0 vs server v1 / base v1 vs server v2) although no other device wrote. The
   second op carried the base version the device had seen *before* the chain (0 for an unsynced create). Fix:
   `SyncPlanner.rebase()` — when an op completes with server version V, every still-queued op of the same entry is
   rebased to V — applied to the rows (`OpDao.rebase`, in `EntryRepository.completeOp`) **and** to the list
   `SyncEngine.push()` holds in memory. The first attempt fixed only the rows and the engine kept sending the stale
   in-memory copy (conflicts 3 → 5 at 13:20:58); the second attempt (13:22) fixed both: conflicts stayed at 5 through
   create+edit (v2) and edit+edit (v3 → v5). Unit test added (`later ops of the same entry are rebased…`). A genuine
   concurrent edit from another device is still recorded (cell J2 in the matrix: base v4 vs server v5).
3. **Settings said "syncing…" while offline.** `observeSyncing()` counted WorkManager's `ENQUEUED` state, which also
   means "constraints not met" (airplane mode) and "backoff". Now only `RUNNING`; offline shows "waiting for network /
   retry" (screenshot `04b-settings-offline.png`).
4. **Category picker invisible to accessibility.** The transparent clickable `Box` over the read-only category field
   had no semantics, so `uiautomator dump` (and TalkBack) saw neither the label nor the value. The overlay now carries
   `contentDescription = "Category: <name>"`, `Role.DropdownList` and an `onClickLabel`.
5. **`cloud/main.js` loaded as ESM locally.** The root `package.json` has `"type": "module"` (for the scripts), so
   parse-server `import()`ed `cloud/main.js` as an ES module where `require` is undefined — the new
   `require('./config.js')` threw and the `SETUP_ENABLED` guard silently fell back to its default (observed: the
   function still answered with `SETUP_ENABLED: false`). `cloud/package.json` with `"type": "commonjs"` makes the
   folder CommonJS, which is what Back4app runs anyway. Guard verified afterwards: `119 setupSchema is disabled`.

Operational note, for the next agent: `pkill -f '<pattern>'` from the tool shell matched the shell's own command line
(it contains the pattern text) and killed the emulator and both dev servers with it (13:14:51). Kill by PID
(`pgrep -f '^node scripts/dev-server.mjs'`, `adb emu kill`) instead.

## 13. `setupSchema` Cloud function (13:11–13:19)

Added at the coordinator's request so a Back4app backend can be set up without the master key ever leaving the
dashboard: `setupSchema()` applies the same classes/fields/CLPs as `scripts/setup.mjs` with `Parse.Schema` (master
key inside Cloud Code), adds only missing fields, never touches indexes, and is guarded by `SETUP_ENABLED` in
`cloud/config.js`. `scripts/setup.mjs` uses it when `MASTER_KEY` is absent (REST key only). Verified on a second local
server with an empty database (`PARSE_PORT=1340 … DATABASE_URL=…/expense_setup_test`): first run "created (8/4/8
fields)", second run "updated (0 new fields)", CLPs byte-identical to the master path, then `check-sync.mjs` 19/19
against it; with the flag off: `119 setupSchema is disabled`.

## 14. Final builds (13:22)

`./gradlew test assembleDebug assembleRelease`: 28 s, 29 tests, 0 failed. `app-debug.apk` 14,325,116 B,
`app-release.apk` 2,083,111 B (+283 B vs the 2026-10-23 morning build: the semantics strings and the rebase code).
