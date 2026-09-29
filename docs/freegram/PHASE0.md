# Freegram Phase 0: first-release contract

Status: design contract, updated 25 September 2026. A buildable Android prototype and protocol tests now exist; relay interoperability, device key behavior and radio delivery still require validation. This is **not** a deployable system. See [status log](STATUS_LOG.md).

## Purpose and scope

An Android user can publish a short public bulletin over the internet. A phone that later loses internet can pass the same signed bulletin to nearby phones; a carrier phone can publish it to internet relays when connectivity returns. The first release does not promise global reach, anonymity, private chat, video over mesh or emergency-service reliability. The native app comes before a website because phones must discover and communicate with nearby phones.

The first user-facing objects are an **unsent local draft** and a **public bulletin**. Once a public bulletin is shared with another device, anyone possessing it may copy it elsewhere. There is no enforceable “nearby only” audience for unencrypted public data. A future private message would need its own recipient-key, encryption, metadata and recovery design.

## Architecture decisions for the pilot

| Concern | Phase 0 decision | Reason and limit |
|---|---|---|
| Public event | NIP-01 `kind: 1` text note, signed by the author's Nostr key | Existing event format can be carried by both nearby peers and relays; relay support must be tested. |
| Identity | Pseudonymous author public key; user-controlled alias is presentation only | Signature identifies a key, not a real person or a truthful claim. Same key across transports permits correlation. |
| Local state | Drafts, verified events, outbox, per-relay acceptance and nearby acceptance stored on device | Network outages and process restarts must not fabricate success. |
| Internet | At least two configurable, independently operated WebSocket relays in the pilot | A relay may reject or drop an event; two URLs do not prove independence if one operator owns both. |
| Nearby | Android native adapter behind an interface; first transport candidate is Nearby Connections | It offers offline Bluetooth/Wi-Fi connectivity, but its Google Play services dependency and device behavior need measurement. |
| Media | None in the first bulletin slice | Images/reels need different bandwidth, storage and moderation treatment. |
| Discovery | Followed keys and explicit shared links first; no global algorithmic feed | Limits scope and avoids implying a complete network index. |
| Moderation | Local block/mute/report and source-key trust indicators | Public relays and peers retain their own policies; signatures do not establish truth. |

Sources: [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md), [NIP-40](https://github.com/nostr-protocol/nips/blob/master/40.md), [Nearby Connections overview](https://developers.google.com/nearby/connections/overview).

## Event contract, version 1

Use the NIP-01 fields `id`, `pubkey`, `created_at`, `kind`, `tags`, `content` and `sig`; compute the ID from canonical serialization and verify the BIP-340 Schnorr signature before local acceptance. An accepted `kind: 1` event has UTF-8 text content of at most **2 KiB**, a whole JSON wire representation of at most **4 KiB**, and at most **8 tags**. These are pilot resource limits, not Nostr-wide rules. A single optional `t` topic tag may assist filtering; it must be a user-selected coarse topic, never an automatically collected GPS position. No `expiration` tag is required in version 1 because relay support and deletion behavior differ. The app displays the post's age and refuses to forward it locally after **48 hours** by default; relay retention is separate and may be indefinite.

Transport metadata, such as local peer connection, acknowledgement and forwarding count, stays outside the signed event. It may change at each hop. A hop or age limit controls our client but cannot stop a malicious peer from republishing a copied public event. Do not treat these limits as security guarantees. A phone with a clock more than ten minutes ahead of the receiver is quarantined for review/retry rather than silently shown as fresh; clock skew and relay rejection need device tests.

The portable serialization fixtures are in [`protocol/vectors/nip01-id-v1.json`](../../protocol/vectors/nip01-id-v1.json). They check NIP-01 ID derivation only. The Android prototype now checks official [BIP-340 vectors 0–14](https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv) (32-byte messages relevant to Nostr IDs) and signing/verification tampering cases with ACINQ secp256k1-kmp. Signed portable event fixtures still need coverage. We will not implement custom cryptography.

## State and exchange contract

Draft state: `local draft`. After explicit publish, the app signs once and stores the immutable event. Delivery state is a set of observations, not one ambiguous “sent” flag:

| Observation | Meaning | Does not mean |
|---|---|---|
| Saved locally | Our database committed the event/outbox entry | Another device has a copy |
| Accepted by peer B | B acknowledged storing the verified event | B will forward it or any other peer received it |
| Accepted by relay R | R returned a successful `OK` for this event ID | All relays or followers have it; R will retain it forever |
| Displayed on device D | D's app rendered a verified event | Every intended reader saw or believed it |

Nearby protocol candidate: `HELLO(version, capabilities)` → bounded recent-ID inventory → request missing IDs → send event → receiver validates and commits → acknowledgement. A peer may disconnect at any step; only a durable receiver commit earns acknowledgement. Inventory exchange leaks which public posts a phone holds, so it must be limited and tested in the privacy review. Pilot guardrails: at most **128 advertised IDs** and **32 requested events** per exchange, plus bounded per-peer rate and total storage. Exact battery/throughput bounds are to be set from device measurements, not assumed from API documentation.

## Test cases and evidence required

1. **Canonicalization:** both JSON fixtures produce exactly their stored serialized bytes and SHA-256 IDs in at least two independent runtimes; Unicode and newline handling match.
2. **Authentication:** accepted signatures verify against official BIP-340 vectors; modified content, ID, signature and malformed tags are rejected before storage or rendering.
3. **Internet:** phone A publishes to two configured relays; B reads from either. Record `OK`/rejection per relay; repeat with each relay disabled.
4. **Persistence:** publish while disconnected, force-stop/restart, reconnect, and confirm the same event ID is sent without duplicates or lost local state.
5. **Nearby:** with internet disabled, A → B → C succeeds while A and C never connect directly. Repeat with different target devices and screen states. Report attempts, successful handoffs and delays, not just one success.
6. **Bridge:** C reconnects and publishes A's unmodified event; D fetches and verifies it. A draft is never published. An old or rejected event stays visibly pending/rejected rather than being marked delivered.
7. **Abuse:** flood, replay, malformed size, full storage and malicious peer tests demonstrate bounded CPU/storage and no invalid event in the feed.
8. **Privacy:** inspect logs, radio discovery identifiers, relay-visible IPs and event tags; document what observers can correlate. Do not claim anonymity.

Each test run records Android version, device model, app version, relay URL/operator, network state, trial count, failure mode and battery measurement method. The controlled A → B → C and bridge tests are prerequisites to a field pilot; a single successful run does not establish reliability. A pilot go/no-go threshold must be chosen with actual target devices and conditions before Phase 6.

## Open validations before Phase 1 implementation

- Select the maintained Kotlin/Java Nostr and BIP-340 implementation; verify its official vectors and license. Android Keystore support for the needed key form must be tested, not assumed. Document backup, loss and revocation limits.
- Select two genuinely independent relays and test whether a carrier can submit an event authored by another key, including relay authentication/policy failures.
- Choose target Android devices and test Nearby Connections availability without internet and, separately, without Google Play services. If the latter fails, plan a native BLE/Wi-Fi alternative before claiming infrastructure independence.
- Decide who runs moderation and source-key verification for the pilot. A valid signature alone must never display an “official organizer” badge.
- Agree on the intended pilot setting and measurable delivery, latency and battery thresholds. These depend on phone density and movement; no city-wide coverage claim is supported by this architecture.

Phase 1 prototyping began with a pinned signing library and explicit key-storage limits. The relay and device checks above remain gates before a field pilot. Phases and model assignments are in [the implementation plan](../FREEGRAM_IMPLEMENTATION_PLAN.md).
