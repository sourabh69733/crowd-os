# Freegram progress

One page to see where Freegram stands. Updated 14 October 2026. All work is on `main`. Latest app version: **0.6.1** (website still offers 0.4.1).

- Details and test evidence: [STATUS_LOG.md](STATUS_LOG.md)
- Next features (photos, videos, reach without internet): [OFFLINE_MEDIA_PLAN.md](OFFLINE_MEDIA_PLAN.md)
- Principles and legal questions: [PRINCIPLES.md](PRINCIPLES.md)
- Google Play launch: [play/README.md](play/README.md)
- Original plan: [FREEGRAM_IMPLEMENTATION_PLAN.md](../FREEGRAM_IMPLEMENTATION_PLAN.md)

Legend: ✅ done · 🟡 built, not fully tested on real phones · ⬜ not started

## Where things stand

| Area | Goal | Status |
|---|---|---|
| 0. Protocol and threat model | Rules, post format, risks | ✅ |
| 1. Internet app | Post and read signed posts through two servers | ✅ |
| 2. Reliability and safety | Nothing lost, no false "sent", abuse limits | ✅ |
| 3. Nearby sharing | Phone to phone without internet | 🟡 first 2-phone test passed |
| 4. Offline to internet | Carried posts reach servers once online | 🟡 |
| 5. Photos and videos | Media | 🟡 photos nearby and online; video engine built, no video screens yet; plan in [OFFLINE_MEDIA_PLAN.md](OFFLINE_MEDIA_PLAN.md) |
| Moderation | Maintainers, reports, appeals | 🟡 you are the default maintainer |
| New UI | Screens from the approved mockup | ✅ |
| Discover | Find people and posts | 🟡 |
| Replies and likes | Answer and react to posts | 🟡 |
| Safety for protests | Panic wipe, delete account, optional app lock | 🟡 |
| Website | freegram.in with APK download | 🟡 live, but offers 0.4.1; needs 0.6.1 |
| Media server | media.freegram.in for photos and videos | 🟡 live for photos; limits and video support written, not deployed yet |
| Google Play | Public listing | 🟡 key uploaded, forms in progress, closed test next |
| 6. Field pilot | Real users, go or no-go | ⬜ |

### 0. Protocol and threat model ✅
- [x] Post format: standard Nostr signed events ([PHASE0.md](PHASE0.md))
- [x] Threat model ([THREAT_MODEL.md](THREAT_MODEL.md))
- [x] Portable test vectors ([protocol/vectors](../../protocol/vectors))

### 1. Internet app ✅
- [x] Sign and verify posts (BIP-340, official vectors)
- [x] Save before sending; state per server
- [x] Two servers (damus, nos.lol), each result shown separately
- [x] Feed of followed people, mute and block
- [x] Checked on phone and emulator, including one server down

### 2. Reliability and safety ✅
- [x] Automatic retry, in the app and in the background after app kill or offline
- [x] Storage limits: 100 posts, 20 per person, 50 MB photos, safe removal order
- [x] Feed catch-up after being offline
- [x] Key backup: password-encrypted (NIP-49), restore, replace a stolen key
- [x] Delete a post from this phone
- [x] Code and security review (30 September): 4 fixes
- [ ] Repeated app-kill tests on a phone

