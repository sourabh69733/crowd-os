# Freegram principles

Draft 1, 1 October 2026. Owner: Sourabh (default maintainer). Status: for discussion; nothing here is legal advice.

This page says what Freegram is for, what may be posted, what the app does to keep people safe, what maintainers do, and what we must ask a lawyer before a public release. Related: [MODERATION.md](MODERATION.md) (how moderation is built), [THREAT_MODEL.md](THREAT_MODEL.md) (risks), [PROGRESS.md](PROGRESS.md).

## 1. What Freegram is

A public notice board that keeps working when the internet doesn't. Posts are signed, carried phone to phone when offline, and published to the internet once any phone is back online.

**It is:** public posts about real life: what's happening, where help is, what people saw, what they think, including dark humour.

**It is not:** a private messenger, an anonymous tool, a place for curated or fake lives, or a service that one company controls.

## 2. Core principles

1. **Fake should not exist; dark, true and real can.** Real life is allowed even when it is uncomfortable. Forgery and deliberate deception are not.
2. **Public means public.** Every post can be copied anywhere. The app says so before the first post and never promises privacy it can't keep.
3. **Nobody can put words in your mouth.** Every post is signed. Editing a post or faking its author breaks the signature, and every phone rejects it.
4. **No single off switch.** No company server. Posts travel through several independent servers and phone to phone.
5. **People choose who moderates for them.** Maintainers hide; they never delete. Anyone can switch a maintainer off.
6. **Collect nothing we don't need.** No phone number, email, contacts, location, ads or tracking.
7. **Safety by design, not by promise.** Protections are built into the app (below), not left to good behaviour.
8. **Say the limits out loud.** We tell users what Freegram can't protect them from.

## 3. Content rules

These apply to posts shown by Freegram and to what maintainers hide. They are the rules for the pilot and will change after legal advice.

### Allowed

- Real events, including protests, police action, disasters and their aftermath.
- Criticism of any government, party, company, religion or person.
- Dark humour, satire, strong language, personal stories.
- Photos of real events, including hard ones, when they document what happened.
- Opinions, including unpopular ones.

### Not allowed (maintainers hide the post, and the author for repeat or severe cases)

| Rule | Examples |
|---|---|
| **Child sexual abuse material** | Zero tolerance. Hide the author at once. See section 6. |
| **Fabricated content presented as real** | Doctored photos, invented events, fake "official" notices, fake locations of help (e.g. a false medical tent). |
| **Impersonation** | Using someone else's name and photo to post as them, or pretending to be an official body. |
| **Threats and calls for violence** | Threatening a specific person or group; calling for attacks. Reporting that violence happened is allowed. |
| **Exposing people** | Posting someone's home address, phone number or ID documents; outing protesters' identities to endanger them. |
| **Sexual content without consent** | Intimate images shared without the person's consent. |
| **Hate that targets people** | Attacks on people for religion, caste, gender, ethnicity or similar. Criticising ideas and institutions is allowed. |
| **Scams and spam** | Fraud, phishing, repeated identical posts, mass advertising. |

### How "fake" is judged

"Fake" is the rule most open to abuse, including by those in power calling true things fake. So:

- A maintainer hides a post as fake only when it is **shown to be false and it can cause harm** (for example a false location for help, or a doctored image).
- Disputed claims, predictions, opinions and criticism are **not** fake.
- When unsure, a maintainer leaves the post up.
- Every "fake" decision can be appealed.

## 4. Safety by design (what the app already does)

| Protection | Status |
|---|---|
| "Everything you post is public" warning before the first post | ✅ |
| No phone number, email or contacts; one key per install | ✅ |
| Photos are resized and their location and camera details removed | ✅ |
| Signed posts; tampered or forged posts are rejected everywhere | ✅ |
| Names always shown with the last 4 characters of the ID, so copied names can be told apart | ✅ |
| Nearby sharing uses a random name each session, never the ID | ✅ |
| Limits on what a nearby phone can send (posts, photos, speed) | ✅ |
| Block and mute on this phone; blocked posts are never passed on | ✅ |
| Maintainer hide lists apply to the feed, to nearby sharing and to what is accepted | ✅ |
| Private reports and appeals (encrypted) | ✅ |
| Encrypted ID backup | ✅ |
| Panic wipe | ✅ |
| No analytics, ads or tracking | ✅ |
| Optional app lock (off by default) | ⬜ |
| Local data encrypted so a wipe also beats forensic recovery | ⬜ |
| Content rules shown in the app | ⬜ |

