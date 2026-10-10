import jpeg from 'jpeg-js';
import { schnorr } from '@noble/curves/secp256k1.js';

const HASH = /^[0-9a-f]{64}$/;
const MAX_BYTES = 1024 * 1024;
const encoder = new TextEncoder();
const hex = bytes => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
const unhex = value => Uint8Array.from(value.match(/../g), b => parseInt(b, 16));
export async function digest(bytes) { return hex(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))); }
const includes = (list, value) => (list || '').split(',').map(x => x.trim()).includes(value);
function error(status, reason) { return new Response(null, { status, headers: { 'X-Reason': reason } }); }
function cors(response) {
  const headers = new Headers(response.headers);
  headers.set('Access-Control-Allow-Origin', '*');
  headers.set('Access-Control-Allow-Methods', 'GET, HEAD, PUT, OPTIONS');
  headers.set('Access-Control-Allow-Headers', 'Authorization, Content-Type, Content-Length, X-SHA-256, X-Content-Type, X-Content-Length, Range');
  headers.set('Access-Control-Expose-Headers', 'X-Reason, Content-Length, Content-Type, ETag');
  return new Response(response.body, { status: response.status, headers });
}
async function authorize(request, hash) {
  const header = request.headers.get('Authorization') || '';
  if (!/^Nostr [A-Za-z0-9_-]+$/.test(header) || header.length > 8192) return null;
  try {
    const encoded = header.slice(6).replace(/-/g, '+').replace(/_/g, '/');
    const event = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(encoded), c => c.charCodeAt(0))));
    if (!HASH.test(event.id) || !HASH.test(event.pubkey) || !/^[0-9a-f]{128}$/.test(event.sig)) return null;
    const now = Math.floor(Date.now() / 1000);
    if (event.kind !== 24242 || !Number.isSafeInteger(event.created_at) || event.created_at > now || typeof event.content !== 'string' || !event.content.trim()) return null;
    if (!Array.isArray(event.tags) || !event.tags.every(t => Array.isArray(t) && t.every(v => typeof v === 'string'))) return null;
    const tags = name => event.tags.filter(t => t[0] === name).map(t => t[1]);
    const expiry = tags('expiration');
    if (expiry.length !== 1 || !/^\d+$/.test(expiry[0]) || Number(expiry[0]) <= now || Number(expiry[0]) > now + 300) return null;
    if (tags('t').length !== 1 || tags('t')[0] !== 'upload' || !tags('x').includes(hash)) return null;
    const domains = tags('server');
    if (domains.length && !domains.includes(new URL(request.url).hostname)) return null;
    const id = await digest(encoder.encode(JSON.stringify([0, event.pubkey, event.created_at, event.kind, event.tags, event.content])));
    if (id !== event.id || !schnorr.verify(unhex(event.sig), unhex(id), unhex(event.pubkey))) return null;
    return event.pubkey;
  } catch { return null; }
}
async function readBounded(request, size) {
  if (!request.body) return null;
  const reader = request.body.getReader();
  const bytes = new Uint8Array(size);
  let offset = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return offset === size ? bytes : null;
      if (offset + value.length > size) { await reader.cancel(); return null; }
      bytes.set(value, offset); offset += value.length;
    }
  } finally { reader.releaseLock(); }
}
async function handle(request, env) {
  const url = new URL(request.url);
  if (request.method === 'OPTIONS') return new Response(null, { status: 204 });
  if (url.pathname === '/upload' && ['PUT', 'HEAD'].includes(request.method)) {
    const hash = request.headers.get('X-SHA-256') || '';
    if (!HASH.test(hash)) return error(400, 'A lowercase X-SHA-256 is required');
    const key = await authorize(request, hash);
    if (!key) return error(401, 'Invalid upload authorization');
    if (env.UPLOAD_KEYS !== "*" && !includes(env.UPLOAD_KEYS, key)) return error(403, 'Upload key is not approved for this pilot');
    if (includes(env.BLOCKED_HASHES, hash)) return error(403, 'Blocked file');
    if (request.headers.get(request.method === 'HEAD' ? 'X-Content-Type' : 'Content-Type') !== 'image/jpeg') return error(415, 'Only JPEG photos are supported');
    const length = request.headers.get(request.method === 'HEAD' ? 'X-Content-Length' : 'Content-Length');
    const size = Number(length);
    if (!/^\d+$/.test(length || '') || !Number.isSafeInteger(size) || size < 1 || size > MAX_BYTES) return error(413, 'Photo must be at most 1 MiB');
    if (request.method === 'HEAD') return new Response(null, { status: 200 });
    const bytes = await readBounded(request, size);
    if (!bytes || await digest(bytes) !== hash || bytes[0] !== 255 || bytes[1] !== 216 || bytes.at(-2) !== 255 || bytes.at(-1) !== 217) return error(400, 'Invalid JPEG bytes or hash');
    try {
      const image = jpeg.decode(bytes, { useTArray: true, tolerantDecoding: false, maxResolutionInMP: 3, maxMemoryUsageInMB: 32 });
      if (image.width < 1 || image.height < 1 || image.width > 1600 || image.height > 1600) return error(400, 'Photo dimensions exceed 1600 pixels');
    } catch { return error(400, 'Invalid JPEG'); }
    return env.UPLOADS.get(env.UPLOADS.idFromName('photos')).fetch(new Request(request.url, {
      method: 'PUT', headers: { 'X-SHA-256': hash, 'X-Upload-Key': key }, body: bytes,
    }));
  }
  const match = /^\/([0-9a-f]{64})(?:\.jpg)?$/.exec(url.pathname);
  if (match && ['GET', 'HEAD'].includes(request.method)) {
    if (includes(env.BLOCKED_HASHES, match[1])) return error(404, 'Not found');
    const object = await env.MEDIA[request.method === 'HEAD' ? 'head' : 'get'](match[1]);
    if (!object) return error(404, 'Not found');
    const headers = new Headers({ 'Content-Length': String(object.size), ETag: object.httpEtag, 'Cache-Control': 'public, max-age=300', 'X-Content-Type-Options': 'nosniff' });
    object.writeHttpMetadata(headers);
    return new Response(request.method === 'HEAD' ? null : object.body, { headers });
  }
  return error(404, 'Not found');
}
export default { async fetch(request, env) {
  try { return cors(await handle(request, env)); }
  catch { return cors(error(503, 'Media storage unavailable')); }
} };

