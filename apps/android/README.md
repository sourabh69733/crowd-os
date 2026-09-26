# Freegram Android app

This directory contains the first buildable Android prototype. Phase 0 defines its protocol and boundaries in [the Phase 0 contract](../../docs/freegram/PHASE0.md) and [project structure](../../docs/freegram/PROJECT_STRUCTURE.md).

Build with `ANDROID_HOME=<Android SDK path> ./gradlew :app:testDebugUnitTest :app:assembleDebug`. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Current slice: Kotlin/Compose, NIP-01 kind-1 text events, ACINQ secp256k1-kmp 0.24.0 for BIP-340, Android Keystore AES wrapping for an exportable Nostr secret, local draft/outbox, and WebSocket relay `OK` status. The user can configure two `wss://` relays. Signing and local persistence happen before network attempts. A saved event can be retried after restart.

For the relay bridge test, A can copy its signed public JSON, B can paste and verify that JSON before explicitly submitting it, and C can fetch the event ID from relay 1 or relay 2. Copy/paste uses a transfer method outside Freegram; it does not test offline peer transport. Follow [the three-phone procedure](../../docs/freegram/RELAY_BRIDGE_TEST.md).

This is **not field ready**. Room stores the draft, signed events, per-relay delivery records and author controls with a 100-event pilot limit. The screen lists verified local bulletins and offers a manual followed-author feed from two relays. Users can mute or block keys locally; a signature never verifies a person's identity or a report. Refreshing exposes followed keys to relay operators. There is no global discovery, background sync, nearby transport, key backup/recovery or complete moderation system. Uninstall or device loss can lose the signing identity. Both relays completed one feed refresh on a single phone after earlier transient failures; cross-device delivery and relay reliability remain unverified. See [Phase 1 status](../../docs/freegram/PHASE1_STATUS.md).

`apps/mobile` is the existing Expo/CrowdOS app and remains untouched until the replacement is demonstrably working.
