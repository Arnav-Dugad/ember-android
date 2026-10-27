# Ember data model (Spark plan, no Cloud Functions)

This is the contract between the Android app (`app/`) and the Firebase backend (`firebase/`).
Ember runs entirely on Firebase's free **Spark** plan: Auth, Firestore and Remote Config. There
are no Cloud Functions and no Cloud Storage (both need the paid Blaze plan), so:

- **Security rules are the server.** Everything the old functions decided (age, matches, who
  may see what) is enforced in `firestore.rules`. The client is still never trusted: it can only
  do what the rules allow.
- **Images live in Firestore** as compressed JPEG blobs (at most 900 KB per document), so the
  same rules that protect profiles also protect photos, chat images and private albums.
- **No push notifications**: sending FCM pushes needs a server. Chat and matches are real-time
  while the app is open.

Change this file first, then the rules, then the app.

## Identity and age

- `users/{uid}/private/account` = `{ birthYear, birthMonth, birthDay, termsVersion, termsAcceptedAt }`.
  - The owner creates it **once** and it is immutable. Its create rule only passes when the
    birth date is a real date at least 18 years before `request.time` (and at most 100).
  - Readable by the owner only.
- **Verified adult** means "has `users/{me}/private/account`". Rules call it `isAdult()`, and every
  read of someone else's data requires it.
- An under-18 date simply cannot be written. The app then deletes the Auth user and locks the
  device (`ageGateBlocked`).
- A match id is the two uids sorted and joined with `_`: `min(a,b) + "_" + max(a,b)`.

## Firestore

### `users/{uid}` (public profile)
Readable (get and list) by any adult. List queries must have `limit <= 300`. Writable only by
`uid`, and only after `private/account` exists.

| field | type | notes |
|---|---|---|
| `uid` | string | equals the doc id |
| `name` | string | 1 to 40 chars |
| `age` | int | must equal the age computed in rules from `private/account` at write time; the app rewrites it on launch so it stays current |
| `gender` | string | `woman`, `man`, `nonbinary` |
| `interestedIn` | string[] | subset of the genders above |
| `bio` | string | at most 500 chars |
| `photos` | `{id, url, blurhash}[]` | at most 6. `url` is `https://...` (demo) or `ember-fs://images/{imageId}` |
| `prompts` | `{id, question, answer}[]` | at most 3 |
| `interests` | string[] | at most 10 |
| `vibes` | string[] | at most 6 |
| `lookingFor` | string | `casual`, `dating`, `friends`, `exploring` |
| `heightCm` | int or null | 120 to 230 |
| `city` | string | at most 80 chars |
| `geohash` | string | at most 5 chars |
| `lat`, `lng` | number | at most 2 decimals |
| `afterDark` | bool | |
| `privateAlbumCount` | int | 0 to 12 |
| `isDemo` | bool | clients may only write `false` or omit it; seeded profiles have `true` |
| `onboarded` | bool | |
| `hideDistance` | bool, optional | Privacy switch. When `true`, apps show others only `city`, never "N km away". `lat`/`lng` stay in the doc (city-level) so discovery can still filter by distance |
| `paused` | bool, optional | Privacy switch. When `true`, the profile is left out of everyone's discovery deck; existing matches and chats carry on |
| `createdAt`, `updatedAt`, `lastActiveAt` | timestamp | |

### `images/{imageId}` (public profile photos)
`{ owner, data: bytes (JPEG, at most 900 KB), createdAt }`. Readable by any adult. The owner
creates and deletes; no updates.

### `users/{uid}/matchOnly/details`
`{ boundaries: string[], turnOns: string[] }`. Readable by `uid` and by users matched with
`uid`. Writable by `uid`.

### `users/{uid}/swipes/{targetUid}`
`{ direction: "like" | "nope" | "super", createdAt == request.time }`. The owner creates and
reads. The owner may delete (rewind) within 10 minutes of `createdAt`, and only if no match with
`targetUid` exists.

### `users/{targetUid}/likedBy/{likerUid}`
`{ createdAt == request.time, super: bool }`. Created by the liker (`auth.uid == likerUid`), who
may also delete it (rewind, account deletion). **Nobody can read these docs**; rules use
`exists()` on them to authorize match creation.

### `users/{uid}/blocks/{targetUid}`
`{ createdAt }`. The owner creates, reads and deletes.

### `demoMeta/{uid}`
`{ likesBack: bool }`. Written only by the seed script (Admin SDK). Nobody can read it.

### `matches/{matchId}`
| field | type | notes |
|---|---|---|
| `users` | string[2] | sorted `[a, b]`; `matchId == a + "_" + b` |
| `profiles` | map uid -> `{name, photoUrl, blurhash, isDemo}` | snapshot for the list |
| `createdAt` | timestamp | `== request.time` |
| `superLike` | bool | |
| `isDemo` | bool | |
| `lastMessage` | `{text, senderId, type, sentAt}` or null | |
| `lastRead` | map uid -> timestamp | |

