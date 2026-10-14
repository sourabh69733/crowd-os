import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import jpeg from 'jpeg-js';
import { schnorr } from '@noble/curves/secp256k1.js';
import worker, { Uploads, digest } from '../src/index.js';

const secret = new Uint8Array(32).fill(1);
const pubkey = Buffer.from(schnorr.getPublicKey(secret)).toString('hex');
const bytes = jpeg.encode({ width: 1, height: 1, data: new Uint8Array([255, 0, 0, 255]) }, 70).data;
const hash = await digest(bytes);
async function authorization(overrides = {}) {
  const now = Math.floor(Date.now() / 1000);
  const event = { pubkey, created_at: now - 1, kind: 24242, tags: [['t', 'upload'], ['x', hash], ['server', 'media.freegram.in'], ['expiration', String(now + 60)]], content: 'Upload photo', ...overrides };
  event.id = await digest(new TextEncoder().encode(JSON.stringify([0, event.pubkey, event.created_at, event.kind, event.tags, event.content])));
  event.sig = Buffer.from(schnorr.sign(Buffer.from(event.id, 'hex'), secret)).toString('hex');
  return 'Nostr ' + Buffer.from(JSON.stringify(event)).toString('base64url');
}
function environment(limit = 10000) {
  const objects = new Map(), values = new Map();
  const env = { UPLOAD_KEYS: pubkey, BLOCKED_HASHES: '', MAX_STORAGE_BYTES: String(limit), MEDIA: {
    async head(key) { return objects.get(key); },
    async put(key, body, opts) { objects.set(key, { size: body.byteLength, body, httpMetadata: opts.httpMetadata, customMetadata: opts.customMetadata, httpEtag: '"etag"', writeHttpMetadata(headers) { headers.set('Content-Type', opts.httpMetadata.contentType); } }); },
    async get(key, options) { const object = objects.get(key); if (!object || !options?.range) return object; const {offset,length} = options.range; return {...object,body:object.body.slice(offset,offset+length)}; },
  } };
  const gate = new Uploads({ storage: { async get(k) { return values.get(k); }, async put(k, v) { if (typeof k === "object") { for (const [key, value] of Object.entries(k)) values.set(key, value); } else values.set(k, v); } }, blockConcurrencyWhile: fn => fn() }, env);
  env.UPLOADS = { idFromName: x => x, get: () => gate };
  return env;
}
async function upload(env, auth = authorization(), body = bytes) {
  return worker.fetch(new Request('https://media.freegram.in/upload', { method: 'PUT', headers: { Authorization: await auth, 'X-SHA-256': hash, 'Content-Type': 'image/jpeg', 'Content-Length': String(body.length) }, body }), env);
}
test('verified upload, duplicate, public GET and HEAD preserve bytes and metadata', async () => {
  const env = environment();
  const response = await upload(env);
  assert.equal(response.status, 201);
  assert.equal((await response.json()).url, `https://media.freegram.in/${hash}.jpg`);
  assert.equal((await upload(env)).status, 200);
  const get = await worker.fetch(new Request(`https://media.freegram.in/${hash}.jpg`), env);
  assert.deepEqual(new Uint8Array(await get.arrayBuffer()), new Uint8Array(bytes));
  const head = await worker.fetch(new Request(`https://media.freegram.in/${hash}`, { method: 'HEAD' }), env);
  assert.equal(head.headers.get('Content-Length'), String(bytes.length));
  assert.equal(await head.text(), '');
});
test('reject invalid signature, expired token, wrong domain/hash, unapproved key and corrupted bytes', async () => {
  const env = environment();
  for (const changes of [ { tags: [['t', 'upload'], ['x', hash], ['expiration', '1']] }, { tags: [['t', 'upload'], ['x', hash], ['server', 'wrong.in'], ['expiration', String(Math.floor(Date.now()/1000)+60)]] }, { kind: 1 } ]) {
    assert.equal((await upload(env, authorization(changes))).status, 401);
  }
  const auth = JSON.parse(Buffer.from((await authorization()).slice(6), 'base64url'));
  auth.sig = '0'.repeat(128);
  assert.equal((await upload(env, 'Nostr ' + Buffer.from(JSON.stringify(auth)).toString('base64url'))).status, 401);
  env.UPLOAD_KEYS = '';
  assert.equal((await upload(env)).status, 403);
  env.UPLOAD_KEYS = pubkey;
  assert.equal((await upload(env, authorization(), new Uint8Array([255,216,0,217]))).status, 400);
});
test('quota, blocked hashes, oversized bodies and CORS', async () => {
  assert.equal((await upload(environment(3))).status, 507);
  const env = environment(); env.BLOCKED_HASHES = hash;
  assert.equal((await upload(env)).status, 403);
  assert.equal((await worker.fetch(new Request(`https://media.freegram.in/${hash}`), env)).status, 404);
  const preflight = await worker.fetch(new Request('https://media.freegram.in/upload', { method: 'OPTIONS' }), env);
  assert.equal(preflight.headers.get('Access-Control-Allow-Origin'), '*');
  env.BLOCKED_HASHES = '';
  assert.equal((await upload(env, authorization(), new Uint8Array(1048577))).status, 413);
});

