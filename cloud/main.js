// cloud/main.js — Cloud Code for the Android expense tracker (Parse Server 7.x on Back4app, CommonJS, Node 19).
//
// Upload this file and cloud/config.js in Dashboard → Cloud Code (no package.json needed). Four functions, one job:
//
//   syncOp({ opId, entryId, baseVersion, fields, op })  the ONLY write path for Entry (contract below)
//   listEntries({ since, limit })                       pull: entries of the caller updated since a cursor, tombstones included
//   ensureUser()                                        cheap "keys + session work" probe; returns the caller's profile
//   setupSchema()                                       idempotent: classes, fields and CLPs (same as scripts/setup.mjs),
//                                                       with the master key inside Cloud Code; guarded by cloud/config.js
//   job purgeTombstones                                 physically removes tombstones older than TOMBSTONE_RETENTION_DAYS
//
// syncOp contract (identical to the feasibility lab):
//   opId         client-generated UUID of the operation. An AppliedOp row keyed by opId makes retries idempotent —
//                it relies on a UNIQUE index on AppliedOp.opId (create it once in Dashboard → Database → Index Manager;
//                the schema API cannot express UNIQUE). scripts/check-sync.mjs proves the behaviour.
//   entryId      client-generated key of the entry (stored as Entry.entryKey) so creates are idempotent and the client
//                never waits for a server objectId.
//   baseVersion  Entry.version the client saw when it produced the op (update/delete).
//   fields       { amountCents?, category?, date?, note? } for create/update.
//   op           'create' | 'update' | 'delete'
// Returns { applied: boolean, reason?: string, entry: object|null, conflict?: object|null }.
//
// Rules: last-arrival-wins per field; a stale baseVersion is applied anyway but recorded as a Conflict row; delete is a
// tombstone (deletedAt) and wins over later edits; nothing is physically deleted before the retention job runs.
// Limits: the version compare-and-increment is read-then-write (not an atomic DB constraint); the only hard guarantee
// is the unique index on AppliedOp.opId.

const ALLOWED_FIELDS = ['amountCents', 'category', 'date', 'note'];
const OPS = ['create', 'update', 'delete'];
const TOMBSTONE_RETENTION_DAYS = 90;
const LIST_LIMIT_DEFAULT = 500;
const LIST_LIMIT_MAX = 1000;
const M = { useMasterKey: true };

// Deploy-time switches (cloud/config.js). Absent file = defaults.
let CONFIG = { SETUP_ENABLED: true };
try { CONFIG = { ...CONFIG, ...require('./config.js') }; } catch (e) { /* no config.js uploaded: defaults apply */ }

function fail(code, message) {
  throw new Parse.Error(code, message);
}

/** Keep only the four allowed fields and validate their shape. Unknown keys are ignored, bad values rejected. */
function pickFields(raw) {
  const out = {};
  if (!raw || typeof raw !== 'object') return out;
  if (raw.amountCents !== undefined) {
    if (!Number.isInteger(raw.amountCents) || raw.amountCents < 0 || raw.amountCents > 999999999) fail(Parse.Error.VALIDATION_ERROR, 'amountCents must be a non-negative integer (cents).');
    out.amountCents = raw.amountCents;
  }
  if (raw.category !== undefined) {
    if (typeof raw.category !== 'string' || raw.category.length > 64) fail(Parse.Error.VALIDATION_ERROR, 'category must be a string of at most 64 characters.');
    out.category = raw.category;
  }
  if (raw.date !== undefined) {
    if (typeof raw.date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(raw.date)) fail(Parse.Error.VALIDATION_ERROR, 'date must be yyyy-MM-dd.');
    out.date = raw.date;
  }
  if (raw.note !== undefined) {
    if (typeof raw.note !== 'string' || raw.note.length > 500) fail(Parse.Error.VALIDATION_ERROR, 'note must be a string of at most 500 characters.');
    out.note = raw.note;
  }
  return out;
}

