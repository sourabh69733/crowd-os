# Freegram protocol boundary

The first transport-independent object is a public, signed NIP-01 `kind: 1` text event. Transport adapters carry the exact signed event bytes/fields without editing them. The author key, signature, event ID and content are verified before an event enters the local store or feed. [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md) defines the event shape and canonical ID calculation.

Nearby exchange metadata (connection ID, per-device forwarding budget and acknowledgement) is **not** part of the Nostr event. It is local routing state; a noncompliant peer can copy or republish a public event, so routing limits are abuse controls rather than confidentiality guarantees.

`vectors/nip01-id-v1.json` contains two canonical ID fixtures. They check serialization and SHA-256 only; neither fixture is a signed event. Phase 1 must separately verify signatures against the official [BIP-340 test vectors](https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv), then add a real signed-event fixture produced by the selected maintained library. Do not implement production cryptography from these documents.