export class Uploads {
  constructor(ctx, env) { this.ctx = ctx; this.env = env; }
  async fetch(request) {
    return this.ctx.blockConcurrencyWhile(async () => {
      const hash = request.headers.get('X-SHA-256');
      const bytes = await request.arrayBuffer();
      const existing = await this.env.MEDIA.head(hash);
      if (!existing) {
        const reserved = await this.ctx.storage.get(hash);
        if (reserved !== undefined && reserved !== bytes.byteLength) return error(400, 'Wrong reserved size');
        if (reserved === undefined) {
          const total = await this.ctx.storage.get('total') || 0;
          const author = 'author:' + request.headers.get('X-Upload-Key');
          const count = await this.ctx.storage.get(author) || 0;
          if (count >= 100) return error(429, 'Pilot upload limit reached');
          const cap = Number(this.env.MAX_STORAGE_BYTES);
          if (!Number.isSafeInteger(cap) || cap < 1 || total + bytes.byteLength > cap) return error(507, 'Pilot media storage is full');
          // Reserve before writing R2. A crash can overcount but cannot bypass the cap.
          await this.ctx.storage.put({ [hash]: bytes.byteLength, total: total + bytes.byteLength, [author]: count + 1 });
        }
        await this.env.MEDIA.put(hash, bytes, { httpMetadata: { contentType: 'image/jpeg' }, customMetadata: { uploaded: String(Math.floor(Date.now()/1000)), uploader: request.headers.get('X-Upload-Key') } });
      }
      const object = existing || await this.env.MEDIA.head(hash);
      return Response.json({ url: `${new URL(request.url).origin}/${hash}.jpg`, sha256: hash, size: object.size, type: 'image/jpeg', uploaded: Number(object.customMetadata.uploaded) }, { status: existing ? 200 : 201 });
    });
  }
}
