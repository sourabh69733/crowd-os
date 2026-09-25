# Freegram Android app

This directory contains the first buildable Android prototype. Phase 0 defines its protocol and boundaries in [the Phase 0 contract](../../docs/freegram/PHASE0.md) and [project structure](../../docs/freegram/PROJECT_STRUCTURE.md).

Build with `ANDROID_HOME=<Android SDK path> ./gradlew :app:testDebugUnitTest :app:assembleDebug`. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Current slice: Kotlin/Compose, NIP-01 kind-1 text events, ACINQ secp256k1-kmp 0.24.0 for BIP-340, Android Keystore AES wrapping for an exportable Nostr secret, local draft/outbox, and WebSocket relay `OK` status. The user can configure two `wss://` relays. Signing and local persistence happen before network attempts. A saved event can be retried after restart.

This is **not field ready**. The local store is a bounded SharedPreferences prototype, not the planned Room database. It shows the latest of at most 100 saved events; it has no incoming feed, nearby transport, background retry, key backup/recovery, source verification or moderation UI. The key can be lost on uninstall or device loss. No physical device or public relay end-to-end test has passed. See [Phase 1 status](../../docs/freegram/PHASE1_STATUS.md).

`apps/mobile` is the existing Expo/CrowdOS app and remains untouched until the replacement is demonstrably working.
