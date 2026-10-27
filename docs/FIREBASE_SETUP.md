# Firebase setup (free Spark plan)

Ember runs entirely on Firebase's free **Spark** plan. It uses Authentication, Cloud Firestore
and Remote Config. There are **no Cloud Functions and no Cloud Storage** (both need the paid
Blaze plan):

- the Firestore security rules do the server's job;
- photos are stored as compressed JPEG bytes inside Firestore documents.

The contract is [DATA_MODEL.md](DATA_MODEL.md).

Without any of this the app still builds and runs in offline **Preview mode**.

## 1. Create the project

1. Create a project in the [Firebase console](https://console.firebase.google.com) (or with
   `firebase projects:create`). Stay on the Spark plan.
2. Add two Android apps, `io.github.arnavdugad.ember` (release) and
   `io.github.arnavdugad.ember.debug` (debug builds). Register both SHA-1 and SHA-256 fingerprints
   for each. Google sign-in needs them.
   ```bash
   # debug key
   keytool -list -v -keystore ~/.android/debug.keystore -storepass android
   # release key (the one in the KEYSTORE_BASE64 secret)
   keytool -list -v -keystore ember-release.jks
   ```
   With the CLI: `firebase apps:create ANDROID ...` then `firebase apps:android:sha:create <appId> <sha>`.
3. Download `google-services.json` (it contains both apps), for example with
   `firebase apps:sdkconfig ANDROID <appId> -o app/google-services.json`.
   - Put it at `app/google-services.json`. It is git-ignored; `app/google-services.json.sample`
     shows the expected shape.
   - Download it again after enabling Google sign-in: that adds the OAuth clients.

## 2. Authentication

Authentication → Get started, then enable:

- **Email/Password**
- **Google** (pick a support email)
- **Phone**. SMS sign-in **may not work on Spark** for newer projects. If it fails, email and
  Google still work.

## 3. Firestore, rules, indexes and Remote Config

```bash
npm install -g firebase-tools
firebase login
cd firebase
cp .firebaserc.example .firebaserc        # then put your project id in it
firebase deploy --only firestore,remoteconfig
```

The first deploy creates the Firestore database if it doesn't exist yet. It deploys:

- `firestore.rules`: age checks, mutual-like matching, blocks, private album access, image size limits;
- `firestore.indexes.json`: the discovery index `users(onboarded, lastActiveAt desc)`;
- `remoteconfig.template.json`: `hide_demo_profiles`, which you flip by hand once enough real people have joined.

The rules are covered by tests that run on the Firestore emulator. These need Java 21+, for
example Android Studio's bundled JBR:

```bash
cd firebase/rules-tests && npm install && npm test
```

## 4. Seed demo profiles

```bash
cd firebase/seed
npm install
node seed.mjs --dry-run --count 6                          # local preview, touches nothing
node seed.mjs --cli-auth --project <your-project-id>       # 150 demo profiles, using your `firebase login`
node seed.mjs --wipe --cli-auth --project <your-project-id>
```

Instead of `--cli-auth` you can save a service-account key as `firebase/seed/serviceAccount.json`
(git-ignored; Project settings → Service accounts → Generate new private key), or point
`GOOGLE_APPLICATION_CREDENTIALS` at one.

## 5. Release builds with Firebase

```bash
base64 -w0 app/google-services.json | gh secret set GOOGLE_SERVICES_JSON
```

Without this secret, release APKs run in Preview mode.

## 6. App Check (optional)

The app installs App Check (Play Integrity in release builds, the debug provider in debug
builds). Leave Firestore enforcement **off**: copies installed from GitHub instead of the Play
Store usually fail Play Integrity, so enforcing it would lock those users out.

## Limits of the free setup

- **No push notifications.** Sending FCM messages needs a server. Matches and messages update in
  real time while the app is open.
- **Discovery reads at most 300 profiles per refresh** (the most recently active) and filters
  them on the device.
- **Blocking** removes the match and stops messages and new matches both ways. It can't hide
  your profile from the other person's discovery query (rules can't filter list results per
  document); the app drops blocked people from the deck.
- **Spark quotas:** 50,000 document reads, 20,000 writes and 20,000 deletes per day, and 1 GiB
  stored. Plenty for a personal app; each deck refresh costs about 300 reads.
- **Images:** at most 900 KB each (the app re-encodes photos to about 150 to 300 KB JPEG).