- **Create:** a participant may create it, with `lastMessage == null` and `lastRead == {}`, only if:
  - `users/{me}/likedBy/{other}` and `users/{other}/likedBy/{me}` both exist, **or** the other
    user is a demo profile with `demoMeta/{other}.likesBack == true` and `users/{other}/likedBy/{me}` exists;
  - and neither user has blocked the other.
- **Update:** a participant may change only `lastRead.{self}` (`<= request.time`) and/or
  `lastMessage` (with `senderId == self`, `sentAt == request.time`).
- **Delete:** any participant (unmatch).
- **Read:** participants only.

### `matches/{matchId}/messages/{messageId}`
`{ senderId == auth.uid, type: "text"|"image"|"game", text (<= 2000), imageId (null unless image),
imageBlurhash, game: {mode, prompt} or null, createdAt == request.time }`.
- **Create:** participants, while the match exists and neither has blocked the other.
- **Read:** participants.
- **Delete:** participants (unmatch cleanup). No updates.

### `matches/{matchId}/images/{imageId}`
`{ owner == auth.uid, data: bytes (<= 900 KB), createdAt }`. Participants create, read and delete.

### `matches/{matchId}/typing/{uid}`
`{ typing: bool, updatedAt == request.time }`. Participants read; each writes and deletes only their own.

### `albums/{ownerUid}/photos/{photoId}`
`{ data: bytes (<= 900 KB), createdAt }`. Read by the owner and by viewers whose access doc is
`approved`. Written and deleted by the owner.

### `albums/{ownerUid}/access/{viewerUid}`
`{ ownerId, viewerId, status: "pending" | "approved" | "denied", requestedAt, updatedAt }`.
- The viewer may create it with `status: "pending"`, and only if matched with the owner.
- The owner may update `status` to `approved` or `denied`.
- Either side may read and delete (revoke). Revoking is instant: rules read this document on every photo read.

### `reports/{reportId}`
`{ reporterId == auth.uid, targetId, reason (enum), details (<= 1000), matchId or null, createdAt == request.time, status == "open" }`.
Create only. Nobody can read, update or delete.

- **Id:** `{reporterId}_{yyyy-MM-dd}_{n}`, where the date is today in UTC and `n` is the value of
  today's report counter (below) after this report.
- **Daily limit:** at most **20 reports per reporter per UTC day**. A report is only accepted in
  the same batch as a +1 on `users/{me}/limits/{today}`; the rules compare the counter before
  (`get`) and after (`getAfter`) the batch and check that the report id names the new value.

### `users/{uid}/limits/{yyyy-MM-dd}`
`{ reports: int }`: how many reports `uid` has sent on that UTC day.
- The doc id must equal the UTC date of `request.time`, so only today's counter can be written.
- Created with `reports == 1`, then only ever incremented by exactly 1, up to 20, and each step
  requires the matching report (`reports/{uid}_{day}_{reports}`) to exist after the batch.
- Readable by the owner. Never deleted (not even with the account), so the limit can't be reset.

Likes are intentionally not rate-limited.

Reasons: `fake_profile`, `inappropriate_photos`, `harassment`, `underage`, `spam_scam`,
`offensive_messages`, `safety_concern`, `other`.

## Client-side flows

- **Discovery:** query `users` with `onboarded == true`, ordered by `lastActiveAt` descending,
  limit 300 (composite index). The client then drops itself, swiped, blocked and paused people,
  and hidden demos, and filters by age, distance, mutual gender interest, lookingFor and After Dark.
  (`paused` is filtered on the device rather than in the query so the existing index still
  serves, and so profiles written before the field existed still show up.)
- **Distance label:** cards and profiles show "N km away" only when the profile's
  `hideDistance` is not `true`; otherwise just the city.
- **Download my data:** the app reads everything the signed-in user can read about themselves
  (login, account doc, profile, match-only details, swipes, blocks, report counters, album
  photo ids and access, matches and the messages they sent) and saves it as a JSON file through
  the system file picker. Image bytes are left out; nothing is sent anywhere.
- **Like:**
  1. Write the swipe and `users/{target}/likedBy/{me}`.
  2. Try to create the match. The rules reject it until the like is mutual (or the target is a
     demo that likes back), so a permission error here just means "no match yet".
  3. When the other person likes back, their client creates the match.
- **Unmatch:** delete the messages, chat images, typing docs and match doc, plus album access both ways.
- **Block:** write the block doc, then unmatch.
- **Report:** read today's counter (`users/{me}/limits/{UTC date}`, from the server), then in one
  batch set it to `n + 1` and create `reports/{me}_{date}_{n + 1}`. At 20 the app says so instead
  of writing (blocking still works). A lost race (two reports at once) retries once.
- **Delete account:** client-side deletes of everything the user owns or created (see
  `FirebaseSafetyRepository`), then `FirebaseUser.delete()`. Reports and report counters are kept.

## Remote Config

| key | type | default |
|---|---|---|
| `hide_demo_profiles` | bool | `false`; flip it by hand once enough real people have joined |