/** Plain JSON for the client: no pointers, no ACL, dates as ISO strings. */
function entryJSON(entry) {
  const d = entry.get('deletedAt');
  return {
    entryKey: entry.get('entryKey'),
    amountCents: entry.get('amountCents') ?? 0,
    category: entry.get('category') ?? '',
    date: entry.get('date') ?? '',
    note: entry.get('note') ?? '',
    version: entry.get('version') ?? 0,
    deletedAt: d ? d.toISOString() : null,
    updatedAt: entry.updatedAt ? entry.updatedAt.toISOString() : null,
    createdAt: entry.createdAt ? entry.createdAt.toISOString() : null,
  };
}

async function loadEntry(entryKey, user) {
  const q = new Parse.Query('Entry');
  q.equalTo('entryKey', entryKey);
  q.equalTo('owner', user);
  return q.first(M);
}

async function recordConflict({ entry, entryKey, opId, baseVersion, serverVersion, fields, reason, user }) {
  const c = new Parse.Object('Conflict');
  c.set({ entryKey, opId, baseVersion: baseVersion ?? null, serverVersion, fields, reason, owner: user });
  if (entry) c.set('entry', entry);
  const acl = new Parse.ACL();
  acl.setReadAccess(user, true);
  c.setACL(acl);
  await c.save(null, M);
  return { reason, baseVersion: baseVersion ?? null, serverVersion, opId, conflictId: c.id };
}

function requireUser(req) {
  if (!req.user) fail(Parse.Error.INVALID_SESSION_TOKEN, 'Sign in to sync.');
  return req.user;
}

Parse.Cloud.define('syncOp', async (req) => {
  const user = requireUser(req);
  const { opId, entryId, op } = req.params || {};
  const baseVersion = req.params && Number.isInteger(req.params.baseVersion) ? req.params.baseVersion : null;
  const fields = pickFields(req.params && req.params.fields);
  if (typeof opId !== 'string' || !opId || opId.length > 64) fail(Parse.Error.VALIDATION_ERROR, 'opId (string) is required.');
  if (typeof entryId !== 'string' || !entryId || entryId.length > 64) fail(Parse.Error.VALIDATION_ERROR, 'entryId (string) is required.');
  if (!OPS.includes(op)) fail(Parse.Error.VALIDATION_ERROR, `op must be one of ${OPS.join('|')}.`);

  // 1. Idempotency gate: insert the AppliedOp row FIRST. With a unique index on opId the second insert of the same
  //    opId fails with DUPLICATE_VALUE (137) → answer with the current state and applied:false.
  const gate = new Parse.Object('AppliedOp');
  gate.set({ opId, op, entryKey: entryId, owner: user });
  gate.setACL(new Parse.ACL()); // master only
  try {
    await gate.save(null, M);
  } catch (e) {
    if (e.code === Parse.Error.DUPLICATE_VALUE) {
      const entry = await loadEntry(entryId, user);
      return { applied: false, reason: 'duplicate opId', entry: entry ? entryJSON(entry) : null };
    }
    throw e;
  }

  let entry = await loadEntry(entryId, user);

  if (op === 'create') {
    if (entry) return { applied: false, reason: 'entry exists', entry: entryJSON(entry) };
    if (fields.amountCents === undefined || fields.category === undefined || fields.date === undefined) {
      fail(Parse.Error.VALIDATION_ERROR, 'create requires amountCents, category and date.');
    }
    entry = new Parse.Object('Entry');
    const acl = new Parse.ACL();
    acl.setReadAccess(user, true); // owner reads; nobody writes (writes only through this function)
    entry.setACL(acl);
    entry.set({ entryKey: entryId, owner: user, version: 1, note: '', ...fields });
    await entry.save(null, M);
    return { applied: true, entry: entryJSON(entry) };
  }

  if (!entry) fail(Parse.Error.OBJECT_NOT_FOUND, `Unknown entryId ${entryId}.`);
  const serverVersion = entry.get('version') || 0;

  if (op === 'delete') {
    // Tombstone. Idempotent; a stale baseVersion does not matter — delete always wins.
    if (!entry.get('deletedAt')) {
      entry.set('deletedAt', new Date());
      entry.increment('version');
      await entry.save(null, M);
    }
    return { applied: true, entry: entryJSON(entry) };
  }

  // op === 'update'
  if (entry.get('deletedAt')) {
    const conflict = await recordConflict({ entry, entryKey: entryId, opId, baseVersion, serverVersion, fields, reason: 'tombstone', user });
    return { applied: false, reason: 'tombstone wins', entry: entryJSON(entry), conflict };
  }
  let conflict = null;
  if (baseVersion !== serverVersion) {
    conflict = await recordConflict({ entry, entryKey: entryId, opId, baseVersion, serverVersion, fields, reason: 'stale baseVersion', user });
  }
  for (const [k, v] of Object.entries(fields)) entry.set(k, v); // last-arrival-wins, per field
  entry.increment('version');
  await entry.save(null, M);
  return { applied: true, entry: entryJSON(entry), conflict };
});

