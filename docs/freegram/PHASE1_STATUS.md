# Freegram Android slice: status

25 September 2026. This is a developer prototype, not a field pilot or a release candidate.

## Implemented and verified

- `apps/android` builds a debug APK with Kotlin and Compose. `:app:testDebugUnitTest` and `:app:assembleDebug` passed locally.
- NIP-01 canonical serialization matches both portable fixtures in `protocol/vectors/nip01-id-v1.json`, including Unicode and newline content. The test suite runs official [BIP-340 vectors 0–14](https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv), which cover 32-byte messages used for Nostr IDs. Modified message, signature, public key and over-limit content are rejected by tests. BIP-340 vectors 15–18 use other message lengths and are outside this Nostr event-ID path.
- [ACINQ secp256k1-kmp 0.24.0](https://github.com/ACINQ/secp256k1-kmp) supplies the Schnorr implementation and is published under Apache-2.0. A candidate NDK Kotlin `1.0.0` artifact was not found on Maven Central; current Quartz `1.16.0` required Android API 37 / AGP 9.1 through its dependencies, while the installed SDK is API 36. Those packages were not adopted.
- The prototype saves a draft and signed bulletin locally before network submission. It records each relay's `OK` acceptance/rejection independently and retains a pending bulletin for manual retry after restart. `Accepted` is an observed relay response, never a global delivery claim.
- An Android Keystore AES-GCM key wraps the exportable Nostr secret at rest. This does **not** make BIP-340 signing hardware-backed: the secret is decrypted into app memory while signing. Backup, rotation and recovery do not exist; reinstall/device loss can lose identity. Android backup is disabled for this prototype.

## External integration results

- A throwaway BIP-340 key produced the exact official vector-0 signature with an independent `coincurve` probe.
- A second probe tried publishing one harmless, throwaway signed NIP-01 event from a separate WebSocket client to `wss://relay.damus.io` and `wss://nos.lol`, then fetching it with a third connection. The handshakes returned HTTP 503 and 502 respectively from this execution environment, before NIP-01 `EVENT` or `OK`. **No relay acceptance or bridge claim is supported.** Repeat on a normal network and on target phones; record operator/policy independence and rejection reasons.
- No Android phone is attached. The APK has not been installed or exercised on hardware. Nearby A → B → C, carrier bridging, battery, Google Play services dependency and background behavior remain untested.

## Next acceptance gate

1. Test two independent relay operators from a normal mobile network: A signs, carrier B submits the same event, C fetches and verifies it. Include outage/rejection cases and preserve per-relay states.
2. Move local records to Room with an outbox and per-relay records, then test force-stop/restart and storage limits.
3. Add key export/recovery and a loss/compromise path before inviting outside users; validate the wrapper on target Android devices.
4. Add incoming event verification, local mute/block and a clear unverified-source label before a public feed.
5. Only then add Nearby Connections behind a transport interface and run repeated offline A → B → C device trials.
