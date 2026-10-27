# Firebase setup

Ember builds and runs without Firebase. With no `app/google-services.json` it starts in offline
**Preview mode** with local demo profiles. To run the real backend, follow these steps once.
Allow about 30 minutes.

Everything server side lives in [`firebase/`](../firebase). The contract between the app and the
backend is [`docs/DATA_MODEL.md`](DATA_MODEL.md).

## 1. Create the project

1. Open the [Firebase console](https://console.firebase.google.com/) and click **Add project**.
   Enable Google Analytics when asked; Ember logs screen views and key events.
2. Upgrade to the **Blaze (pay as you go)** plan under *Usage and billing*. Cloud Functions,
   scheduled functions and cross-service Storage rules require it. At hobby scale the cost is
   usually zero; set a budget alert anyway.

## 2. Register the Android app (twice)

Debug builds use the package `io.github.arnavdugad.ember.debug`, so they can sit next to a
release install. Register both packages in the same project:

1. *Project settings > General > Your apps > Add app > Android*
   - Package name: `io.github.arnavdugad.ember`
2. Add a second Android app:
   - Package name: `io.github.arnavdugad.ember.debug`

### Signing certificate fingerprints

Google sign-in and Play Integrity need the SHA-1 and SHA-256 of every key that signs the app.
Add the **release** key's fingerprints to the release app, and the **debug** key's to the
`.debug` app.

```bash
# Debug key (created by Android Studio; the password is "android")
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android

# Release key (see docs/RELEASING.md for creating it)
keytool -list -v -keystore /path/to/ember-release.jks -alias ember
```

Copy the `SHA1:` and `SHA256:` lines into *Project settings > Your apps > (app) > Add
fingerprint*.

### google-services.json

Download `google-services.json` after adding the fingerprints. One file contains both apps.
Save it as **`app/google-services.json`**. It is git-ignored; never commit it.
[`app/google-services.json.sample`](../app/google-services.json.sample) shows the expected
shape.

When the file is present, Gradle applies the Google Services and Crashlytics plugins
automatically, and the app leaves Preview mode.

## 3. Turn on the products

| Product | Where | Settings |
|---|---|---|
| **Authentication** | Build > Authentication > Sign-in method | Enable **Email/Password**, **Google** (pick a support email) and **Phone**. For Phone, add test numbers under *Phone numbers for testing* while developing. |
| **Firestore** | Build > Firestore Database | Create in **production mode**; pick a region close to your users. The rules are deployed in step 5. |
| **Storage** | Build > Storage | Get started in **production mode**, same region. If your bucket is `<project>.appspot.com` (older projects), pass `--bucket` to the seed script. |
| **Cloud Messaging** | Run > Messaging | Nothing to configure for Android; FCM is enabled with the project. |
| **Remote Config** | Run > Remote Config | Created on deploy from `firebase/remoteconfig.template.json`. |
| **Crashlytics** | Run > Crashlytics | Click *Enable*. The first crash report arrives after a release build has run once. |
| **Analytics** | Enabled with the project | Nothing else to do. |

## 4. App Check

App Check makes sure requests come from your genuine app.

1. *Build > App Check > Apps*: register **both** Android apps with **Play Integrity**. Play
   Integrity uses the SHA-256 fingerprints from step 2. In Google Play Console, link the Cloud
   project under *Release > App integrity* if prompted.
2. Debug builds use the **debug provider**. On the first launch of a debug build, logcat prints:
   `Enter this debug secret into the allow list in the Firebase Console for your project: <token>`.
   Add that token under *App Check > Apps > (.debug app) > Manage debug tokens*.
3. Once requests show as verified in the App Check metrics, click **Enforce** for Firestore and
   Storage.

### The sideloading caveat

Ember is distributed as an APK on GitHub Releases, not through Google Play. Play Integrity's
app-recognition verdict can fail for apps that Play did not install, so App Check may reject
genuine sideloaded installs.

- Callable Cloud Functions read the `ENFORCE_APP_CHECK` parameter (default `true`). To accept
  calls without a valid token while distributing by APK, create `firebase/functions/.env` with
  `ENFORCE_APP_CHECK=false` before deploying (the Firebase CLI also asks for the value on the
  first deploy).
- Only turn on Firestore and Storage enforcement in the console once your metrics show
  sideloaded installs verifying. Security rules still protect every document either way; App
  Check is an extra layer against scripted abuse, not a substitute for the rules.

## 5. Deploy the backend

```bash
npm install -g firebase-tools
firebase login

cd firebase
cp .firebaserc.example .firebaserc      # then put your project id in it
# or: firebase use --add

npm --prefix functions install
firebase deploy --only firestore,storage,functions,remoteconfig
```

This deploys:
- `firestore.rules` and `firestore.indexes.json`. Indexes can take a few minutes to build;
  discovery returns errors until they finish.
- `storage.rules`
- Twelve Cloud Functions (Node 22, `us-central1`): `completeAgeGate`, `getDiscoveryDeck`,
  `onSwipeCreated`, `rewindSwipe`, `onMessageCreated`, `onAlbumAccessWritten`, `unmatch`,
  `blockUser`, `reportUser`, `deleteAccount`, `refreshAges`, `updateDemoVisibility`
- The Remote Config template (`hide_demo_profiles`, `demo_hide_threshold`)

The first Functions deploy can ask you to enable several Google Cloud APIs (Cloud Build,
Artifact Registry, Eventarc, Cloud Scheduler). Answer yes.

`updateDemoVisibility` publishes Remote Config through the Admin SDK. Its runtime service
account needs the **Firebase Remote Config Admin** role (*Google Cloud console > IAM*; the
default compute service account usually has Editor, which is enough).

### Test the rules locally (optional)

```bash
cd firebase/rules-tests
npm install
npm test      # starts the Firestore and Storage emulators and runs the rules tests
```

The emulators need Java 11 or newer on your PATH.

## 6. Seed the demo profiles

```bash
cd firebase/seed
npm install
# Project settings > Service accounts > Generate new private key -> save as serviceAccount.json
node seed.mjs --project your-project-id
```

This creates 150 illustrated, clearly labelled demo profiles. See
[`firebase/seed/README.md`](../firebase/seed/README.md). Remove them later with `--wipe`. They
are hidden automatically once real users pass `demo_hide_threshold`.

Delete `serviceAccount.json` when you are done. It grants full admin access to the project.

## 7. Let CI include Firebase in release builds

The release workflow writes `app/google-services.json` from a repository secret, so APKs
published on GitHub talk to your project:

```bash
# macOS / Linux
base64 -w0 app/google-services.json    # macOS: base64 -i app/google-services.json
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("app\google-services.json"))
```

Add the output as the secret **`GOOGLE_SERVICES_JSON`** under *GitHub > Settings > Secrets and
variables > Actions*. Without it, CI still builds a working APK, but in Preview mode.

`google-services.json` contains identifiers, not secrets: the API key is restricted by package
name and signing certificate, and access is governed by security rules and App Check. It is
kept out of the repository so forks configure their own project.

## Checklist

- [ ] Project on the Blaze plan
- [ ] Both Android apps registered, SHA-1 and SHA-256 for debug and release keys added
- [ ] `app/google-services.json` in place (and `GOOGLE_SERVICES_JSON` secret for CI)
- [ ] Email/Password, Google and Phone sign-in enabled
- [ ] Firestore and Storage created in production mode
- [ ] App Check registered (Play Integrity + debug token); `ENFORCE_APP_CHECK` decided
- [ ] `firebase deploy --only firestore,storage,functions,remoteconfig` succeeded
- [ ] Demo profiles seeded
