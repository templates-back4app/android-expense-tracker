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
