# Freegram protocol boundary

The first transport-independent object is a public, signed NIP-01 `kind: 1` text event. Transport adapters carry the exact signed event bytes/fields without editing them. The author key, signature, event ID and content are verified before an event enters the local store or feed. [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md) defines the event shape and canonical ID calculation.

Nearby exchange metadata (connection ID, per-device forwarding budget and acknowledgement) is **not** part of the Nostr event. It is local routing state; a noncompliant peer can copy or republish a public event, so routing limits are abuse controls rather than confidentiality guarantees.

`vectors/nip01-id-v1.json` contains two canonical ID fixtures. They check serialization and SHA-256 only; neither fixture is a signed event. `vectors/bip340-test-vectors.csv` is copied from the official [BIP-340 test vectors](https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv); the Android tests run vectors 0–14, whose messages are 32 bytes like Nostr event IDs. A portable signed-event fixture is still needed. Do not implement production cryptography from these documents.

## Nearby exchange, version 1

Each frame is one UTF-8 JSON array of at most 6 KiB. Both peers run the same steps; either may disconnect at any point.

| Step | Frame | Rule |
|---|---|---|
| 1 | `["HELLO", 1, {}]` | Different version: stop. |
| 2 | `["HAVE", [id, …]]` | At most 128 distinct 64-hex IDs, newest first. Only eligible posts (below). |
| 3 | `["WANT", [id, …]]` | At most 32 IDs, all from the peer's `HAVE`; requesting anything else ends the exchange. |
| 4 | `["EVENT", hops, event]` then `["SENT"]` | `hops` is how many nearby transfers the sender's copy made (0 = authored or fetched from a relay). The event is unchanged NIP-01 JSON. |
| 5 | `["ACK", id]` or `["NACK", id, reason]` | `ACK` only after the receiver verified and durably stored the event. |

Eligible to offer: verified `kind: 1`, author not blocked locally, `created_at` within the last 48 hours and at most 10 minutes ahead, `hops` below this phone's selected limit: Short (6), Medium (12, default), or Far (20). Each phone applies its own setting when offering and receiving; the limit is not negotiated or added to any frame. The receiver checks its own limits, rejects unrequested or repeated events, and stores the copy with `hops + 1`. `ACK` means that one peer stored it, not that anyone else received it. Hop and age limits bind compliant clients only; a malicious peer can lie about `hops` or republish elsewhere. The Android implementation is `apps/android/.../nearby/`; it is transport-independent and has only been tested over in-memory links, not radios.

### Photos (capability `{"media":1}` in `HELLO`)

A post's photo is a NIP-92 `imeta` tag with no URL: `["imeta","x <sha256>","m image/jpeg","size <bytes>","dim <w>x<h>"]`, at most 1 MiB and 1600 px per side. After `SENT`/acknowledgements, if both `HELLO`s carry `media: 1`:

| Frame | Rule |
|---|---|
| `["BLOBHAVE", [sha, …]]` | At most 64 hashes, only photos of posts this phone offered in `HAVE`. |
| `["BLOBWANT", [sha, …]]` | At most 8, all from the peer's `BLOBHAVE` and referenced by a post the receiver stores. |
| `["BLOB", sha, size]` then `["CHUNK", sha, index, base64]`… | One photo at a time; chunks in order, at most 16 KiB of data each (frame at most 24 KiB). |
| `["BLOBSENT"]` | No more photos from this side. |
| `["BLOBACK", sha]` / `["BLOBNACK", sha, reason]` | `BLOBACK` only after the bytes hash to `sha`, match the post's size, decode as a JPEG within limits, and are stored. |

### Fast media handoff (capability `{"media":1,"fast":2}`)

HELLO remains version 1. The optional `fast: 2` capability enables the new handoff only when both phones advertise it; older phones keep the existing CHUNK round. This capability does not claim which radio Nearby selects or a measured speed.

After the text ACK/SENT round and BLOBHAVE/BLOBWANT inventories:

| Frame | Rule |
|---|---|
| `["MEDIAREADY", token]` | A fresh random 64-character lowercase hex token reserves this phone's single media window. An empty string declines and both phones use CHUNK instead. No new frames are sent to peers without `fast: 2`. |
| `["MEDIAGO", session]` | Both sides confirm SHA-256 of their tokens sorted lexicographically and concatenated as UTF-8. Equal tokens or mismatched sessions stop the exchange. |

On mutual readiness, finish/drain other cluster exchanges for up to 15 seconds, stop cluster advertising, discovery and endpoints, then reconnect using `P2P_POINT_TO_POINT` with service ID `org.freegram.media.v2`. The smaller token advertises; the larger token discovers. Only the agreed peer token is accepted. Both sides repeat MEDIAGO on the new connection before enabling files. Reconnection is bounded to 30 seconds. Cluster sharing resumes after the media window unless stopped by the user or run-length deadline. A failed handoff retries at a later meeting.

The smaller-token phone sends its requested photos first, then directions reverse:

| Frame/payload | Rule |
|---|---|
| `["FILE", payloadId, sha256, size]` | One file at a time; a unique Nearby payload ID and a hash requested in this exchange, at most 1 MiB. |
| `["FILEREADY", payloadId]` or BLOBNACK | The receiver registers the expected file before granting permission. Unrequested, hidden or mismatched photos are declined. |
| Nearby FILE payload | Send only after FILEREADY. FILE arrival and transfer-success callbacks may arrive in either order. Unexpected or oversized payloads are cancelled and the peer disconnected. |
| BLOBACK / BLOBNACK | ACK only after transfer success, exact size, SHA-256, JPEG and signed dimensions checks, current moderation checks and durable storage. |
| BLOBSENT | This direction has no more photos. |

Keep the existing limit of 8 requested photos per side and 1600 pixels per side. Each file is bounded to 120 seconds and the whole media window to 5 minutes. Temporary files and descriptors are removed on failure, timeout, disconnect or Stop. Transport success alone is not a storage acknowledgement. Signed events and their hop counts are not edited by this handoff.

The v1 CHUNK path sends and receives concurrently, with one outgoing photo waiting for its storage acknowledgement before the next starts. This avoids filling bounded input buffers when both phones have photos. Its frame format is unchanged.
