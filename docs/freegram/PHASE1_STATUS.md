# Freegram Android slice: status

Updated 26 September 2026. This is a developer prototype, not a field pilot or a release candidate.

## Implemented and verified

- `apps/android` builds a debug APK with Kotlin and Compose. `:app:testDebugUnitTest` and `:app:assembleDebug` passed locally.
- NIP-01 canonical serialization matches both portable fixtures in `protocol/vectors/nip01-id-v1.json`, including Unicode and newline content. The test suite runs official [BIP-340 vectors 0–14](https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv), which cover 32-byte messages used for Nostr IDs. Modified message, signature, public key and over-limit content are rejected by tests. BIP-340 vectors 15–18 use other message lengths and are outside this Nostr event-ID path.
- [ACINQ secp256k1-kmp 0.24.0](https://github.com/ACINQ/secp256k1-kmp) supplies the Schnorr implementation and is published under Apache-2.0. A candidate NDK Kotlin `1.0.0` artifact was not found on Maven Central; current Quartz `1.16.0` required Android API 37 / AGP 9.1 through its dependencies, while the installed SDK is API 36. Those packages were not adopted.
- The prototype saves a draft and signed bulletin locally before network submission. It records each relay's `OK` acceptance/rejection independently and retains a pending bulletin for manual retry after restart. `Accepted` is an observed relay response, never a global delivery claim.
- Room 2.8.5 now stores drafts, signed events and per-relay delivery records. Event and relay-target inserts share a transaction. JVM Android tests reopen the database, verify the unchanged event and relay states, reject invalid signatures, migrate old SharedPreferences records and enforce the 100-event limit. The version-1 schema is exported for future migrations. This does not replace a physical force-stop/restart test.
- The bridge test screen now copies a signed public event, verifies and queues an imported event without the carrier's signing key, and fetches one event ID from either relay. A fetched event is verified before local storage and is not queued for forwarding until the user explicitly imports it as a carrier. A local TLS WebSocket test passes the unchanged signed event from a carrier client to a reader client. This is an app/protocol integration test, not a physical-device test.
- An Android Keystore AES-GCM key wraps the exportable Nostr secret at rest. This does **not** make BIP-340 signing hardware-backed: the secret is decrypted into app memory while signing. Backup, rotation and recovery do not exist; reinstall/device loss can lose identity. Android backup is disabled for this prototype.

## External integration results

- A throwaway BIP-340 key produced the exact official vector-0 signature with an independent `coincurve` probe.
- A 25 September probe to `wss://relay.damus.io` and `wss://nos.lol` stopped at HTTP 503/502 handshakes. On 26 September, the same independent-client probe succeeded: both relays returned `OK true` for throwaway event `d89115c8d9e8645fb771f81f32ba0777234391aa54664b9b9d713c49ceffa46d`, and a separate connection fetched it from each. The relays' [Damus](https://relay.damus.io/) and [nos.lol](https://nos.lol/) NIP-11 responses report different names and public keys. This verifies current acceptance and retrieval of a carrier-submitted event from this computer; it does not prove operator independence, retention, phone behavior or offline delivery.
- No Android phone is attached. The APK has not been installed or exercised on hardware. Nearby A → B → C, carrier bridging, battery, Google Play services dependency and background behavior remain untested.

## Next acceptance gate

1. Run the [three-phone relay bridge procedure](RELAY_BRIDGE_TEST.md) on a normal mobile network. A signs, carrier B submits the same event, and C fetches and verifies it from each relay. Include outage/rejection cases and preserve per-relay states.
2. On a target phone, force-stop/restart during relay delivery and verify Room restores the event, pending targets and `OK` states. Measure storage behavior near the 100-event limit.
3. Add key export/recovery and a loss/compromise path before inviting outside users; validate the wrapper on target Android devices.
4. Add incoming event verification, local mute/block and a clear unverified-source label before a public feed.
5. Only then add Nearby Connections behind a transport interface and run repeated offline A → B → C device trials.
