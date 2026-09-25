# Freegram Android app

This directory is the home of the new Android-first Freegram app. Phase 0 defines its protocol and boundaries in [the Phase 0 contract](../../docs/freegram/PHASE0.md) and [project structure](../../docs/freegram/PROJECT_STRUCTURE.md). The Gradle application begins in Phase 1; this directory is not yet a runnable app.

Target implementation: Kotlin and Jetpack Compose, Room for local records/outbox, a vetted BIP-340-compatible signing library, and isolated internet/nearby transport adapters. The app must save and validate events locally before any network operation. UI must distinguish local save, peer acceptance and each relay's acceptance.

`apps/mobile` is the existing Expo/CrowdOS app and remains untouched until the replacement is demonstrably working. No Freegram APK exists yet.
