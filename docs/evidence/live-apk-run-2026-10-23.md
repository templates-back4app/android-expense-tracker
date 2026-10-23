# Release APK against the live backend — 2026-10-23, 14:05–14:07 UTC

Build: `app-release.apk` from commit 00c47c8 (2,083,111 B, SHA-256 in `apk-release-2026-10-23.sha256`), the one on
GitHub Releases v0.1.0, with `parse.appId` / `parse.clientKey` of the Back4app app `expense-tracker` compiled in
(no server URL override: `https://parseapi.back4app.com`, TLS). Emulator `expense35` (Pixel 6 profile, Android 15,
arm64), driven with `adb`.

| Time (UTC) | Step | Outcome |
|---|---|---|
| 14:05 | `adb install -r app-release.apk`, `am start -W` | Success; TotalTime 240 ms (warm process from a previous debug install was gone; this is the minified build) |
| 14:05 | Tap *Continue as guest* | Entries screen after ~5 s; anonymous user `9TVpoEZ77v` created on Back4app |
| 14:06:37 | + → `23.45`, note `live apk test` → Save | row shown at once; "unsynced" counter gone within 6 s |
| 14:06:36 | Settings | Status **up to date**, Pending operations 0, Conflicts 0, Last successful sync 2026-10-23 16:06:36 (device local time, UTC+2) — `screens/07-settings-live-backend.png` |
| 14:07 | Dashboard → Database → Entry | 5 objects, one of them `note = "live apk test"` owned by `9TVpoEZ77v`; AppliedOp 7 rows; Conflict 0 |

What this proves: the shipped APK, with only the Application ID and the Client key, signs in, calls `ensureUser`,
`syncOp` and `listEntries` on Back4app over TLS and lands a row with an owner-only ACL. Still not run: a physical
device, airplane-mode matrix against the live backend (done against the local server only), session expiry.
