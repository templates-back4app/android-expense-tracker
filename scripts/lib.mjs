// Shared helpers for the backend scripts: .env loading and a tiny Parse REST client. No dependencies (Node >= 20).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export function loadEnv(file = path.join(ROOT, '.env')) {
  const env = { SERVER_URL: 'https://parseapi.back4app.com', ...process.env };
  if (fs.existsSync(file)) {
    for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
      const m = line.match(/^\s*([A-Z_]+)\s*=\s*(.*?)\s*$/);
      if (m && !(m[1] in process.env)) env[m[1]] = m[2].replace(/^["']|["']$/g, '');
    }
  }
  for (const k of ['APP_ID', 'MASTER_KEY']) {
    if (!env[k]) {
      console.error(`${k} is missing. Copy .env.example to .env and paste the keys from App Settings → Security & Keys.`);
      process.exit(2);
    }
  }
  env.SERVER_URL = env.SERVER_URL.replace(/\/+$/, '');
  return env;
}

export class ParseError extends Error {
  constructor(status, body) {
    super(body && body.error ? body.error : `HTTP ${status}`);
    this.status = status;
    this.code = body && body.code;
    this.body = body;
  }
}

/** rest(env, 'POST', '/functions/syncOp', body, { master: true } | { session: token } | {}) */
export async function rest(env, method, urlPath, body, { master = false, session = null } = {}) {
  const headers = { 'X-Parse-Application-Id': env.APP_ID, 'Content-Type': 'application/json' };
  if (master) headers['X-Parse-Master-Key'] = env.MASTER_KEY;
  else if (env.REST_KEY) headers['X-Parse-REST-API-Key'] = env.REST_KEY;
  if (session) headers['X-Parse-Session-Token'] = session;
  const res = await fetch(env.SERVER_URL + urlPath, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = { error: text }; }
  if (!res.ok) throw new ParseError(res.status, json);
  return json;
}

export function randomPassword() {
  return Array.from(crypto.getRandomValues(new Uint8Array(24)), (b) => 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'[b % 57]).join('');
}
