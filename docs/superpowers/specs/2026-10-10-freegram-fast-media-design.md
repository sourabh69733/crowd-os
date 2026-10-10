# Freegram fast media transfer design

Date: 10 October 2026. Status: proposed design, implementation has not started.

## Goal and approved choice

Keep public text moving between nearby phones and move photos faster when both phones support the new transport. The user approved briefly pausing cluster sharing, connecting one pair in point-to-point mode, and resuming cluster sharing afterward. Existing phones must still exchange text and photos using protocol v1.

This is the first remaining media stage in OFFLINE_MEDIA_PLAN.md. Later stages are the media server, resumable video, direct sharing, and offline names, hide lists and likes. Those stages should have their own focused implementation and commits.

## Verified platform constraints

The installed Android dependency is play-services-nearby 19.3.0. Its public ConnectionsClient API does not expose an explicit bandwidth-upgrade method. Nearby reports STATUS_ALREADY_HAVE_ACTIVE_STRATEGY when another strategy still has active advertising, discovery or connections. Creating another client is not a supported workaround.

Sources:

- https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/ConnectionsStatusCodes#STATUS_ALREADY_HAVE_ACTIVE_STRATEGY
- https://developers.google.com/nearby/connections/strategies
- https://developers.google.com/nearby/connections/android/exchange-data
- https://android-developers.googleblog.com/2026/07/upcoming-changes-nearby-connections-api.html

Point-to-point offers the highest-throughput topology, but Google controls the selected radio. The app must describe it as a faster media connection, without asserting a specific radio or measured speed. Wi-Fi and Bluetooth need user-controlled enablement. The existing plan's simultaneous cluster and point-to-point sessions must be corrected.

## Exchange and compatibility

Start with the existing HELLO version 1 and an optional capability advertising support for the new media handoff. Older phones ignore the new capability and finish their existing photo round unchanged. New control frames are sent only after both phones advertise support. Document this negotiated extension as the new protocol generation while preserving the v1 bootstrap.

Complete text exchange and acknowledgements first. Exchange eligible photo inventories and bounded wants next. If neither side needs a photo, finish without changing strategy. If Wi-Fi is off on either phone, use the existing v1 chunk path.

When both phones support the handoff and media is waiting:

1. Reserve a single media slot on each phone. A busy phone declines and uses the existing chunk path.
2. Exchange fresh random rendezvous tokens and roles over the current connection. Roles use the existing ordering of random session names. No signing key or public profile is advertised.
3. Confirm readiness on both sides. Stop accepting new cluster connections, let other exchanges finish within a bounded drain period, then disconnect remaining endpoints.
4. Stop cluster advertising and discovery and clear endpoints before starting point-to-point operations.
5. Reconnect only to the selected session using the negotiated rendezvous data. Authenticate the new session against the tokens before sending or accepting media.
6. Transfer the previously requested photo hashes, one file at a time in each direction, with deterministic direction ordering.
7. Acknowledge each file only after transfer success, validation and durable storage.
8. Disconnect the pair and restart cluster advertising and discovery if sharing is still enabled.

Rendezvous tokens bind the media connection to this public exchange. They are not an author identity or a confidentiality guarantee. A phone may refuse or leave at any point. Already acknowledged posts stay stored.

## Ownership and recovery

NearbySharing owns strategy changes and connection lifecycle. A small transport-independent coordinator owns state transitions and decisions; NearbyExchange continues to own inventories, wants, signature checks and store acknowledgements. A separate file adapter handles Nearby FILE payload callbacks and temporary files. Avoid adding strategy management to RoomStore or shared UI.

Use explicit states: Cluster, PreparingMedia, ConnectingMedia, TransferringMedia, ReturningToCluster, Stopped. Keep a monotonically increasing session generation so delayed callbacks from an old connection cannot restart sharing or modify the current session.

Bound preparation/drain to 15 seconds, reconnection to 30 seconds, each file to 120 seconds and the complete media window to 5 minutes. These are pilot limits, not measured transfer estimates. Any failure cancels payloads, removes temporary files, releases the media slot and returns to cluster. Do not recursively retry a failed handoff in the same exchange; photos can retry at the next meeting. Stop, service destruction and the chosen run-length deadline cancel every stage and prevent cluster restart.

## File validation and storage

Keep existing limits: at most 8 requested photos per side, each at most 1 MiB and 1600 pixels per side. Preserve signed events exactly and use lowercase hex hashes only.

A descriptor binds a payload ID to a requested hash and expected size before the receiver grants permission to send the file. Handle FILE arrival and transfer-success callbacks in either order. Reject unexpected, repeated or mismatched payload IDs. Cancel transfers exceeding the expected byte count. Read completed files with a strict size bound; never trust the transport filename or sender's length.

Require size, SHA-256 and decoded JPEG dimensions to match the signed photo reference before MediaStore.put. Recheck that the post remains eligible under the current hide and block rules before storing or acknowledging its photo. Hidden or blocked posts cannot become a bypass through the media connection. Do not weaken NIP-01 signature checks.

Always close file descriptors and delete incomplete or rejected files. Disconnect only after queued acknowledgement payloads have finished or the bounded flush timeout expires. Transfer success alone is not a storage acknowledgement.

## User interface

On Nearby, show a Wi-Fi-off hint: "Turn on Wi-Fi for faster photos and videos" with a button opening Android's Wi-Fi panel, or Wi-Fi settings on older supported Android versions. Recheck radio state when the app resumes. Sharing text remains available when Wi-Fi is off and Bluetooth is available.

Show plain status during the pause: "Transferring photos with one phone. Sharing with other phones resumes afterward." Keep Stop available. Do not claim that Wi-Fi was used or display invented speed measurements.

## Tests and acceptance

Unit tests must cover capability negotiation with old/new phones, no-media and Wi-Fi-off paths, single-slot contention, deterministic roles, timeout recovery, late callbacks, user stop at every stage, and service run-length cancellation.

File tests must cover descriptor/FILE/success ordering, unsolicited and duplicate payloads, wrong hash, size and dimensions, oversized input, hidden/blocked posts, failed storage, cleanup and ACK after durable storage only. Existing v1 photo and post tests must keep passing.

Run testDebugUnitTest and assembleRelease for the complete slice. Commit protocol/coordinator, file transport, UI, and release documentation separately where each commit remains buildable and includes its relevant tests.

Real-phone acceptance requires two phones with no internet: confirm text arrives before photos, verify reconnection to cluster, test Wi-Fi off, interruption and Stop, and measure throughput and battery use. Unit tests and a successful APK build do not establish radio performance.

## Media-server follow-up

The user has freegram.in on Cloudflare and authorized preparing R2 and a media server. Proposed hostname: media.freegram.in, with the existing website kept separate. The repository currently has no media-worker or R2 configuration. Account access and R2 availability need verification before deployment.

Use a Worker with an R2 binding; never ship Cloudflare credentials in the APK. Verify the current Blossom specifications before implementing its authentication and endpoints. Publish new posts with a deterministic media URL included before signing. Carriers upload matching bytes at that URL without editing or resigning the author's event. Existing URL-less signed posts need hash-based lookup without mutation.

Deployment is a separate reviewable step after local server/client tests. File-size, retention and server takedown policies remain product decisions for that stage. Do not invent those policies or treat local maintainer hiding as removal from Cloudflare.
