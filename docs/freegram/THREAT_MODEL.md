# Freegram Phase 0 threat model

Status: draft for the public-bulletin pilot, 25 September 2026. It covers public text and transport. Private messages, media, organizer command tools and payment systems require separate threat models.

## Assets and trust boundaries

- **Author secret:** controls the pseudonymous public identity. Loss permits impersonation; loss without backup may permanently remove control of that identity.
- **Signed bulletin:** public content. Signature and ID protect authorship/integrity, not truth, secrecy or availability.
- **Phone database/outbox:** contains drafts, public reading history, peers/relay outcomes and possible sensitive metadata. A seized or compromised phone may expose it.
- **Nearby link:** reveals that devices were close enough to exchange. A receiving peer may log presence, inventory and content.
- **Relay/media operators:** can observe connections, reject or remove content, and log IP/network metadata. Independent operators reduce single-point control only when ownership and infrastructure actually differ.

## Threats, proposed controls and residual risk

| Threat | Phase 0/initial control | Residual risk or validation |
|---|---|---|
| Fake author or edited bulletin | Recompute NIP-01 ID and verify BIP-340 signature before storage/display | A stolen author key still signs valid forgeries; key rotation/recovery is unresolved. |
| False rumor or fake “organizer” claim | Never equate valid signature with factual truth; trusted source keys must be verified out of band; report/block UI | Users may still trust a misleading alias or copied screenshot. |
| Flooding by many keys (Sybil) | Per-peer/device resource budgets, bounded event size/inventory, no automatic ranking | Open identity permits cheap new keys; moderation and relay policy remain necessary. |
| Replay and duplicate spread | Event-ID deduplication; local age and forwarding caps; bounded storage | A noncompliant peer can resend or republish public data elsewhere. |
| Radio tracking and presence | Minimal advertised data, explicit nearby mode, measured discovery behavior | Nearby peers or observers can infer physical proximity; rotating identifiers cannot guarantee anonymity. |
| Inventory disclosure | Small exchange inventory; share only eligible public event IDs | A peer learns what the phone holds; stronger private set reconciliation is deferred. |
| Relay censorship/outage | Two configurable independently operated relays; local cache and delayed forwarding | Both can reject; no internet path means no global delivery. |
| Relay/network surveillance | TLS to relays; avoid precise-location tags and unnecessary telemetry | Relay and network operators can still see connection metadata and public content. |
| Seized or malware-infected phone | Protected key storage, device lock guidance, minimal stored personal data, no plaintext secret logs | No app can protect data from a fully compromised/unlocked device. |
| Lost or inaccurate clock | Quarantine extreme future timestamps; show age; test delayed relay submission | Relays may reject old/future events; timestamps are not proof of event chronology. |
| Malicious nearby payload | Bound parse size and nesting; validate before database/UI; fuzz parser and interrupt transfers | Native radio/library defects and denial of service remain possible. |
| Unsafe app binary or update | Signed releases and independent security review before field release | Users can still install an impersonating build; distribution is a separate operational problem. |

## Non-claims

Freegram cannot promise that a bulletin reaches every person, that nearby participants are anonymous, that forwarded public content remains local, that a valid author is trustworthy, or that deletion removes independent copies. The UI and deployment materials must say this plainly. The first pilot must not be described as an emergency service or a verified channel for medical/security instructions.

## Review triggers

Revisit this model before adding private messages, contact lists, precise location, photo/video capture, organizer badges, moderation-provider labels, iOS transport, or user payments. Also revisit it after any field measurement contradicts the assumptions above.
