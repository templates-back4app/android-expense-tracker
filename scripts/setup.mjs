// scripts/setup.mjs · Node >= 20 · idempotent
// Creates the three classes the app needs — fields, class-level permissions and (master path only) plain indexes —
// then tells you the steps that still need the dashboard (Cloud Code upload, the unique index on AppliedOp.opId)
// and checks whether Cloud Code is live.
//   node scripts/setup.mjs
// Two paths, same result:
//   - MASTER_KEY in .env (local dev server, or an owner who is happy to keep the master key in a gitignored file):
//     the schema API is called directly.
//   - no MASTER_KEY (recommended for Back4app — the key never leaves the dashboard): the Cloud function `setupSchema`
//     is called with the REST key; it applies the same table with the master key inside Cloud Code. Requires
//     cloud/main.js + cloud/config.js deployed with SETUP_ENABLED: true (switch it off afterwards).
import { loadEnv, rest } from './lib.mjs';

const env = loadEnv({ master: false });

const NO_CLIENT_WRITES = { create: {}, update: {}, delete: {}, addField: {}, protectedFields: { '*': [] } };
const AUTH_READ = { find: { requiresAuthentication: true }, get: { requiresAuthentication: true }, count: { requiresAuthentication: true } };
const MASTER_ONLY = { find: {}, get: {}, count: {}, ...NO_CLIENT_WRITES };

const SCHEMAS = {
  // Owner reads through the object ACL set by syncOp; signed-in users may query (they only ever see their own rows);
  // nobody writes from a client — syncOp (master key) is the only write path, and beforeSave enforces it again.
  Entry: {
    fields: {
      entryKey: { type: 'String' }, amountCents: { type: 'Number' }, category: { type: 'String' }, date: { type: 'String' },
      note: { type: 'String' }, version: { type: 'Number' }, deletedAt: { type: 'Date' }, owner: { type: 'Pointer', targetClass: '_User' },
    },
    indexes: { entryKey_owner: { entryKey: 1, owner: 1 }, owner_updatedAt: { owner: 1, updatedAt: 1 }, deletedAt_1: { deletedAt: 1 } },
    classLevelPermissions: { ...AUTH_READ, ...NO_CLIENT_WRITES },
  },
  // Idempotency ledger. Master only. The UNIQUE index on opId cannot be created through the schema API — see below.
  AppliedOp: {
    fields: { opId: { type: 'String' }, op: { type: 'String' }, entryKey: { type: 'String' }, owner: { type: 'Pointer', targetClass: '_User' } },
    indexes: {},
    classLevelPermissions: MASTER_ONLY,
  },
  Conflict: {
    fields: {
      entryKey: { type: 'String' }, opId: { type: 'String' }, baseVersion: { type: 'Number' }, serverVersion: { type: 'Number' },
      fields: { type: 'Object' }, reason: { type: 'String' }, owner: { type: 'Pointer', targetClass: '_User' },
      entry: { type: 'Pointer', targetClass: 'Entry' },
    },
    indexes: { owner_createdAt: { owner: 1, createdAt: 1 } },
    classLevelPermissions: { ...AUTH_READ, ...NO_CLIENT_WRITES },
  },
};

async function upsertSchema(className, spec) {
  let existing = null;
  try { existing = await rest(env, 'GET', `/schemas/${className}`, undefined, { master: true }); }
  catch (e) { if (e.status !== 400 && e.status !== 404) throw e; }
  const body = { className, fields: { ...spec.fields }, classLevelPermissions: spec.classLevelPermissions };
  const newIndexes = {};
  for (const [name, def] of Object.entries(spec.indexes)) if (!existing?.indexes?.[name]) newIndexes[name] = def;
  if (Object.keys(newIndexes).length) body.indexes = newIndexes;
  if (existing) {
    for (const f of Object.keys(body.fields)) if (existing.fields?.[f]) delete body.fields[f];
    await rest(env, 'PUT', `/schemas/${className}`, body, { master: true });
    console.log(`schema ${className}: updated (${Object.keys(body.fields).length} new field(s), ${Object.keys(newIndexes).length} new index(es)); CLP re-applied`);
  } else {
    await rest(env, 'POST', '/schemas', body, { master: true });
    console.log(`schema ${className}: created`);
  }
}

async function cloudCodeStatus() {
  try {
    await rest(env, 'POST', '/functions/ensureUser', {}, {});
    return 'unexpected success (ensureUser should reject anonymous calls)';
  } catch (e) {
    if (e.code === 209) return 'LIVE (ensureUser exists and rejects calls without a session)';
    if (e.code === 141) return 'NOT DEPLOYED (141 Invalid function) — upload cloud/main.js in Dashboard → Cloud Code and Deploy';
    return `unknown (${e.status} ${e.code}: ${e.message})`;
  }
}

async function viaCloudFunction() {
  try {
    const r = (await rest(env, 'POST', '/functions/setupSchema', {}, {})).result;
    for (const c of r.classes) console.log(`schema ${c.className}: ${c.action} (${c.addedFields.length} new field(s): ${c.addedFields.join(', ') || '-'}); CLP applied`);
    console.log(`(via Cloud function setupSchema — ${r.note})`);
  } catch (e) {
    if (e.code === 141) console.error('setupSchema is not deployed (141 Invalid function): upload cloud/main.js and cloud/config.js in Dashboard → Cloud Code and Deploy, then rerun.');
    else if (e.code === 119) console.error(`setupSchema refused: ${e.message} — set SETUP_ENABLED: true in cloud/config.js, redeploy, rerun, then switch it off again.`);
    else console.error(`setupSchema failed: ${e.status || ''} ${e.code || ''} ${e.message}`);
    process.exit(1);
  }
}

if (env.MASTER_KEY) {
  for (const [className, spec] of Object.entries(SCHEMAS)) await upsertSchema(className, spec);
} else {
  console.log('MASTER_KEY not set: applying the schema through the setupSchema Cloud function (REST key).');
  await viaCloudFunction();
}

console.log(`
Manual steps (dashboard, account owner):
  1. Cloud Code → Upload → cloud/main.js → confirm "Files pending deploy (1)" → Deploy.
     Status now: ${await cloudCodeStatus()}
  2. Database → AppliedOp → Index Manager → new index on {opId: 1} with option UNIQUE (name it opId_uniq).
     The schema API can only create non-unique indexes; without UNIQUE a retried op can be applied twice.
  3. Once set up: cloud/config.js → SETUP_ENABLED: false → Deploy again (closes the setupSchema function).
  4. Optional: Cloud Code → Jobs → schedule purgeTombstones (daily) — or POST /jobs/purgeTombstones with the master key.
Then: node scripts/check-sync.mjs   (creates and removes a throw-away user; proves idempotency, LWW, tombstones)`);
