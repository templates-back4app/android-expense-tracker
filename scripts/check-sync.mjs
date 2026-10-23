// scripts/check-sync.mjs · Node >= 20
// End-to-end check of the sync contract against a real backend, without a device: creates a throw-away user (public
// sign-up), exercises syncOp / listEntries / ensureUser through the REST API with that user's session token, verifies
// the client-write lockdown, then deletes everything it created (rows and user). Prints PASS/FAIL lines.
// With MASTER_KEY in .env the cleanup uses the key directly; without it, the testCleanup Cloud function (guarded by
// SETUP_ENABLED in cloud/config.js) does the same work inside Cloud Code.
//   node scripts/check-sync.mjs
import { loadEnv, rest, randomPassword } from './lib.mjs';

const env = loadEnv({ master: false });
const hasMaster = Boolean(env.MASTER_KEY);
let passed = 0, failed = 0;
function check(name, ok, detail = '') {
  if (ok) passed++; else failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`);
}
const uuid = () => crypto.randomUUID();
// The REST API wraps a Cloud function result as { "result": ... }; unwrap it so checks see the function's own object.
const fn = async (name, params, session) => (await rest(env, 'POST', `/functions/${name}`, params, { session })).result;

const username = `check-sync-${Date.now()}`;
const password = randomPassword();
const user = await rest(env, 'POST', '/users', { username, password });
const session = user.sessionToken;
console.log(`throw-away user ${user.objectId} (${username}) created`);

try {
  // ensureUser
  const me = await fn('ensureUser', {}, session);
  check('ensureUser answers for a signed-in user', me.userId === user.objectId && me.entries === 0, JSON.stringify(me));
  try { await fn('ensureUser', {}); check('ensureUser rejects anonymous calls', false); }
  catch (e) { check('ensureUser rejects anonymous calls', e.code === 209, `${e.code} ${e.message}`); }

  // create + duplicate
  const entryKey = uuid();
  const op1 = uuid();
  const create = { opId: op1, entryId: entryKey, op: 'create', fields: { amountCents: 1234, category: 'Food', date: '2026-10-23', note: 'lunch' } };
  const r1 = await fn('syncOp', create, session);
  check('create applied with version 1', r1.applied === true && r1.entry.version === 1 && r1.entry.amountCents === 1234, JSON.stringify(r1.entry));
  const r1b = await fn('syncOp', create, session);
  check('same opId again → duplicate opId, not applied (needs the UNIQUE index on AppliedOp.opId)',
    r1b.applied === false && r1b.reason === 'duplicate opId' && r1b.entry.version === 1, JSON.stringify({ applied: r1b.applied, reason: r1b.reason, version: r1b.entry?.version }));

  // update with correct base
  const r2 = await fn('syncOp', { opId: uuid(), entryId: entryKey, op: 'update', baseVersion: 1, fields: { amountCents: 1500 } }, session);
  check('update with baseVersion 1 → version 2, no conflict', r2.applied && r2.entry.version === 2 && r2.entry.amountCents === 1500 && !r2.conflict);

  // stale update (another device still on version 1): applied anyway (last arrival wins), conflict recorded
  const r3 = await fn('syncOp', { opId: uuid(), entryId: entryKey, op: 'update', baseVersion: 1, fields: { note: 'stale edit' } }, session);
  check('stale baseVersion → applied (LWW) + conflict recorded', r3.applied && r3.entry.version === 3 && r3.entry.note === 'stale edit' && r3.entry.amountCents === 1500
    && r3.conflict && r3.conflict.reason === 'stale baseVersion' && r3.conflict.serverVersion === 2, JSON.stringify(r3.conflict));

  // validation
  try { await fn('syncOp', { opId: uuid(), entryId: entryKey, op: 'update', baseVersion: 3, fields: { amountCents: 12.5 } }, session); check('non-integer amountCents rejected', false); }
  catch (e) { check('non-integer amountCents rejected (142)', e.code === 142, e.message); }

  // pull
  const pull = await fn('listEntries', {}, session);
  check('listEntries returns the entry with a cursor', pull.entries.some((e) => e.entryKey === entryKey && e.version === 3) && typeof pull.cursor === 'string' && pull.more === false);

  // delete → tombstone, then an update must lose
  const r4 = await fn('syncOp', { opId: uuid(), entryId: entryKey, op: 'delete', baseVersion: 1 }, session);
  check('delete → tombstone with version 4 (stale base ignored)', r4.applied && r4.entry.deletedAt && r4.entry.version === 4);
  const r5 = await fn('syncOp', { opId: uuid(), entryId: entryKey, op: 'update', baseVersion: 4, fields: { note: 'too late' } }, session);
  check('update after delete → tombstone wins, conflict recorded', r5.applied === false && r5.reason === 'tombstone wins' && r5.entry.note === 'stale edit' && r5.conflict?.reason === 'tombstone');
  const r6 = await fn('syncOp', { opId: uuid(), entryId: entryKey, op: 'delete', baseVersion: 4 }, session);
  check('second delete is idempotent (version stays 4)', r6.applied && r6.entry.version === 4);
  const pull2 = await fn('listEntries', { since: pull.cursor }, session);
  check('incremental pull includes the tombstone', pull2.entries.some((e) => e.entryKey === entryKey && e.deletedAt));

  // create requires the mandatory fields
  try { await fn('syncOp', { opId: uuid(), entryId: uuid(), op: 'create', fields: { note: 'no amount' } }, session); check('create without amount/category/date rejected', false); }
  catch (e) { check('create without amount/category/date rejected (142)', e.code === 142, e.message); }

  // client-side writes are locked down
  try { await rest(env, 'POST', '/classes/Entry', { entryKey: uuid(), amountCents: 1 }, { session }); check('direct Entry create rejected', false); }
  catch (e) { check('direct Entry create rejected (CLP 119 or beforeSave)', e.code === 119, `${e.code} ${e.message}`); }
  const entryRows = await rest(env, 'GET', `/classes/Entry?where=${encodeURIComponent(JSON.stringify({ entryKey }))}`, undefined, { session }); // owner read (ACL)
  const entryId = entryRows.results[0]?.objectId;
  try { await rest(env, 'PUT', `/classes/Entry/${entryId}`, { amountCents: 1 }, { session }); check('direct Entry update rejected', false); }
  catch (e) { check('direct Entry update rejected (119)', e.code === 119, `${e.code} ${e.message}`); }
  try { await rest(env, 'DELETE', `/classes/Entry/${entryId}`, undefined, { session }); check('direct Entry delete rejected', false); }
  catch (e) { check('direct Entry delete rejected (119)', e.code === 119, `${e.code} ${e.message}`); }
  try { await rest(env, 'GET', '/classes/AppliedOp', undefined, { session }); check('AppliedOp is not readable by clients', false); }
  catch (e) { check('AppliedOp is not readable by clients (119)', e.code === 119, `${e.code} ${e.message}`); }
  const mine = await rest(env, 'GET', '/classes/Entry', undefined, { session });
  check('the owner can read their own Entry rows (ACL)', mine.results.length === 1 && mine.results[0].entryKey === entryKey);
  const conflicts = await rest(env, 'GET', '/classes/Conflict', undefined, { session });
  check('the owner can read their Conflict rows (2 recorded)', conflicts.results.length === 2, `${conflicts.results.length} rows`);
} catch (e) {
  check('unexpected error', false, `${e.status || ''} ${e.code || ''} ${e.message}`);
} finally {
  // cleanup: rows owned by the throw-away user, then the user — master key here, or testCleanup inside Cloud Code
  if (hasMaster) {
    const owner = { __type: 'Pointer', className: '_User', objectId: user.objectId };
    for (const cls of ['Conflict', 'AppliedOp', 'Entry']) {
      const rows = await rest(env, 'GET', `/classes/${cls}?where=${encodeURIComponent(JSON.stringify({ owner }))}&limit=1000`, undefined, { master: true });
      for (const r of rows.results) await rest(env, 'DELETE', `/classes/${cls}/${r.objectId}`, undefined, { master: true });
      console.log(`cleanup ${cls}: ${rows.results.length} row(s) removed`);
    }
    await rest(env, 'DELETE', `/users/${user.objectId}`, undefined, { master: true });
    console.log(`cleanup: user ${user.objectId} removed`);
  } else {
    const r = await fn('testCleanup', { objectId: user.objectId });
    console.log(`cleanup (testCleanup, no master key): ${JSON.stringify(r.removed)}, user ${user.objectId} removed`);
  }
}
console.log(`${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
