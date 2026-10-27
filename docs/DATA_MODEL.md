# Ember data model

This is the contract between the Android app (`app/`) and the Firebase backend
(`firebase/`). Security rules, Cloud Functions and the app all follow it, so change it here
first.

## Trust model

- The client is never trusted with anything that matters: age, matches, blocks, reports,
  demo flags and account deletion are all decided in Cloud Functions.
- Every signed-in user who passes the age gate gets the custom claim **`adult: true`**.
  Firestore and Storage rules require it for every read of another person's data.
- A match id is the two uids sorted and joined with `_`: `min(a,b) + "_" + max(a,b)`.
  Rules use this to check "are these two matched?" with a single `exists()`.

## Firestore

### `users/{uid}` (public profile)
Readable by any `adult` who has not been blocked by `uid`. Writable only by `uid`, and never the
server-owned fields.

| field | type | notes |
|---|---|---|
| `uid` | string | equals the doc id |
| `name` | string | 1 to 40 chars |
| `age` | number | **server-owned**, set by `completeAgeGate`, refreshed daily |
| `gender` | string | `woman`, `man`, `nonbinary` |
| `interestedIn` | string[] | subset of the genders above |
| `bio` | string | at most 500 chars |
| `photos` | `{id, url, path, blurhash}[]` | at most 6, non-explicit, `path` is the Storage path |
| `prompts` | `{id, question, answer}[]` | at most 3 |
| `interests` | string[] | at most 10 |
| `vibes` | string[] | at most 6, e.g. `flirty`, `night_owl` |
| `lookingFor` | string | `casual`, `dating`, `friends`, `exploring` |
| `heightCm` | number or null | 120 to 230 |
| `city` | string | city name only |
| `geohash` | string | precision 5 (about 5 km), never finer |
| `lat`, `lng` | number | rounded to 2 decimals |
| `afterDark` | bool | opted into After Dark discovery |
| `privateAlbumCount` | number | 0 to 12, maintained by the owner |
| `isDemo` | bool | **server-owned**; seeded demo profiles only |
| `onboarded` | bool | profile setup finished |
| `createdAt`, `updatedAt`, `lastActiveAt` | timestamp | |

### `users/{uid}/matchOnly/details`
`{ boundaries: string[], turnOns: string[] }`. Readable by `uid` and by users matched with
`uid` (checked with the match id). Writable by `uid`.

### `users/{uid}/private/account`
`{ birthDate: "YYYY-MM-DD", termsVersion, termsAcceptedAt, ageVerifiedAt, demoLikesBack? }`.
Readable by `uid` only. **Written only by Cloud Functions.**

### `users/{uid}/tokens/{fcmToken}`
`{ token, platform: "android", updatedAt }`. Owner read and write.

### `users/{uid}/swipes/{targetUid}`
`{ direction: "like" | "nope" | "super", createdAt }`. The owner may **create** (with
`createdAt == request.time`) and read. Updates and deletes go through `rewindSwipe`.

### `users/{uid}/blocks/{targetUid}`
`{ createdAt }`. Owner may read. Written only by Cloud Functions.

### `matches/{matchId}`
| field | type | notes |
|---|---|---|
| `users` | string[2] | both uids |
| `profiles` | map uid -> `{name, photoUrl, blurhash, isDemo}` | snapshot for the list |
| `createdAt` | timestamp | |
| `superLike` | bool | either side super-liked |
| `isDemo` | bool | one side is a demo profile |
| `lastMessage` | `{text, senderId, type, sentAt}` or null | written by `onMessageCreated` |
| `lastRead` | map uid -> timestamp | each participant may update **only their own key** |

Created only by the `onSwipeCreated` function. Readable only by participants.

### `matches/{matchId}/typing/{uid}`
`{ typing: bool, updatedAt }`. Participants read; each writes only their own doc.

### `matches/{matchId}/messages/{messageId}`
| field | type | notes |
|---|---|---|
| `senderId` | string | must equal `request.auth.uid` |
| `type` | string | `text`, `image`, `game` |
| `text` | string | at most 2000 chars (caption for images, prompt for games) |
| `imagePath` | string or null | `chats/{matchId}/{messageId}.jpg` |
| `imageBlurhash` | string or null | |
| `game` | `{mode: "truth" or "dare", prompt}` or null | Truth or Dare card |
| `createdAt` | timestamp | must equal `request.time` |

