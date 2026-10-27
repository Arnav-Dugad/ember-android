# Ember demo seed

Creates 150 fictional demo profiles so the deck, matching and chat can be tested before real
people sign up. It uses Firestore only: on the free Spark plan there is no Cloud Storage.

- **No real people.** Each profile's main photo is an AI-generated portrait of someone who does
  not exist (`portraits/`, see its README), served from this repository through jsDelivr. The
  other photos are [DiceBear](https://www.dicebear.com/) `notionists` illustrations (CC0).
  Nothing is scraped, and nothing is explicit.
- **Always labelled.** Every profile has `isDemo: true`, and the app shows a "Demo" badge on it.
- **Never chatty.** About half the demo profiles "like back" (`demoMeta/{uid}.likesBack`, which the
  security rules read when a match is created), but nothing ever sends a message as a demo profile.
- **Hidden by hand.** Set the Remote Config value `hide_demo_profiles` to `true` once enough real
  people have joined.
- **Deterministic.** Each run with the same `--count` produces the same names, ages and cities.

The vocabularies (`prompts.json`, `catalog.json`) mirror the app's
`app/src/main/java/io/github/arnavdugad/ember/data/model/Catalog.kt`. Prompt answers and bios live
in `shared/`, which the app also bundles for Preview mode.

## Run it

```bash
cd firebase/seed
npm install

# Preview: writes .cache/profiles.json and touches nothing remote.
node seed.mjs --dry-run --count 6

# Seed for real with your Firebase CLI login (`firebase login`).
node seed.mjs --cli-auth --project your-project-id

# Remove every demo profile and every match with one.
node seed.mjs --wipe --cli-auth --project your-project-id
```

| flag | default | meaning |
|---|---|---|
| `--count` | 150 | number of profiles |
| `--dry-run` | off | write `.cache/profiles.json` only |
| `--wipe` | off | delete demo data instead of creating it |
| `--cli-auth` | off | use the Firebase CLI's login (needs `--project`) |
| `--project` | from the service account | Firebase project id |

Credentials, in order: `serviceAccount.json` here (git-ignored), then `GOOGLE_APPLICATION_CREDENTIALS`,
then `--cli-auth`. With `--cli-auth`, the CLI's refresh token goes into a private temp file in the
"authorized_user" format, which is deleted when the script exits.

Writes: `users/demo-001` … `users/demo-150`, each with `private/account` (birth date) and
`matchOnly/details`, plus `demoMeta/{uid}`. Running it again overwrites the same documents.
