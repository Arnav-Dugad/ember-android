<div align="center">

<img src="docs/media/icon.png" width="96" alt="Ember icon" />

# Ember

**Flirty, fun and adventurous connections. For grown-ups only (18+).**

A swipe-based dating app for Android with a glassmorphic, Apple-inspired design: physics-based cards, a warm "after dark" palette, private albums with consent built in, and a Firebase backend that never trusts the client.

<a href="https://github.com/Arnav-Dugad/ember-android/releases/latest">
  <img src="https://img.shields.io/badge/Download-APK-FF5E57?style=for-the-badge&logo=android&logoColor=white" alt="Download APK" height="56" />
</a>

[![Release](https://img.shields.io/github/v/release/Arnav-Dugad/ember-android?style=flat-square&color=FF3D7F)](https://github.com/Arnav-Dugad/ember-android/releases/latest)
[![CI](https://img.shields.io/github/actions/workflow/status/Arnav-Dugad/ember-android/ci.yml?branch=main&style=flat-square&label=CI)](https://github.com/Arnav-Dugad/ember-android/actions/workflows/ci.yml)
![minSdk](https://img.shields.io/badge/minSdk-26-120A12?style=flat-square)

</div>

<!-- Real captures from the release build (Preview mode). Drop a demo GIF at docs/media/demo.gif and link it here. -->
| Welcome | Discover | Swiping | It's a match |
|---|---|---|---|
| ![Welcome](docs/media/welcome.webp) | ![Discover](docs/media/discover.webp) | ![Swipe](docs/media/swipe.webp) | ![Match](docs/media/match.webp) |
| **Profile** | **Matches** | **Truth or Dare** | **Light theme** |
| ![Profile](docs/media/profile.webp) | ![Matches](docs/media/matches.webp) | ![Chat](docs/media/chat.webp) | ![Light](docs/media/light.webp) |

> **Install:** download the APK from the latest release and open it. Android will ask you to allow installs from your browser or file manager the first time. After that, Ember updates itself: it checks GitHub Releases on launch and once a day, then downloads, verifies and installs new versions.

## Preview mode vs. Firebase

Ember runs in two modes, picked automatically at startup:

| | Preview mode | Firebase mode |
|---|---|---|
| When | the build has no `google-services.json` | `app/google-services.json` is present |
| Accounts | one local demo user | Email, Google and phone sign-in |
| People | 72 demo profiles: AI-generated portraits of people who don't exist, plus illustrations | real users, plus seeded demo profiles until real sign-ups pass a threshold |
| Data | your profile is saved on the device; matches and chats reset when the app closes | Firestore (free Spark plan): data and photos |

That lets anyone try the full UI from the release APK before you set up a Firebase project. Demo profiles always wear a **Demo** badge, can match with you for testing, and **never** send messages.

## Features

- **Onboarding:** 18+ age gate (enforced by security rules: an under-18 birth date can't be saved), terms and community guidelines acceptance, then a seven-step profile wizard with a searchable city picker.
- **Profiles:** up to 6 public photos (EXIF and GPS stripped on upload), bio, cheeky prompts, vibes, interests, height, a 30-second voice intro, city-level location only, and what you're looking for. A profile-strength meter suggests the next best improvement and throws confetti at 100%.
- **Match-only details:** boundaries and turn-ons that only your matches can read (enforced by security rules).
- **Discovery:** physics swipe deck (rotation around the grab point, velocity flings, spring return, LIKE / NOPE / SUPER stamps, heart burst and nope shake, gyroscope tilt, deal-in animation), rewind, distance, age and intent filters, pull-to-refresh, and swipes that keep working offline.
- **Today's picks, Free tonight and Travel mode:** five daily picks chosen from shared interests and vibes, a 🌙 badge for people free in the next 6 hours, and "browse from" any city without changing your profile.
- **Likes you:** a grid of everyone who liked you, frosted until you match.
- **After Dark:** a darker palette that also limits discovery to people open to casual or adventurous connections.
- **Matching:** created only when likes are mutual, checked by security rules. The match screen has flying photos, an ember burst and a spring headline.
- **Chat:** real-time messages, reactions, replies, view-once photos, voice notes, a date planner built from shared interests, typing indicator, read receipts, Truth or Dare cards, and gentle safety nudges when someone asks for money or to move off the app. Messages sent offline go out when you reconnect, and a background check posts local notifications for new messages.
- **Matches list:** NEW and "Your turn" badges, swipe to archive or unmatch, and a 10-second undo.
- **Private album:** photos are invisible until the owner approves a match, and access can be revoked instantly. Security rules check access on every photo read, screenshots are blocked, and viewers see a consent reminder first.
- **Safety and privacy:** block (which also hides you from them), unmatch, report (8 reasons, 20 a day), hide distance, pause your profile, download your data, private lock-screen notifications, and full account deletion.
- **Design:** Haze backdrop blur with a frosted fallback below Android 12, continuous-curvature corners, Manrope typography, an ambient glow tinted by the current photo (Palette API), and haptics on thresholds, matches and presses.
- **Self-update:** an in-app updater backed by GitHub Releases. It checks the SHA-256 of each download, plus its package name and signing certificate, before installing. Add `[mandatory]` to the release notes to require an update.

## Tech

Kotlin, Jetpack Compose, Material 3 (heavily restyled), MVVM, Hilt, Coroutines and Flow, Navigation Compose with type-safe routes and shared elements, Coil 3 (with BlurHash placeholders), Haze 2, DataStore, WorkManager, OkHttp, and Baseline Profiles.

Firebase, entirely on the free **Spark** plan: Auth, Firestore (which also stores photos as compressed blobs), Remote Config, App Check, Crashlytics and Analytics. There are no Cloud Functions or Cloud Storage: security rules do the server's job (age verification, mutual-like matching, private-album access).

```
app/                 Android app (single module, package-by-feature)
  core/              design system (glass, shapes, motion, haptics), image, util, analytics
  data/              models, repository interfaces, Firebase + Preview implementations
  ui/                auth, onboarding, discover, profile, matches, chat, album, safety, settings
  update/            GitHub Releases self-updater (WorkManager + install sheet)
baselineprofile/     Baseline Profile generator (macrobenchmark)
firebase/            security rules, indexes, rules tests, seed script and AI demo portraits
docs/                data model, Firebase setup, releasing
```

## Build

Requirements: JDK 17+ (Android Studio's bundled JBR works) and the Android SDK with API 37.

```bash
./gradlew assembleDebug        # debug build, Preview mode unless app/google-services.json exists
./gradlew assembleRelease      # release build (debug-signed unless a keystore is configured)
./gradlew testDebugUnitTest
```

Local signed builds: copy `keystore.properties.example` to `keystore.properties` and fill it in. Both files stay out of git.

## Firebase setup

Follow **[docs/FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md)**. In short:

1. Create a Firebase project (the free Spark plan is enough), then add Android apps `io.github.arnavdugad.ember` and `io.github.arnavdugad.ember.debug`.
2. Download `google-services.json` to `app/` (git-ignored; see `app/google-services.json.sample` for its shape).
3. Enable the Auth providers (Email/Password, Google, and Phone, though phone SMS may need a paid plan) and Firestore.
4. Deploy rules, indexes and Remote Config with `cd firebase && firebase deploy --only firestore,remoteconfig`.
5. Seed 150 demo profiles: `cd firebase/seed && npm i && node seed.mjs --cli-auth --project <project-id>`, using your `firebase login` (or put a `serviceAccount.json` next to the script).

The contract between the app and the backend is documented in [docs/DATA_MODEL.md](docs/DATA_MODEL.md).

## Releasing

See **[docs/RELEASING.md](docs/RELEASING.md)**. Tag `vX.Y.Z` and push it. GitHub Actions then builds an APK signed with the same key every time (from repository secrets), derives `versionName`/`versionCode` from the tag, and publishes the release with auto-generated notes.

## Safety and content

Ember is for adults. Public photos must be non-explicit, and private content is shared only by explicit, revocable consent. Demo profiles use 60 AI-generated portraits of fictional adults (firebase/seed/portraits, made with Figma AI) and CC0 DiceBear illustrations. None of them are real people, and nothing is explicit.

## License

MIT. Manrope is licensed under the SIL Open Font License ([docs/FONT_LICENSE_OFL.txt](docs/FONT_LICENSE_OFL.txt)).
