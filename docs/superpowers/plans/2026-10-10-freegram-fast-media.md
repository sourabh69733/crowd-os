# Freegram fast media implementation plan

> Use superpowers:executing-plans inline. The user explicitly requested implementation without further planning approval.

Goal: implement the approved point-to-point photo handoff while retaining v1 exchanges.
Architecture: NearbyExchange negotiates capabilities and inventories; a bounded coordinator reserves one handoff; NearbySharing changes strategy; a file adapter and photo exchange validate and store requested photos.
Spec: ../specs/2026-10-10-freegram-fast-media-design.md

## Constraints

Keep signatures and signed events unchanged, lowercase hashes, 1 MiB and 1600 px photo bounds, 8 photos per side. Text first. No simultaneous strategies. Stop and run-length cancellation prevent restart. Use small buildable commits on main as previously authorized.

## Review focus

Old peers ignore new HELLO fields; failed negotiation must not strand chunk exchange. Callbacks can arrive in either order. Stop can race reconnection. Moderation can change during transfer. A failed file must not count as saved.

## Tasks and ledger

- [x] 1. Protocol/coordinator: add optional fast-photo capability and bounded handoff/file control frames to NearbyFrames; add MediaHandoffCoordinator with generation-bound leases and cluster/preparing/connecting/transferring/stopped states. Tests pin old HELLO parsing, malformed tokens/descriptors and stop/stale leases. Run failing tests, implement, verify, commit.
- [x] 2. File exchange: add FilePeerLink and FastPhotoExchange with deterministic directions, descriptor-ready-file-ACK sequence, timeout and bounded file reads. Check requested hashes, size, hash, image and current moderation before durable ACK. Extend NearbyExchange with optional FastMediaHandoff; negotiate reservation only after inventories; retain existing chunks when unavailable. Tests cover real exchange plans, old peers, busy reservation, corrupt/hidden files and transfer order. Run failing tests, implement, verify, commit.
- [x] 3. Android adapter: extract NearbyPeerLink, track expected FILE payloads and transfer-success independently, bounded cleanup and cancellation. Integrate media rendezvous and single-strategy pause/restart into NearbySharing with 15s drain, 30s reconnect, 120s/file, 5min batch. Test callback order and cancellation with injectable fakes. Verify and commit.
- [x] 4. UI and release: expose actual Wi-Fi state in NearbyUi, open Android Wi-Fi panel, refresh on resume, retain Stop and duration state; update protocol and progress docs with built versus device-tested boundary. Run testDebugUnitTest assembleRelease, review, fix, commit.

Media server, resumable video, direct sharing and offline metadata remain subsequent independent slices. Do not claim them complete from the fast-photo slice.

Task 1: failing compile confirmed missing API; full existing suite plus 3 new tests passed.

Task 2: new file API tests failed before implementation, then the full suite passed including negotiated handoff and busy-peer chunk fallback.

Tasks 3 and 4: adapter callback ordering, unsolicited FILE cleanup, Stop cleanup, Wi-Fi intent, dimensions, disconnects, bounded bidirectional chunk queues and slow chunk progress are covered. Review found duplicate hashes, wrong dimensions, transport exceptions, resource registration and Stop races; all fixed. Slow-progress and dimension regressions failed before fixes. Final testDebugUnitTest assembleRelease passed: 116 tests, zero failures. Final focused review found no further important findings. Physical-phone speed, reconnection and battery validation remain open.
