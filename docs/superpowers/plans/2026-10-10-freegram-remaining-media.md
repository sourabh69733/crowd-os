# Remaining Freegram media implementation

The user requested the remaining OFFLINE_MEDIA_PLAN features and explicitly authorized implementation without more design approval. Continue on main, small buildable commits. Device testing remains a separate acceptance gate.

## Completed photo server slice

- [x] R2 bucket freegram-media and separate media.freegram.in Worker, bounded signed Blossom photo uploads and public hash GET/HEAD.
- [x] URLs included before post signing, unchanged carrier posts, upload receipts and bounded verified downloads.
- [x] Review fixes: standard upload-check headers, bounded JPEG decode, persistent rotating retry batches, observed durable-download changes.
- [x] Server tests: four passed. Live throwaway-key615-byte JPEG upload201, checked download200, HEAD200. Android121 tests and release build passed; final versioned build also passed.

Ruling: initial public pilot accepts valid scoped signed uploads, at most100 new hashes per key and1GiB total reserved bytes. The optional policy question had no response after more than60seconds; public uploads were announced as the assumption before deployment. Empty UPLOAD_KEYS disables uploads; a comma-separated list restricts keys. No cloud credentials enter the APK. Failed storage writes retain reservations for safety. Production retention/deletion remains open.

## Resumable video slice

- [ ] Signed bounded video manifest: full hash, exact MP4 size, dimensions, 256KiB ordered piece hashes. At most15MiB encoded video, source at most40MiB. Embed manifest reference in a signed post without exceeding existing bulletin bounds. Verify manifest author matches post author. New optional capability; old peers retain text/photos.
- [ ] Video storage: durable checked pieces, missing-index inventory across restart and multiple peers, full-file hash before playback, media budget200MiB default through1GiB, removable others first and never own unsent pieces. Protect active staging.
- [ ] Android encoder and UI: select source, bounded copy, H264/AAC export at720p then480p if needed, new MP4 mux strips location/camera metadata, cancel/cleanup, publish and show download progress/playback. Report unsupported codecs honestly.
- [ ] Nearby media: text then photos then video pieces, only requested pieces, hash/size before ACK, bounded FILE batch and resumable inventories, Stop invalidates session and preserves checked pieces. Server video upload/download integration, signed URLs before signing.

## Direct sharing slice

- [ ] Explicit selection of one post and one nearby phone, reciprocal acceptance before transfer, same verified resumable media pipeline, cancellation and progress, no silent identity disclosure through discovery labels.

## Offline metadata slice

- [ ] Keep signed profile wires, bounded profiles for stored authors, configured maintainer hide lists only, likes for eligible posts. Negotiate optional metadata exchange, verify every event, newer replaceable values win, never introduce new trust automatically. Preserve unlike tombstones against stale replay.

## Verification and commits

For each slice add meaningful unit regressions, verify full testDebugUnitTest assembleRelease, obtain focused fresh review, fix important findings, update protocol/progress/status docs and bump version as needed. Do not claim physical-phone throughput, radio choice, battery, encoder compatibility or completed remaining features from unit results alone.
