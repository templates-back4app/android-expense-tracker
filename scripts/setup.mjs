// scripts/setup.mjs · Node >= 20 · idempotent
// Creates, with the master key, the three classes the app needs on a Back4app backend — fields, indexes and
// class-level permissions — then tells you the two steps that still need the dashboard (Cloud Code upload and
// the unique index on AppliedOp.opId) and checks whether Cloud Code is live.
//   node scripts/setup.mjs
import { loadEnv, rest } from './lib.mjs';

const env = loadEnv();

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

for (const [className, spec] of Object.entries(SCHEMAS)) await upsertSchema(className, spec);

console.log(`
Manual steps (dashboard, account owner):
  1. Cloud Code → Upload → cloud/main.js → confirm "Files pending deploy (1)" → Deploy.
     Status now: ${await cloudCodeStatus()}
  2. Database → AppliedOp → Index Manager → new index on {opId: 1} with option UNIQUE (name it opId_uniq).
     The schema API can only create non-unique indexes; without UNIQUE a retried op can be applied twice.
  3. Optional: Cloud Code → Jobs → schedule purgeTombstones (daily) — or POST /jobs/purgeTombstones with the master key.
Then: node scripts/check-sync.mjs   (creates and removes a throw-away user; proves idempotency, LWW, tombstones)`);
