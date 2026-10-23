// scripts/dev-server.mjs · Node >= 20 · local Parse Server 7 with the Postgres adapter and cloud/main.js mounted.
// Lets you run the app and the two scripts without a Back4app account. Mirrors the Back4app settings that matter
// for this template: allowClientClassCreation=false, anonymous users enabled, master key only from localhost.
//
//   cp .env.example .env            # local values: any random strings work as keys; SERVER_URL=http://localhost:1339/
//   npm run dev:server              # → http://localhost:1339/  (database postgres://localhost:5433/expense_dev)
//   node scripts/setup.mjs          # classes, indexes, CLPs — same script as for Back4app
//   psql -p 5433 expense_dev -c 'CREATE UNIQUE INDEX IF NOT EXISTS "AppliedOp_opId_uniq" ON "AppliedOp" ("opId")'
//   node scripts/check-sync.mjs     # contract check
//
// The emulator reaches the host at 10.0.2.2, so the app's backend.properties uses serverUrl=http://10.0.2.2:1339/.
// Needs PostgreSQL listening on 5433 (brew: postgresql@17); the database is created with `createdb` when missing.
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import express from 'express';
import { ParseServer } from 'parse-server';
import { ROOT, loadEnv } from './lib.mjs';

const env = loadEnv();
const PG_BIN = [process.env.PG_BIN, '/opt/homebrew/opt/postgresql@17/bin', '/opt/homebrew/bin', '/usr/local/bin'].filter(Boolean);
const databaseURI = env.DATABASE_URL || 'postgres://localhost:5433/expense_dev';
const port = Number(env.PARSE_PORT) || 1339;
const serverURL = env.SERVER_URL || `http://localhost:${port}/`;
const mountPath = new URL(serverURL).pathname.replace(/\/+$/, '') || '/';

if (!/^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?\//.test(serverURL + '/')) {
  console.error(`SERVER_URL=${serverURL} is not a localhost URL. The dev server is for local use only; point .env at it.`);
  process.exit(2);
}

ensureDatabase(databaseURI);

const parseServer = new ParseServer({
  appId: env.APP_ID,
  masterKey: env.MASTER_KEY,
  clientKey: env.CLIENT_KEY || undefined,
  restAPIKey: env.REST_KEY || undefined,
  serverURL,
  databaseURI,
  cloud: path.join(ROOT, 'cloud', 'main.js'),
  appName: 'android-expense-tracker (local)',
  allowClientClassCreation: false,
  enableAnonymousUsers: true,
  allowExpiredAuthDataToken: false,
  enforcePrivateUsers: true,
  directAccess: true,
  masterKeyIps: ['127.0.0.1', '::1', '::ffff:127.0.0.1'],
  logLevel: process.env.PARSE_LOG_LEVEL || 'info',
  silent: false,
  verbose: process.env.PARSE_VERBOSE === '1',
});

// Listen first: start() probes serverURL over HTTP and warns "Unable to connect" if nothing answers yet.
const app = express();
app.disable('x-powered-by');
app.use((req, _res, next) => { // one line per request, so the diary can show what the app really sent
  console.log(`${new Date().toISOString()} ${req.method} ${req.originalUrl}`);
  next();
});
app.use(mountPath, parseServer.app);
await new Promise((resolve) => app.listen(port, resolve));
await parseServer.start();
console.log(`parse-server ${parseServerVersion()} on ${serverURL} (mount ${mountPath}, db ${databaseURI}, cloud/main.js mounted) — emulator URL http://10.0.2.2:${port}${mountPath === '/' ? '/' : mountPath + '/'}`);

function ensureDatabase(uri) {
  const u = new URL(uri);
  const dbName = u.pathname.replace(/^\//, '');
  const dir = PG_BIN.find((d) => existsSync(path.join(d, 'createdb')));
  if (!dir) { console.warn(`createdb not found in ${PG_BIN.join(', ')} (set PG_BIN) — assuming database ${dbName} exists`); return; }
  const common = ['-h', u.hostname, '-p', u.port || '5432', ...(u.username ? ['-U', u.username] : [])];
  const list = execFileSync(path.join(dir, 'psql'), [...common, '-lqt'], { encoding: 'utf8' });
  if (list.split('\n').some((l) => l.split('|')[0].trim() === dbName)) return;
  execFileSync(path.join(dir, 'createdb'), [...common, dbName], { stdio: 'inherit' });
  console.log(`created database ${dbName}`);
}

function parseServerVersion() {
  try { return createRequire(import.meta.url)('parse-server/package.json').version; } catch { return ''; }
}
