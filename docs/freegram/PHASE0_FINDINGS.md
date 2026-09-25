# Phase 0 dependency and device findings

Checked 25 September 2026. This is a shortlist, not a dependency approval or a claim that Freegram has been built.

## Signing and Nostr library

- [Nostr Development Kit for Kotlin](https://github.com/nostr-dev-kit/kotlin) advertises Android support, NIP-01 events, signing, relay subscriptions, a Room cache adapter and an MIT license. Its repository is relatively small/new; its own “production-quality” description is a maintainer claim, not a Freegram validation. Phase 1 should build a minimal app against a pinned release, run our ID fixtures and official BIP-340 vectors, inspect key storage, and test relay errors before adopting it.
- [Amethyst's Quartz code](https://github.com/vitorpamplona/amethyst) is an actively used Kotlin Nostr implementation with a signer abstraction. It is a useful reference and fallback candidate, but importing a large app codebase may add more complexity than Freegram needs. Its repository states an MIT license; verify the exact module and transitive dependency licenses before reuse.
- No cryptographic library is selected yet. Do not write our own secp256k1/Schnorr implementation merely to avoid dependency review.

## Android key storage

[Android Keystore documentation](https://developer.android.com/privacy-and-security/keystore) shows EC signing examples, but its examples use ordinary ECDSA and the [Android platform key specifications](https://source.android.com/docs/security/features/keystore/features) list NIST curves. They do not establish that target phones can perform Nostr's secp256k1 BIP-340 signing inside secure hardware. Phase 1 must test the chosen key design on target devices; if hardware signing is unavailable, document how an exportable Nostr secret is encrypted at rest, when it enters process memory, and how backup/loss works. Do not advertise hardware-backed Nostr identity without that evidence.

## Nearby transport

[Nearby Connections](https://developers.google.com/nearby/connections/overview) can discover/connect phones offline and exchange bytes/files using Bluetooth and Wi-Fi; Google documents usage-data collection when using the Google Play services SDK. This makes it a fast radio prototype candidate but a dependency to challenge for censorship-resistant deployment. A native BLE/Wi-Fi adapter must remain possible behind the transport interface. The sender's signature and Freegram event validation are required even if the link itself is encrypted.

## Current device evidence

`adb devices -l` returned **no attached devices** on 25 September 2026. No Freegram radio, background-mode, battery or cross-device test has been run. Before Phase 3, obtain at least three Android phones spanning two manufacturers/OS versions and record Google Play services presence. The local Android SDK is installed, so build work can begin after Phase 0 decisions, but an emulator cannot validate mesh behavior.

## Relay selection check

The NDK Kotlin README lists public relay examples, but that is not evidence of independent ownership, retention policy or willingness to accept carried events. Phase 1 needs two chosen operators with documented URLs/policies and a test that a phone can submit an unchanged event authored by another phone. Some relays may demand authentication, payment or rate limits; a rejected event must remain visibly pending/rejected.
