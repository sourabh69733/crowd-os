# Followed-author feed implementation plan

**Goal:** Read recent public text bulletins from explicitly followed Nostr keys through either configured relay.

**Boundary:** Manual refresh only. No global discovery, identity verification, background sync, or offline transport. Refreshing reveals queried author keys to relay operators. A signature proves key control, not the author's identity or a report's truth.

## Behavior

1. Persist at most 20 author keys as `following`, `muted`, or `blocked`. Only 64-character lowercase hexadecimal keys are accepted. Mute hides an author from the feed and refresh; block also prevents new local storage and hides existing saved copies. Unblock removes the local restriction; unfollow removes a follow. Existing public copies remain on disk until a later deletion design.
2. A manual refresh asks each configured relay for at most 50 `kind:1` events from followed, unmuted keys. The client accepts only matching, correctly signed events, caps frame size/count, and closes after `EOSE`. Each relay's failure is reported separately; one failure does not discard the other's verified events.
3. Store accepted events by event ID and show a chronological feed from followed, unmuted keys. Label every entry as an unverified source. A full 100-event local store stops accepting new posts and reports that limit.

## Small implementation steps

- Add a Room schema migration and author-policy store with migration and policy tests. Keep bulletin and outbox rows unchanged.
- Add bounded author-query frames and relay fetch with hostile-frame and local WebSocket tests.
- Add a sync coordinator that saves verified events from each relay independently, then add a focused Compose section for follow, mute, block and manual refresh.
- Run all Android tests, build the APK, update the connected phone, and test following the phone's own public key as a one-device check. Cross-device validation remains open.

The relay `REQ` filter and `EOSE` behavior follow [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md).
