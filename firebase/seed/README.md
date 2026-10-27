# Ember demo seed

Creates 150 fictional demo profiles so the deck, matching and chat can be tested before real
people sign up.

- **No real people.** Every photo is an illustrated avatar rendered locally by
  [DiceBear](https://www.dicebear.com/) using the CC0-licensed `lorelei` and `notionists`
  styles, on an Ember gradient. Nothing is downloaded or scraped, and nothing is explicit.
- **Always labelled.** Every profile has `isDemo: true`, and the app shows a "Demo" badge on it.
- **Never chatty.** Demo profiles can match (about half of them "like back", controlled by
  `private/account.demoLikesBack`), but no code path ever sends a message as a demo profile.
- **Hidden automatically.** Once real onboarded users reach the Remote Config value
  `demo_hide_threshold` (default 500), the `updateDemoVisibility` function sets
  `hide_demo_profiles = true` and the deck stops serving them.
- **Deterministic.** Each run with the same `--count` produces the same names, ages, cities and
  illustrations.

The vocabularies (`prompts.json`, `catalog.json`) mirror the app's
`app/src/main/java/io/github/arnavdugad/ember/data/model/Catalog.kt`. If you change one, change
the other.

## Run it

```bash
cd firebase/seed
npm install

# Preview locally: renders PNGs and profiles.json into .cache/, touches nothing remote.
node seed.mjs --dry-run --count 6

# Seed for real. Needs a service account key (Project settings > Service accounts >
# Generate new private key) saved as serviceAccount.json here (git-ignored), or
# GOOGLE_APPLICATION_CREDENTIALS pointing at it.
node seed.mjs --project your-project-id

# Remove every demo profile, their matches, chats and photos.
node seed.mjs --wipe --project your-project-id
```

| flag | default | meaning |
|---|---|---|
| `--count` | 150 | number of profiles |
| `--dry-run` | off | render only, into `.cache/` |
| `--wipe` | off | delete demo data instead of creating it |
| `--project` | from the service account | Firebase project id |
| `--bucket` | `<project>.firebasestorage.app` | Storage bucket (older projects use `<project>.appspot.com`) |
| `--concurrency` | 6 | parallel renders and uploads |

Profiles are written to `users/demo-001` through `users/demo-150` with `private/account`
(birth date, `demoLikesBack`) and `matchOnly/details` (boundaries and turn-ons). Photos go to
`demo/{uid}/{n}.png`. Running the seed again overwrites the same documents.
