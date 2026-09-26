# Two-phone test plan

Only two physical Android phones (P1, P2) are available, plus the Pixel 9a emulator (E). This plan gets the most evidence from that setup and says what it cannot prove. Use harmless test posts only; public relays may keep them.

Record for every run: date, app commit, phone models and Android versions, Google Play services version, network state, screen on/off, trial count, and each relay's result.

## 1. Relay bridge (internet only)

Same as [the three-phone procedure](RELAY_BRIDGE_TEST.md) with A = P1, B = P2, C = E. The emulator is enough for C because C only fetches over the internet. Repeat with each relay unavailable.

## 2. Feed on a second phone

P2 follows P1's `npub`, refreshes, and must show P1's posts verified. Then P1 posts again while P2 is offline; P2 reconnects and refreshes, and must catch up without duplicates.

## 3. Nearby, one hop (P1 → P2)

1. Turn off Wi-Fi and mobile data on both phones. Keep Bluetooth and location services on.
2. P1 writes a post (it stays unsent).
3. Both open Freegram and tap **Start sharing nearby**.
4. P2's log must show `received 1`; P1's must show `1 confirmed stored`. P2 opens the post: same event ID, signature valid.

Repeat at least 10 times at 1 m, 10 m and through a wall. Record discovery time and failures.

## 4. Nearby, two hops without a third phone (A → B → C)

A third phone is simulated by giving P1 an empty store after it hands the post over:

1. P1 (as A) posts offline and shares with P2 (as B), as in test 3.
2. On P1, back up the key if needed, then clear Freegram's app data (Settings → Apps → Freegram → Storage → Clear data). P1 is now C, a phone that has never held the post.
3. Both share nearby again. P1 must receive A's post from P2 at hop 2 with A's original key and signature.

This proves store-and-forward through B. It does **not** prove three different radios in range at once, movement between groups, or behaviour with many phones.

## 5. Offline to internet (Phase 4)

1. Repeat test 3 with **Publish posts received nearby** on (the default) on P2.
2. Turn P2's internet back on without opening the app. P2's copy must reach both relays automatically (check with E: fetch the event ID).
3. Repeat with the switch off: nothing must be published.

## What two phones cannot show

Three simultaneous radios, crowded-area behaviour, delivery rates across many devices, or differences across more phone makers. These need a small field pilot before any release decision.
