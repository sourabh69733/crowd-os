# Three-phone relay bridge check

Use only a harmless test bulletin. Public relays may retain it. This checks the internet bridge, not Bluetooth, mesh coverage or safe field operation.

## Setup

- Install the same debug APK on Android phones A, B and C. Record app commit, model, OS version, network and time for each phone.
- Configure `wss://relay.damus.io` and `wss://nos.lol`, or document two other relay URLs and their NIP-11 information. Different URLs alone do not prove independent operators.
- Transfer the signed public JSON from A to B by a stated external method. The app currently copies it to A's clipboard and accepts paste on B; it does not transfer bytes between phones offline.

## Run

1. A signs a short bulletin. Record its event ID, author public key, signature and content. Copy the signed JSON. Put A offline before B submits it.
2. On B, paste the JSON into **Bridge test** and tap **Verify and carry event**. Confirm the ID, author key, signature and content match A exactly. Tap **Submit saved event to relays** and record the `OK` result or rejection for each URL. B must not need A's private key.
3. On C, enter A's event ID and fetch from relay 1, then relay 2. Confirm both returned events have A's ID, key, signature and content. C must label the signature as valid without claiming the author or report is verified.
4. Repeat with one relay unavailable and with one byte of the imported content changed. The unavailable relay must remain visibly unresolved, while the changed event must be rejected before storage or submission.
5. Force-stop and restart B during a pending attempt. Confirm the same event ID and per-relay states survive and manual retry uses the saved event.

Record each trial's success/failure and delay, relay `OK` text, disconnections and network changes. Run multiple trials on more than one phone model before setting a pilot reliability threshold. Do not infer lasting relay storage from one successful fetch.
