# App Structure

The mobile app is organized so a future web app can reuse protocol and domain
logic without reusing native transport code.

```text
apps/mobile/src/
  navigation/   screen flow and route state
  screens/      attendee and volunteer views
  storage/      encrypted device persistence
  transport/    future Android/iOS nearby adapters

packages/core/
  message types, validation, encoding and secure envelopes

future apps/
  web/           PWA participant experience
  dashboard/     organizer operations console
```

Rules:

- Screens call domain services, not Bluetooth APIs directly.
- Native transport is isolated behind a platform-neutral interface.
- Shared message types and security rules live in `packages/core`.
- Web storage will use IndexedDB; mobile storage uses SQLCipher.
