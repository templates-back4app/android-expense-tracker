# Offline matrix — 2026-10-23

One row per cell, one outcome per cell; no averages. Setup and timestamps: `emulator-run-2026-10-23.md`. "Server" is
the local Parse Server 7.5.4 / PostgreSQL 17 (`scripts/dev-server.mjs`); "device B" is a second client on the same
account made with `POST /loginAs` (master key, local only) + `syncOp` over REST. "Offline" = `cmd connectivity
airplane-mode enable` on the emulator (`Active default network: none`; zero requests reached the server while on).

Expected behaviour is the one documented in README "The offline sync, in plain words". Where the unfixed build
diverged, the cell shows both results.

| Cell | Scenario | Local effect (while offline) | After reconnect: server | After reconnect: app | Outcome |
|---|---|---|---|---|---|
| A | Create a new entry offline (Leisure $20.00) | saved instantly; "1 unsynced"; row marked ● | 1 `syncOp` create → version 1 | row clean, counter 0 | **as expected** |
| B | Edit a synced entry offline (Food $12.34 → $15.00, base v1) | "● $15.00"; counter +1 | update base 1 → version 2, no conflict | shows $15.00 then $16.00 (see F) | **as expected** |
| C | Delete a synced entry offline (Coffee) | row hidden at once; counter +1 | delete → tombstone, version 2; row kept on the server | stays hidden; total excludes it | **as expected** |
| D | Create offline, then edit the same entry offline (Health $30 → $33) | both ops queued (create + update base 0); "● $33.00" | create v1, then update applied → v2 (last arrival wins) — **unfixed build: Conflict "stale baseVersion 0 vs 1" recorded**; fixed build (D″, $5.00 → $6.50): v2, **no conflict** | value right in both; unfixed build showed the conflict in Settings | unfixed: **spurious conflict (bug 2)** · fixed: **as expected** |
| E | Create offline, then delete it offline (Utilities $9.99) | create kept, delete queued (no update to drop); row hidden; counter +2 | create v1 then delete → tombstone v2 (the server learns the entry existed, as designed) | hidden | **as expected** |
| F | Edit offline twice, same entry (Food $15 → $16, both base v1) | two updates queued | 1st: v2; 2nd applied → v3 (LWW) — **unfixed build: Conflict "stale baseVersion 1 vs 2"**; fixed build (F″, Leisure $22 → $23 → $24): v4, v5, **no conflict** | $16.00 / $24.00 | unfixed: **spurious conflict (bug 2)** · fixed: **as expected** |
| G | Edit offline, then delete the same entry offline (Transport $100.50 → $101, then delete) | the queued update is dropped from the outbox when the delete is queued (counter went 4 → 5, not 6) | only the delete reached the server → tombstone v2; amountCents still 10050 | hidden | **as expected** |
| H | Kill the app while offline with 9 queued ops, relaunch (still offline) | after relaunch: "9 unsynced", Settings "Pending operations: 9" — the outbox is in Room, not in memory | — | — | **as expected**. Settings status read "syncing…" on the unfixed build (bug 3); fixed build: "waiting for network / retry" |
| I | Reconnect with 9 queued ops | — | 9 `syncOp` in 130 ms, oldest first; AppliedOp 12 rows = 12 distinct opIds (3 earlier creates + 9); every op applied exactly once; then 1 `listEntries` (3 on the unfixed build, bug 1) | counter 0 in 2 s; rows clean | **as expected** (no duplicates) |
| J1 | Device B edits an entry online (note, base v3); app taps Sync now | — | v4 | row shows "lunch (edited on device B)" after the pull | **as expected** |
| J2 | Concurrent edit: app offline sets $18.00 (base v4); device B sets $17.00 (base v4 → v5); app reconnects | "● $18.00" | app's update base 4 ≠ server 5 → applied anyway (LWW by arrival): **1800, v6**; Conflict row "stale baseVersion 4 vs 5" with `{"amountCents":1800}` | $18.00 kept (it was the last arrival); Settings "Conflicts recorded: 3", the row listed with base v4 vs server v5 | **as expected** (genuine conflict recorded, nothing auto-merged) |
| K | Server unreachable while the network is up (dev server stopped 30 s), create $3.33 | "1 unsynced" | the Parse SDK's in-call retries (≈15 s) rode out the outage; 1 `syncOp`, 1 AppliedOp | counter 0 within 2 s of the server returning | **as expected** |
| K2 | Same with the server down 60 s, create $2.22 | "1 unsynced"; Settings "waiting for network / retry", "Last error: i/o failure"; logcat `op … attempt 1 failed: i/o failure` | worker `Result.retry()` → exponential backoff (30 s) → **same opId** resent at 13:27:05 → applied once, 1 AppliedOp, no conflict | counter 0 six seconds after the server was back | **as expected** |
| L | Emulator restarted (process gone, not just the app) with everything synced | — | — | Room data, categories, budget and the Parse session all present after reboot | **as expected** |

Not covered (needs more than one real device or the real backend): session expiry (209) on the worker path,
tombstone purge + full pull after 90 days (`purgeTombstones` job, `SyncPlanner.needsFullPull` is unit-tested only),
a second physical device with its own clock.