// Pull: every entry of the caller whose updatedAt >= since (inclusive, so a cursor equal to the newest row re-sends
// that one row — harmless and simpler than an exclusive bound with millisecond ties). Tombstones are included so other
// devices learn about deletions. Pages of `limit` rows; `more` says whether to call again with the returned cursor.
Parse.Cloud.define('listEntries', async (req) => {
  const user = requireUser(req);
  const params = req.params || {};
  let since = null;
  if (params.since !== undefined && params.since !== null) {
    since = params.since instanceof Date ? params.since : new Date(params.since);
    if (Number.isNaN(since.getTime())) fail(Parse.Error.VALIDATION_ERROR, 'since must be an ISO date string.');
  }
  const limit = Math.min(Math.max(1, Number(params.limit) || LIST_LIMIT_DEFAULT), LIST_LIMIT_MAX);

  const q = new Parse.Query('Entry');
  q.equalTo('owner', user);
  if (since) q.greaterThanOrEqualTo('updatedAt', since);
  q.ascending('updatedAt');
  q.limit(limit + 1);
  const rows = await q.find(M);
  const more = rows.length > limit;
  const page = more ? rows.slice(0, limit) : rows;
  const serverTime = new Date();
  const cursor = page.length ? page[page.length - 1].updatedAt : since || serverTime;
  return {
    entries: page.map(entryJSON),
    cursor: cursor.toISOString(),
    more,
    serverTime: serverTime.toISOString(),
    retentionDays: TOMBSTONE_RETENTION_DAYS,
  };
});

// A round trip the app makes right after sign-in. It proves the Application ID / Client key pair and the session
// token work and that Cloud Code is deployed (an undeployed function answers 141 "Invalid function").
Parse.Cloud.define('ensureUser', async (req) => {
  const user = requireUser(req);
  const countQ = new Parse.Query('Entry');
  countQ.equalTo('owner', user);
  countQ.doesNotExist('deletedAt');
  const authData = user.get('authData') || {};
  return {
    userId: user.id,
    username: user.get('username') || null,
    anonymous: !!authData.anonymous,
    entries: await countQ.count(M),
    serverTime: new Date().toISOString(),
  };
});

// ---- setupSchema ------------------------------------------------------------------------------------------------
// The classes, fields and class-level permissions the app needs. Keep in step with scripts/setup.mjs (same table).
// Indexes are NOT declared here on purpose: the schema API cannot express UNIQUE, and the one index that matters —
// AppliedOp.opId UNIQUE — is created in Dashboard → Database → AppliedOp → Index Manager. (setup.mjs declares the
// plain performance indexes when it runs with the master key; they are optional.)
const NO_CLIENT_WRITES = { create: {}, update: {}, delete: {}, addField: {}, protectedFields: { '*': [] } };
const AUTH_READ = { find: { requiresAuthentication: true }, get: { requiresAuthentication: true }, count: { requiresAuthentication: true } };
const MASTER_ONLY = { find: {}, get: {}, count: {}, ...NO_CLIENT_WRITES };
const SCHEMAS = {
  Entry: {
    fields: {
      entryKey: { type: 'String' }, amountCents: { type: 'Number' }, category: { type: 'String' }, date: { type: 'String' },
      note: { type: 'String' }, version: { type: 'Number' }, deletedAt: { type: 'Date' }, owner: { type: 'Pointer', targetClass: '_User' },
    },
    classLevelPermissions: { ...AUTH_READ, ...NO_CLIENT_WRITES },
  },
  AppliedOp: {
    fields: { opId: { type: 'String' }, op: { type: 'String' }, entryKey: { type: 'String' }, owner: { type: 'Pointer', targetClass: '_User' } },
    classLevelPermissions: MASTER_ONLY,
  },
  Conflict: {
    fields: {
      entryKey: { type: 'String' }, opId: { type: 'String' }, baseVersion: { type: 'Number' }, serverVersion: { type: 'Number' },
      fields: { type: 'Object' }, reason: { type: 'String' }, owner: { type: 'Pointer', targetClass: '_User' },
      entry: { type: 'Pointer', targetClass: 'Entry' },
    },
    classLevelPermissions: { ...AUTH_READ, ...NO_CLIENT_WRITES },
  },
};