**What Freegram can't protect against, and we say so:** a key can be linked to a person (through what they post, who they meet, or their phone); servers see internet addresses; nearby phones can tell a Freegram phone is close; posts already spread can't be recalled.

## 5. Maintainers

### Role

A maintainer is a trusted person whose **public, signed hide list** people can choose to follow. Maintainers can:

- hide a post or an author for everyone who follows them;
- read private reports and appeals sent to them;
- unhide.

Maintainers **cannot** delete posts, read anyone's private data, see who follows them, or act for users who switched them off.

### Standards

1. Apply the content rules in section 3, not personal views or politics.
2. Hide the smallest thing that fixes the problem: a post before an author.
3. When unsure, leave it up.
4. Answer appeals within **7 days**. The answer is the next version of the public list (unhidden or kept).
5. Don't moderate your own disputes. If a report concerns you or someone close to you, pass it to another maintainer.
6. Keep your key safe: encrypted backup, panic wipe, and a new key if it may be stolen.

### Team

- Start with one maintainer (Sourabh), then grow to 3 or 4, each with their own key.
- New maintainers are added by agreement of the existing ones and announced publicly with their ID.
- A maintainer who breaks these standards is removed from the default list, and users are told.
- Every hide list is public, so anyone can check what a maintainer hid.

### Default maintainer

New installs follow the default maintainer (Sourabh) so the app is not unmoderated from day one. Users can switch this off in Settings → Maintainers. *Not built yet.*

## 6. Illegal content

- **Child sexual abuse material:** hide the author immediately, never download or pass it on, keep only the post ID, and report it as the law requires (see question 5).
- **Other clearly illegal content** (credible threats, non-consensual intimate images): hide first, then decide within the standards.
- **Limits:** we can't delete copies from phones or independent servers. A hide stops it being shown and passed on by everyone who follows the maintainer, and servers can be asked to remove it.
- **Requests from authorities:** we hold no user data (no names, numbers, emails or locations). Anything else waits for legal advice (question 7).

## 7. What we won't do

- Sell or share data, or show ads.
- Add secret hide lists or hidden moderation.
- Build a backdoor to read, trace or post as users.
- Claim Freegram is anonymous or censorship-proof.

## 8. Questions for a lawyer (India)

1. **Are we an intermediary?** With no server of our own, do we (the app developers) count as an "intermediary" under the IT Act 2000, section 79? Does that change once we run photo servers or our own relay?
2. **IT Rules 2021:** which duties apply to us at pilot size (grievance officer, takedown deadlines, monthly reports)? Do "significant social media intermediary" duties such as traceability apply, and at what user count?
3. **Blocking orders (section 69A):** if we receive one for a post or the whole app, what must we do, given we can't remove posts from phones or servers we don't run?
4. **Developer liability:** can the developers or maintainers be held personally liable for what users post, or for building a tool that works during shutdowns?
5. **Child sexual abuse material:** what are our reporting duties (for example under the POCSO Act), and to whom?
6. **Maintainers:** does hiding content make a maintainer an editor who is liable for what they didn't hide? How do we protect maintainers?
7. **Requests from police and courts:** how should we respond, and what must we keep (we hold almost nothing)?
8. **Data protection (DPDP Act 2023):** do we process personal data at all (public keys, public posts), and what notices or consent do we need?
9. **Distribution:** any issues with publishing the APK on our own website, and later on Google Play?
10. **Shutdowns:** is using nearby phone-to-phone sharing during a lawful internet shutdown itself a problem for users or for us?
11. **Legal form:** should the project sit under an individual, a non-profit (trust or Section 8 company), or an entity outside India?
12. **Content law:** which offences under the Bharatiya Nyaya Sanhita 2023 are most likely to be used against users or maintainers, and how do the content rules above need to change?

## 9. Open decisions (for Sourabh)

1. Should the content rules be shown in the app during onboarding, or linked from Settings?
2. Should maintainers be able to label a post (for example "disputed") instead of only hiding it?
3. Appeal deadline: is 7 days right?
4. Is anyone besides Sourabh ready to be a maintainer for the pilot?
5. Which lawyer or legal organisation should review this (for example one working on digital rights)?

## Review triggers

Update this page after legal advice, before the field pilot, when the second maintainer joins, when we run any server, and after any serious incident.
