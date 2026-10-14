# Freegram media

Cloudflare Worker and private R2 bucket for hash-addressed JPEG photos. Public downloads use `https://media.freegram.in/<sha256>.jpg`. R2 credentials stay in the Worker binding.

Supported Blossom endpoints: PUT /upload, HEAD /upload, GET and HEAD /<sha256>[.jpg], OPTIONS. Upload authorization follows BUD-11: verified Nostr kind 24242, upload verb, matching lowercase hash, optional server scope, and expiration within five minutes. JPEG decode is limited to 1 MiB, 1600 pixels per dimension, 3 megapixels and 32 MiB of decoder memory. Responses include CORS headers.

`UPLOAD_KEYS` is a comma-separated list of approved public keys, or `*` for any correctly signed upload. Empty disables uploads. Downloads remain public. `MAX_STORAGE_BYTES` caps reserved storage at 1 GiB by default; each uploading key may add at most 100 new hashes and `MAX_BYTES_PER_KEY` bytes (50 MiB), and all keys together at most `MAX_BYTES_PER_DAY` bytes per UTC day (200 MiB), because new keys are free to create. The singleton Durable Object serializes reservation before R2 writes. Failed writes retain their reservation until the same hash is retried, so failures can overcount usage but cannot bypass the cap. Do not reset this object's storage or write into its bucket externally without reconciling reservations.

`BLOCKED_HASHES` is a comma-separated list of hashes denied for uploads and downloads. Blocking does not delete the R2 object or reclaim quota. There is no automatic retention deletion or public delete/list endpoint in this first slice. GET responses cache for five minutes; policy changes can take that long to reach cached copies. Back up the Durable Object before changing quota accounting.

Run `npm ci && npm test` in this directory with Node 22 or later. Deploy from the repository root with `wrangler deploy --config freegram-media/wrangler.jsonc`. R2 must be enabled in the account. The custom media subdomain does not change the website Worker.

Android signs URLs into new photo posts before signing the post itself. Carriers upload unchanged signed posts' local photos with their own scoped upload authorization. Old URL-free posts can fetch by hash from the configured Freegram server. Downloads must match the signed hash, size and dimensions before local storage. Server upload receipts are local preferences, not signed-event edits. Background runs attempt eight photos per pass and retry bounded failures when connected.

Video, range serving, deletion, replication, production moderation workflow and real-phone end-to-end validation are still pending.
