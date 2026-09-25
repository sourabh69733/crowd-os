# Freegram project structure

Status: updated 25 September 2026. A first Android application and protocol tests now build; the planned boundaries below still guide later work.

```text
apps/
  android/                 new native Android app and Gradle build
    README.md              build command and prototype limitations
  mobile/                  existing Expo/CrowdOS code; retained for reference
protocol/
  README.md                portable event and exchange boundaries
  vectors/                 language-independent protocol fixtures
docs/
  FREEGRAM_IMPLEMENTATION_PLAN.md
  freegram/
    PHASE0.md               first-release protocol and acceptance contract
    THREAT_MODEL.md         adversaries, controls, residual risks
    PHASE0_FINDINGS.md      library, key-storage and device research
    PROJECT_STRUCTURE.md   this map
packages/core/             existing CrowdOS core; not silently reused
```

Phase 1 should add one Android Gradle project under `apps/android/`. Its logical units are:

| Unit | Owns | Must not own |
|---|---|---|
| `app` | Compose screens, navigation, dependency assembly | Event signatures or Bluetooth calls in screens |
| `domain` | Compose/publish/read use cases and delivery-state rules | Android radio and database APIs |
| `protocol` | NIP-01 serialization, hash/signature verification, bounds, portable fixtures | UI, database or relay connection |
| `store` | Room event, draft, outbox and per-relay state | Signing keys |
| `identity` | Key creation, protected storage and signing API | Feed ranking or networking |
| `transport-relay` | WebSocket subscriptions and per-relay acceptance | Editing signed events |
| `transport-nearby` | Discovery, connection and bounded event exchange | Deciding whether a claim is true |

These are *responsibility boundaries*, not seven mandatory Gradle modules. Begin with one app module and small packages; split only when the interfaces and tests justify it. The `protocol/vectors` fixtures must be consumed by Android and any future iOS implementation. Do not copy old Expo code merely because its names match new concepts: the previous envelope, identity and audience assumptions differ.

The repo's root `package.json` and existing site remain unchanged during Phase 0. This avoids breaking unrelated build commands while the replacement app is still a plan. Retire or remove legacy surfaces only after a working replacement and its data-migration decision exist.