test('standard authenticated upload check and invalid JPEG are rejected before storage', async () => {
  const env = environment();
  const head = await worker.fetch(new Request('https://media.freegram.in/upload', { method: 'HEAD', headers: { Authorization: await authorization(), 'X-SHA-256': hash, 'X-Content-Type': 'image/jpeg', 'X-Content-Length': String(bytes.length) } }), env);
  assert.equal(head.status, 200);
  const preflight = await worker.fetch(new Request('https://media.freegram.in/upload', { method: 'OPTIONS' }), env);
  assert.match(preflight.headers.get('Access-Control-Allow-Headers'), /X-Content-Type/);
  const invalid = new Uint8Array([255,216,0,255,217]);
  const invalidHash = await digest(invalid);
  const auth = await authorization({ tags: [['t','upload'], ['x',invalidHash], ['expiration',String(Math.floor(Date.now()/1000)+60)]] });
  const response = await worker.fetch(new Request('https://media.freegram.in/upload', { method: 'PUT', headers: { Authorization: auth, 'X-SHA-256': invalidHash, 'Content-Type':'image/jpeg', 'Content-Length':String(invalid.length) }, body:invalid }), env);
  assert.equal(response.status, 400);
});

async function uploadBlob(env, body, mime) {
  const fingerprint = await digest(body);
  const now = Math.floor(Date.now()/1000);
  const auth = await authorization({ tags: [['t','upload'],['x',fingerprint],['server','media.freegram.in'],['expiration',String(now+60)]] });
  return worker.fetch(new Request('https://media.freegram.in/upload', { method:'PUT', headers:{ Authorization:auth, 'X-SHA-256':fingerprint, 'Content-Type':mime, 'Content-Length':String(body.length) }, body }),env);
}
test('bounded H264 MP4 and signed manifest uploads preserve type; malformed containers and signatures fail', async () => {
  const env = environment(100000);
  const video = readFileSync(new URL('./fixtures/video.mp4',import.meta.url));
  const fingerprint = await digest(video);
  const upload = await uploadBlob(env,video,'video/mp4');
  assert.equal(upload.status,201);
  assert.equal((await upload.json()).url,`https://media.freegram.in/${fingerprint}.mp4`);
  const tags = [['x',fingerprint],['m','video/mp4'],['size',String(video.length)],['dim','160x90'],['duration','1'],['freegram-piece','262144'],['freegram-pieces',fingerprint]];
  const signed = JSON.parse(Buffer.from((await authorization({kind:1063,content:'',tags})).slice(6),'base64url'));
  const manifest = new TextEncoder().encode(JSON.stringify(signed));
  const stored = await uploadBlob(env,manifest,'application/json');
  assert.equal(stored.status,201);
  assert.equal((await stored.json()).type,'application/json');
  signed.sig = '0'.repeat(128);
  assert.equal((await uploadBlob(env,new TextEncoder().encode(JSON.stringify(signed)),'application/json')).status,400);
  assert.equal((await uploadBlob(env,new Uint8Array([0,0,0,8,109,111,111,118]),'video/mp4')).status,400);
  const head = await worker.fetch(new Request(`https://media.freegram.in/${fingerprint}.mp4`, {method:'HEAD'}),env);
  assert.equal(head.headers.get('Content-Type'),'video/mp4');
});

test('immutable media ranges return exact bytes and invalid ranges fail', async () => {
  const env = environment(100000);
  const video = readFileSync(new URL('./fixtures/video.mp4',import.meta.url));
  assert.equal((await uploadBlob(env,video,'video/mp4')).status,201);
  const hash = await digest(video);
  const partial = await worker.fetch(new Request(`https://media.freegram.in/${hash}.mp4`,{headers:{Range:'bytes=0-99'}}),env);
  assert.equal(partial.status,206);
  assert.equal(partial.headers.get('Content-Range'),`bytes 0-99/${video.length}`);
  assert.deepEqual(new Uint8Array(await partial.arrayBuffer()),new Uint8Array(video.subarray(0,100)));
  const invalid = await worker.fetch(new Request(`https://media.freegram.in/${hash}.mp4`,{headers:{Range:`bytes=0-${video.length}`}}),env);
  assert.equal(invalid.status,416);
});