### 3. Nearby sharing 🟡
- [x] Exchange protocol with limits ([protocol/README.md](../../protocol/README.md))
- [x] Nearby Connections adapter, runs in the background with a notification (2 hours, 6 hours or until stopped)
- [x] First real 2-phone test: posts shared across phones (28 September)
- [ ] Repeated trials: distance, walls, screen off, battery ([TWO_PHONE_TEST.md](TWO_PHONE_TEST.md) test 3)
- [ ] Two hops with 2 phones (test 4)
- [x] Hop setting: Short (6), Medium (12, default), Far (20)
- [x] Carry verified Discover posts nearby, within existing storage limits; server-fetched replies stay in memory
- [x] Nearby run length: 2 hours (default), 6 hours or until stopped, with a battery note
- [ ] Check the new reach and run-length settings on real phones ([plan, feature 5](OFFLINE_MEDIA_PLAN.md#5-reach-farther-without-internet))
- [ ] Names, hide lists and likes over Nearby
- [ ] Phones without Google Play services (own Bluetooth layer)

### 4. Offline to internet 🟡
- [x] Posts received nearby are published automatically when online (switch, on by default)
- [x] Simulated A → B → server → D test
- [ ] Real-phone test ([TWO_PHONE_TEST.md](TWO_PHONE_TEST.md) test 5)

### 5. Photos and videos 🟡
- [x] One photo per post, resized, location removed
- [x] Photos swap nearby by fingerprint
- [ ] Photo sent between two real phones
- [x] Fast photo handoff: text first, brief cluster pause, requested FILE transfer, v1 fallback
- [x] Wi-Fi-off hint and Android settings button
- [ ] Test fast media on two phones: reconnect, Wi-Fi off, interrupted transfers, Stop, throughput and battery ([plan, feature 1](OFFLINE_MEDIA_PLAN.md#1-fast-wi-fi-mode-for-media))
- [x] Blossom photo server on Cloudflare R2 at `media.freegram.in`, scoped signed uploads and checked downloads
- [x] Android carrier uploads, persistent rotating retries and photo completion updates
- [x] Media server limits: 50 MB per ID and 200 MB per day for everyone, on top of 100 files per ID and the 1 GB cap (14 Oct; deploy pending)
- [ ] Deploy the media server update: `npx wrangler deploy --config freegram-media/wrangler.jsonc`
- [ ] Media server deletion and moderation workflow (today only `BLOCKED_HASHES`)
- [ ] Real-phone online photo upload, download and carrier reconnect test
- [ ] Videos in pieces from several phones, resumable ([feature 3](OFFLINE_MEDIA_PLAN.md#3-videos)). Engine built (14 Oct): shrinking with metadata removal, signed manifests, pieces over Nearby file links from several phones, resume, retention, server upload and byte-range download, with tests
- [ ] Video screens: pick a video, post it, show progress, play it
- [ ] "Send to a nearby phone" for big files ([feature 4](OFFLINE_MEDIA_PLAN.md#4-send-to-a-nearby-phone-big-files-on-purpose))

### Moderation 🟡
- [x] Maintainer hide lists (signed, public, can be switched off)
- [x] Private reports and appeals (NIP-17, Quartz NIP-44), maintainer inbox
- [x] You are the default maintainer for new installs (0.4.1)
- [x] Maintainers can suggest people in Discover
- [ ] Hide lists shared nearby
- [ ] Maintainer inbox servers (NIP-17 kind 10050)

### New UI ✅
- [x] Mockup approved; tabs, Home, post detail, New post, Nearby
- [x] Onboarding with rules and terms, names with ID ending, QR code and Scan to follow
- [x] Settings pages, now opened from the gear on Profile
- [ ] Walk-through on your phone with feedback
- [ ] 4 small mockup gaps: Share post, Profile rows, live "phones near", paste ID on scan

### Discover 🟡
- [x] Discover tab: maintainer suggestions, recent #freegram posts, search by ID or known name
- [x] "Show my posts in Discover" switch (on by default)
- [ ] Check a tagged post shows on a second phone

### Replies and likes 🟡
- [x] Replies (NIP-10) and likes (NIP-25), counts on every post, thread under each post (0.4.0)
- [ ] Checked on your phones

### Safety for protests 🟡
- [x] Panic wipe: hold 2 seconds; erases ID, posts, photos and lists
- [x] Delete my account: asks servers to delete (NIP-09, NIP-62), then wipes (0.4.1)
- [ ] Optional app lock (PIN or fingerprint), off by default
- [ ] Encrypt local data: deferred, build only together with app lock
- [ ] Optional disguised icon and name

### Website ✅
- [x] freegram.in: what it is, how it works, features, download APK 0.4.1 with checksums, privacy and terms pages (9 October)
- [ ] Put 0.6.1 (or the next release) on the site with new checksums
- [ ] Fix automatic Cloudflare builds (today deployed by hand with `npx wrangler deploy`)
- [ ] Self-updating website build ("Update available" banner)

### Google Play 🟡
- [x] Launch prep: target Android 16, AAB, terms in app, delete account, privacy policy, listing, data safety ([play/README.md](play/README.md))
- [x] Developer account; own signing key uploaded (8 October), so Play and website builds update each other
- [x] Icon and feature graphic ([play/graphics](play/graphics))
- [ ] Screenshots from your phone
- [x] Privacy policy and data safety updated for media.freegram.in (14 Oct); website copy needs `npx wrangler deploy` from `freegram-site`
- [ ] Finish App content forms; upload the latest version (0.6.1 or newer) to closed testing
- [ ] 12+ testers for 14 days, then apply for production

### Release keys ✅
- [x] Release key outside the repo (`~/.freegram/`, password in Keychain)
- [ ] You back up the release key and its password somewhere safe

### 6. Field pilot ⬜
- [x] Principles doc, draft 1 ([PRINCIPLES.md](PRINCIPLES.md))
- [ ] Your answers to its open decisions; lawyer review
- [ ] Small real group (college fest or match), one maintainer
- [ ] Measure delivery, battery, confusion points; go or no-go

## Decisions

| Date | Decision |
|---|---|
| 25 Sep | Public posts only, no "nearby only" privacy promise. Nostr signed events. |
| 27 Sep | Only 2 physical Android phones (plus an emulator) for testing. |
| 27 Sep | Posts received nearby publish automatically when online. |
| 27 Sep | Maintainers: start with 1, grow to 3–4, each with their own key; appeals allowed. |
| 27 Sep | Media: P2P first (nearby, then internet), servers as backup. Cloud options: R2, Hetzner, GCP. |
| 27 Sep | Encryption library: Quartz, after upgrading to compile SDK 37 and AGP 9. |
| 29 Sep | India first, protest settings. Build first, then measure adoption. |
| 29 Sep | "Fake should not exist; dark, true and real can." Safety by design. |
| 29 Sep | Identity = one key per install; no email or phone number. |
| 29 Sep | Distribution: APK from your website first, Google Play later. |
| 29 Sep | You are the default maintainer. |
| 29 Sep | Android only for now; code structured for iPhone later (Kotlin Multiplatform `shared` module). |
| 29 Sep | Release key kept on your Mac, password in Keychain. |
| 30 Sep | Panic wipe: yes. App lock: optional only, off by default. |
| 1 Oct | Discover tab: maintainer suggestions, recent posts, search. Posts opt in with #freegram (on by default). |
| 1 Oct | Local data encryption deferred until app lock. |
| 1 Oct | Device testing done by you; Claude writes code, unit tests and builds. |
| 7 Oct | Google Play first; Apple account when the iPhone app is ready (one account also covers Omiryn). Web app may come before native iPhone, for mixed groups. |
| 8 Oct | Play signs with your own key (not Google-generated), so the website APK keeps working if Play drops the app. |
| 8 Oct | Target audience 18+. Support email sourabhsahu69733@gmail.com. |
| 9 Oct | APK distributed from freegram.in now, alongside the Play closed test. All work on `main`. |
| 10 Oct | Fast photos pause cluster sharing for a bounded point-to-point media window because Nearby allows one active strategy. Older peers use v1 chunks. No measured speed or specific-radio claim until device testing. |
| 10 Oct | Reach settings apply locally to offers and acceptance, without changing protocol v1. Eligible Discover posts are carried with no relay deliveries. Nearby defaults to 2 hours; longer runs use more battery and changes apply on the next start. |
| 10 Oct | Next: photos and videos for everyone, videos slower but supported; fast phone-to-phone Wi-Fi for media; Wi-Fi link only while media is waiting. |
| 14 Oct | Media server upload limits: 50 MB per ID and 200 MB per day in total, because new IDs are free; a flood can pause uploads for the day but can't fill storage. Privacy policy now names our media server. |
| 10 Oct | Media server pilot deployed at media.freegram.in. Signed JPEG uploads, 1 MiB photos, 1600 px dimensions, 100 new hashes per key and 1 GiB reserved cap. URLs are included before post signing; carriers never edit signed posts. Android attempts eight hashes per pass with persistent rotation and receipts. Downloads trigger UI refresh. Production retention/deletion and physical-phone end-to-end checks remain open. |

## Next up

1. **You:** deploy the media server (`npx wrangler deploy --config freegram-media/wrangler.jsonc`) and the website (`cd freegram-site && npx wrangler deploy`).
2. **Me:** video screens (pick, post, progress, play), then release 0.7.0 and put it on freegram.in.
3. **You:** test 0.6.x on your phones: reach settings, carried Discover posts, longer Nearby, fast photos, online photos.
4. **You:** screenshots; finish the Play forms; upload the latest version to closed testing; invite 15 to 20 testers.
5. **You:** back up the release key; answer the open decisions in [PRINCIPLES.md](PRINCIPLES.md); find a lawyer.

## Not planned yet

iPhone app (needs the Apple account and a Bluetooth bridge to Android), own Nostr server, SMS fallback, independent security audit.
