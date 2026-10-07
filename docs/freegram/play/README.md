# Publishing Freegram on Google Play

Everything needed for the Play launch. Files here:

- [PRIVACY.md](PRIVACY.md): privacy policy (Play needs its public link)
- [TERMS.md](TERMS.md): terms of use (also shown in the app)
- [STORE_LISTING.md](STORE_LISTING.md): name, descriptions, category, graphics list
- [DATA_SAFETY.md](DATA_SAFETY.md): answers for Data safety, content rating, target audience, foreground service and permissions

Support email: sourabhsahu69733@gmail.com.

## Already done in the app (version 0.4.0)

- Targets Android 16 (API 36), Google's requirement for new apps and updates since 31 August 2026.
- Builds an Android App Bundle (AAB), signed with the release key.
- Terms shown during setup ("I understand and agree") and in Settings → Rules and terms.
- Report, block, mute and maintainer hiding.
- Settings → Delete my account (asks servers to delete, then erases the phone).
- Default maintainer support: set `FreegramConfig.DEFAULT_MAINTAINER_NPUB` (still empty).

## Steps

### 1. Build

```bash
cd apps/android && ./gradlew :app:bundleRelease
```

Output: `apps/android/app/build/outputs/bundle/release/app-release.aab`. Raise `versionCode` and `versionName` in `app/build.gradle.kts` for every upload.

### 2. Create the app in Play Console

- App name: Freegram. Default language: English (India) or English (US). App, Free.
- Package name comes from the first upload: `org.freegram.app`. It can never change.

### 3. App signing: use our existing key

Website APKs are signed with `~/.freegram/freegram-release.jks`. To let people move between the website APK and Play without losing data, Play must sign with the **same** key.

- Play Console → Test and release → App integrity → App signing → choose to use your own key ("Use a different key" / "Export and upload a key from Java keystore").
- Follow the Console's instructions: it gives a small tool (PEPK) that encrypts `freegram-release.jks` (alias `freegram`) for upload. The password is in the Keychain item `freegram-release-keystore`.
- Do this **before the first release**. It can't be changed afterwards.
- Keep the key file and password backed up. Losing them means no more updates.

### 4. Policy forms

Fill Policy → App content using [DATA_SAFETY.md](DATA_SAFETY.md). Store listing from [STORE_LISTING.md](STORE_LISTING.md). Privacy policy URL: the GitHub link to PRIVACY.md on `main`.

### 5. Closed test: 12 testers for 14 days

New personal developer accounts must run a closed test with at least 12 testers who stay opted in for 14 days in a row before publishing to everyone. Check the exact numbers in your Console.

- Test and release → Testing → Closed testing → create a track, upload the AAB.
- Add testers' Gmail addresses (aim for 15 to 20, in case some drop out) or a Google Group.
- Share the opt-in link. Testers tap "Become a tester", then install from Play.
- Testers who have the website APK can install over it only if step 3 used the same key.
- Ask testers to actually use it: post, reply, try Nearby with a friend. Note their feedback; Google asks about it.

### 6. Apply for production

After 14 days, Dashboard → Apply for production. Answer the questions about the test. Review usually takes a few days.

### 7. Updates

Raise the version, build the AAB, upload to the production track with release notes. Use a staged rollout (for example 20%, then 100%). Keep the website APK on the same version numbers and key.

## Things Play may ask about

- **Foreground service video:** see DATA_SAFETY.md.
- **User-generated content:** point to Report (post menu), Block, maintainers, and the terms during setup.
- **Contact for abuse reports:** sourabhsahu69733@gmail.com, answered within a few days.