function addField(schema, name, def) {
  if (def.type === 'Pointer') schema.addPointer(name, def.targetClass);
  else schema.addField(name, def.type);
}

// Idempotent: creates missing classes, adds missing fields (never removes or retypes one), re-applies the CLPs,
// leaves indexes alone. Runs with the master key because Parse.Schema does so inside Cloud Code; the caller needs
// nothing but the Application ID (+ REST or Client key). Guarded by SETUP_ENABLED in cloud/config.js — set it to
// false and redeploy once the backend is set up.
Parse.Cloud.define('setupSchema', async () => {
  if (!CONFIG.SETUP_ENABLED) fail(Parse.Error.OPERATION_FORBIDDEN, 'setupSchema is disabled (cloud/config.js SETUP_ENABLED=false).');
  const report = [];
  for (const [className, spec] of Object.entries(SCHEMAS)) {
    const schema = new Parse.Schema(className);
    let existing = null;
    try { existing = await schema.get(); } catch (e) { if (e.code !== Parse.Error.INVALID_CLASS_NAME) throw e; }
    const added = [];
    for (const [name, def] of Object.entries(spec.fields)) {
      if (existing && existing.fields && existing.fields[name]) continue;
      addField(schema, name, def);
      added.push(name);
    }
    schema.setCLP(spec.classLevelPermissions);
    if (existing) await schema.update(); else await schema.save();
    report.push({ className, action: existing ? 'updated' : 'created', addedFields: added, clp: 'applied' });
  }
  return { ok: true, classes: report, note: 'Indexes are not managed here: create the UNIQUE index on AppliedOp.opId in Dashboard → Database → Index Manager.' };
});

// Entry/AppliedOp/Conflict are written only by this file (master key). Any direct client write — even if a CLP or ACL
// let it through — is rejected here. Master-key writes (Cloud Code, dashboard) pass.
for (const className of ['Entry', 'AppliedOp', 'Conflict']) {
  Parse.Cloud.beforeSave(className, (req) => {
    if (!req.master) fail(Parse.Error.OPERATION_FORBIDDEN, `${className} is written only through the syncOp Cloud function.`);
  });
  Parse.Cloud.beforeDelete(className, (req) => {
    if (!req.master) fail(Parse.Error.OPERATION_FORBIDDEN, `${className} rows are tombstoned via syncOp, never deleted by clients.`);
  });
}

// Retention: tombstones older than 90 days are removed for good. A device that stayed offline longer than that does a
// full pull (the app checks its cursor age) and drops local rows the server no longer returns. AppliedOp rows are kept:
// they are tiny and they keep very late retries idempotent.
// Schedule: Dashboard → Cloud Code → Jobs, or POST /jobs/purgeTombstones with the master key from any cron.
Parse.Cloud.job('purgeTombstones', async (req) => {
  const cutoff = new Date(Date.now() - TOMBSTONE_RETENTION_DAYS * 24 * 60 * 60 * 1000);
  let removed = 0;
  for (let i = 0; i < 100; i++) {
    const q = new Parse.Query('Entry');
    q.lessThan('deletedAt', cutoff);
    q.limit(500);
    const rows = await q.find(M);
    if (!rows.length) break;
    await Parse.Object.destroyAll(rows, M);
    removed += rows.length;
  }
  const msg = `purgeTombstones: removed ${removed} tombstone(s) deleted before ${cutoff.toISOString()}`;
  if (req.message) req.message(msg);
  return msg;
});