Participants read; participants create; nobody updates or deletes (unmatch removes the thread
server side). Read receipts come from `matches/{id}.lastRead`. Demo profiles never send
messages: no code path writes a message whose `senderId` is a demo uid.

### `albums/{ownerUid}/photos/{photoId}`
`{ path: "private/{ownerUid}/{photoId}.jpg", createdAt }`. Read by the owner and by viewers
whose access doc is `approved`. Written by the owner.

### `albums/{ownerUid}/access/{viewerUid}`
`{ ownerId, viewerId, status: "pending" | "approved" | "denied", requestedAt, updatedAt }`.
- The viewer may **create** it with `status: "pending"`, and only if matched with the owner.
- The owner may **update** `status` to `approved` or `denied`.
- Either side may **delete** it (revoke). Revoking takes effect at once because Storage rules
  read this document on every request.

### `reports/{reportId}`
`{ reporterId, targetId, reason, details, matchId, createdAt, status: "open" }`. Written only
by `reportUser`. Never readable by clients.

Reasons: `fake_profile`, `inappropriate_photos`, `harassment`, `underage`, `spam_scam`,
`offensive_messages`, `safety_concern`, `other`.

## Cloud Storage

| path | read | write |
|---|---|---|
| `users/{uid}/photos/{photoId}.jpg` | any `adult` | owner; image/*, at most 10 MB |
| `private/{ownerUid}/{photoId}.jpg` | owner, or a viewer whose `albums/{owner}/access/{viewer}.status == "approved"` | owner; image/*, at most 10 MB |
| `chats/{matchId}/{messageId}.jpg` | match participants | match participants; image/*, at most 10 MB |
| `demo/{uid}/{n}.png` | any `adult` | nobody (seed script uses the Admin SDK) |

## Callable Cloud Functions

All callables require auth and App Check (enforcement is configurable, see
`docs/FIREBASE_SETUP.md`). Region `us-central1`.

| name | input | output |
|---|---|---|
| `completeAgeGate` | `{ birthDate: "YYYY-MM-DD", termsVersion: string }` | `{ age }`, or the `failed-precondition` error with message `underage` (the account is then disabled and its data deleted) |
| `getDiscoveryDeck` | `{ maxDistanceKm, minAge, maxAge, lookingFor: string[], afterDark: bool, limit }` | `{ profiles: Profile[] }`, each with `distanceKm` |
| `rewindSwipe` | `{ targetId }` | `{ ok }`. Only the most recent swipe, within 10 minutes, and never one that produced a match |
| `unmatch` | `{ matchId }` | `{ ok }`. Deletes the match, its messages, chat images and album access both ways |
| `blockUser` | `{ targetId }` | `{ ok }`. Writes both block docs and unmatches |
| `reportUser` | `{ targetId, reason, details, matchId?, block: bool }` | `{ ok }` |
| `deleteAccount` | `{}` | `{ ok }`. Deletes the profile, subcollections, swipes, matches, messages, albums, Storage files and the Auth user |

## Triggers and schedules

| name | trigger | does |
|---|---|---|
| `onSwipeCreated` | `users/{uid}/swipes/{targetId}` created | On like or super: if the target already liked back (or is a demo profile with `demoLikesBack`) and neither blocked the other, creates the match in a transaction and sends an FCM `match` push to real users |
| `onMessageCreated` | `matches/{id}/messages/{msgId}` created | Updates `lastMessage`, sends an FCM `message` push to the other participant |
| `onAlbumAccessWritten` | `albums/{owner}/access/{viewer}` written | Pushes an `album` notification on a new request or an approval |
| `refreshAges` | daily | Recomputes `age` from `private/account.birthDate` |
| `updateDemoVisibility` | hourly | Counts non-demo onboarded users. Above Remote Config `demo_hide_threshold`, sets `hide_demo_profiles = true` |

## FCM payload

Data message: `{ type: "match" | "message" | "album", matchId?, title, body }`, plus a
notification block. Android channels: `matches`, `messages`, `album`.

## Remote Config

| key | type | default |
|---|---|---|
| `hide_demo_profiles` | bool | `false` |
| `demo_hide_threshold` | number | `500` |
