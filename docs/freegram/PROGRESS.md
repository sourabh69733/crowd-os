# Freegram progress

One page to see where Freegram stands. Updated 29 September 2026, branch `freegram-phase0` (not yet merged to `main`).

Details and test evidence: [STATUS_LOG.md](STATUS_LOG.md). Original plan: [FREEGRAM_IMPLEMENTATION_PLAN.md](../FREEGRAM_IMPLEMENTATION_PLAN.md). Diagrams: the architecture page shared on 28 September.

Legend: ✅ done · 🟡 built, not fully tested on real phones · ⬜ not started

## Phases

| Phase | Goal | Status |
|---|---|---|
| 0. Protocol and threat model | Rules, post format, risks | ✅ |
| 1. Internet app | Post and read signed posts through two servers | ✅ |
| 2. Reliability and safety | Nothing lost, no false "sent", abuse limits | ✅ |
| 3. Nearby sharing | Phone to phone without internet | 🟡 first 2-phone test passed |
| 4. Offline to internet | Carried posts reach servers once online | 🟡 |
| 5. Photos, then reels | Media | 🟡 photos nearby only |
| Moderation | Maintainers, reports, appeals | 🟡 |
| New UI | App screens from the approved mockup | ✅ |
| Release | Signed APK for the website | ✅ 0.2.0 built |
| Discover | Find people and posts | 🟡 built, 0.3.0 on your phone |
| Replies and likes | Answer and react to posts | 🟡 built, 0.4.0 |
| Safety for protests | Panic wipe, optional app lock | 🟡 panic wipe done |
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
- [x] Key backup: plain and password-encrypted (NIP-49), restore, replace a stolen key
- [x] Delete a post from this phone
- [ ] Repeated app-kill tests on a phone

### 3. Nearby sharing 🟡
- [x] Exchange protocol with limits ([protocol/README.md](../../protocol/README.md))
- [x] Nearby Connections adapter, runs in the background with a notification
- [x] First real 2-phone test: posts shared across phones (28 September, by you)
- [ ] Repeated trials: distance, walls, screen off, battery ([TWO_PHONE_TEST.md](TWO_PHONE_TEST.md) test 3)
- [ ] Two hops with 2 phones (test 4)
- [ ] Phones without Google Play services (a Bluetooth-only fallback)

### 4. Offline to internet 🟡
- [x] Posts received nearby are published automatically when online (switch, on by default)
- [x] Simulated A → B → server → D test
- [ ] Real-phone test ([TWO_PHONE_TEST.md](TWO_PHONE_TEST.md) test 5)

### 5. Photos, then reels 🟡
- [x] One photo per post, resized, location removed (checked on your phone)
- [x] Photos swap nearby by fingerprint
- [ ] Photo sent between two real phones
- [ ] Backup media servers (Blossom on Cloudflare R2 + Hetzner), so photos work over the internet
- [ ] Internet P2P for media (opt-in)
- [ ] Reels and video

### Moderation 🟡
- [x] Maintainer hide lists (signed, public, can be switched off)
- [x] Private reports and appeals (NIP-17, Quartz NIP-44), maintainer inbox
- [ ] You set up as the default maintainer for new users
- [ ] Hide lists shared nearby
- [ ] Maintainer inbox servers (NIP-17 kind 10050)

### New UI ✅
- [x] Mockup approved
- [x] Step 1: tabs, Home, post detail, New post, Nearby
- [x] Step 2: onboarding, names with ID ending, QR code and Scan to follow
- [x] Step 3: Settings pages (backup, people, maintainers, servers, storage)
- [ ] Walk-through on your phone with feedback

### Discover 🟡
- [x] Discover tab: maintainer suggestions, recent #freegram posts, search by ID or known name
- [x] "Show my posts in Discover" switch (on by default)
- [ ] Check a tagged post shows on a second phone
- [ ] Default maintainer, so new users see suggestions

### Replies and likes 🟡
- [x] Replies (NIP-10) and likes (NIP-25), counts on every post, thread under each post
- [ ] Checked on your phones
- [ ] Likes over Nearby

### Release ✅
- [x] Release key outside the repo (`~/.freegram/`, password in Keychain)
- [x] Release APK 0.2.0, 6.8 MB, tested on the emulator
- [x] App icon
- [ ] You back up the release key and its password
- [ ] APK on your website
- [x] Play launch prep (7 October): target Android 16, AAB build, terms in app, Delete my account, default maintainer hook, privacy policy, listing, data safety ([play/README.md](play/README.md))
- [ ] Your ID as default maintainer; support email in the Play docs
- [ ] Play: upload own signing key, closed test with 12+ testers for 14 days, apply for production

### Safety for protests 🟡
- [x] Panic wipe: hold 2 seconds in Settings; erases ID, posts, photos and lists (checked on the emulator)
- [ ] Optional app lock (PIN or fingerprint), off by default
- [ ] Encrypt local data: deferred; build only together with the optional app lock (Android 10+ already encrypts each file, so a wipe already defeats most recovery)
- [ ] Optional disguised icon and name

### 6. Field pilot ⬜
- [x] Code and security review (30 September): 4 fixes, see [STATUS_LOG.md](STATUS_LOG.md)
- [x] Merged to `main` (1 October)
- [x] Principles doc, draft 1 ([PRINCIPLES.md](PRINCIPLES.md)): content rules, safety by design, maintainer role, 12 legal questions
- [ ] Your answers to its open decisions; lawyer review
- [ ] Small real group, one maintainer
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
| 29 Sep | Release key kept on your Mac, password in Keychain (option A). |
| 30 Sep | Panic wipe: yes. App lock: optional only, off by default. |
| 7 Oct | Google Play first (account bought); Apple later, when the iPhone app is ready. A web app may come before native iPhone, for mixed Android/iPhone groups. |
| 1 Oct | Local data encryption deferred until app lock; Android's own per-file encryption covers wipes on Android 10+. |
| 1 Oct | Discover tab: maintainer suggestions, recent posts, search. Posts opt in with #freegram (on by default). |

## Next up

1. Merge the branch to `main` (review done 30 September).
2. Back up the release key; put APK 0.2.0 on your website.
3. Two-phone tests 3–5 with the release build.
4. Answer the open decisions in [PRINCIPLES.md](PRINCIPLES.md); find a lawyer to review it.
5. Default maintainer setup, then backup media servers.

## Not planned yet

iPhone app (needs the App Store and a Bluetooth bridge to Android), running our own relay, independent security audit.
