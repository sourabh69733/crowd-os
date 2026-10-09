# Play Console answers

Answers for the forms under Play Console → Policy → App content. Based on the code as of version 0.4.0. Re-check them whenever the app starts handling new data.

## Data safety

**Does your app collect or share any of the required user data types?** Yes.

Play counts data that leaves the phone as "collected", even when it goes to independent servers rather than to us. Freegram sends public posts to public Nostr servers, so declare:

| Data type | Collected | Shared | Optional? | Purpose | Notes |
|---|---|---|---|---|---|
| Personal info → Name | Yes | Yes | Optional | App functionality | The display name the user chooses |
| Personal info → User IDs | Yes | Yes | Required | App functionality | The public key (npub) |
| Messages → Other in-app messages | Yes | Yes | Optional | App functionality | Public posts, replies and likes; encrypted reports and appeals |
| Photos and videos → Photos | Yes | Yes | Optional | App functionality | Photos attached to public posts (location removed) |

Not collected: location, contacts, phone number, email, financial info, health, app activity analytics, web history, device IDs, crash logs, diagnostics.

**Is all data encrypted in transit?** Yes (servers are reached over `wss://` TLS; Nearby connections are encrypted by Google Nearby Connections).

**Can users request that their data be deleted?** Yes: Settings → Delete my account.

**Data deletion URL** (Play asks for a web link too): use the "Deleting your data" section of the privacy policy:
https://freegram.in/privacy#deleting-your-data

## Other declarations

| Form | Answer |
|---|---|
| Ads | No ads |
| App access | All features available without login. No account or credentials needed. |
| Content rating (IARC questionnaire) | Social / communication app; users interact and share content; users can share location? **No** (Freegram strips location); unrestricted internet? Yes. Expect a Teen to Mature rating. |
| Target audience | 18 and over only |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |
| User-generated content | Yes. Users can report content (post menu → Report), block users, and maintainers hide content. Terms shown during setup. |
| Account deletion | In-app: Settings → Delete my account. Web: privacy policy link above. |

## Foreground service declaration

Play asks apps targeting Android 14+ to justify each foreground service type.

- **Type:** `connectedDevice`
- **Feature:** Nearby sharing. The user turns it on; the service keeps exchanging public posts with nearby Freegram phones over Bluetooth and Wi-Fi while the screen is off, shows an ongoing notification with a Stop button, and stops itself after 2 hours.
- **Why it must be a foreground service:** the exchange happens when another phone comes into range, which can't be scheduled in advance, and it must keep running while the phone is in a pocket.
- **Video:** record a short screen video: open Nearby, turn sharing on, show the notification, lock the screen, unlock, and show "posts received" increasing (with a second phone nearby). Upload it to YouTube as unlisted and paste the link.

## Permissions to explain if asked

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES` | Find and connect to nearby Freegram phones (Google Nearby Connections). Scan is flagged `neverForLocation`. |
| `ACCESS_FINE_LOCATION` (Android 12 and older only) | Required by Android for Bluetooth scanning on those versions. Location is never read or stored. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS` | Nearby sharing in the background, with a visible notification. |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Reach public servers; Nearby Connections uses Wi-Fi. |
