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
  while the app is open; in the background the app posts local notifications itself, from a
  listener while its process is alive and from a 15-minute WorkManager check after that.

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
| `freeTonightUntil` | timestamp or null | "Free tonight" badge. At most `request.time` + 6 h (plus 10 min of clock slack); the app writes now + 6 h, or deletes the field to turn it off |
| `voiceDurationMs` | int or null | length of the voice intro in `voices/{uid}`, 500 to 31000 (30 s plus slack). Absent or null when there is none |
| `lastActiveAt` | timestamp | the heartbeat ("Online now"). Never after `request.time` (it orders the deck, so a future value would pin a profile to the top). While `hideActivity` is `true` it must be a UTC midnight: the day, never the time |
| `hideActivity` | bool, optional | Privacy switch ("Show when I'm active" off). Apps show no online dot or "Active 2h ago" for this person, and this person sees nobody's |
| `spotlightUntil` | timestamp or null | Spotlight: first in everyone's deck until then. At most `request.time` + 30 min, and only set (to a non-null value) in the same batch as a step on today's Spotlight counter (below), so once per UTC day. Null or deleted turns it off at any time. Never set on create |
| `createdAt`, `updatedAt` | timestamp | |

### `images/{imageId}` (public profile photos)
`{ owner, data: bytes (JPEG, at most 900 KB), createdAt }`. Readable by any adult. The owner
creates and deletes; no updates.

### `voices/{uid}` (voice intro)
`{ owner == uid, data: bytes (AAC in MPEG-4, mono, about 32 kbps; at most 900 KB), durationMs: int (500 to 31000), createdAt == request.time }`.
One clip per user, overwritten on re-record. Readable (get only) by the owner and by adults the
owner hasn't blocked. The owner creates, updates and deletes.

### `users/{uid}/matchOnly/details`
`{ boundaries: string[], turnOns: string[] }`. Readable by `uid` and by users matched with
`uid`. Writable by `uid`.

### `users/{uid}/swipes/{targetUid}`
`{ direction: "like" | "nope" | "super", createdAt == request.time, note? }`. The owner creates and
reads. The owner may delete (rewind) within 10 minutes of `createdAt`, and only if no match with
`targetUid` exists. `note` (likes only, never on a nope) is the owner's own copy of the like's
note, read back to post it as the first message if the other person completes the match.

### `users/{targetUid}/likedBy/{likerUid}`
`{ createdAt == request.time, super: bool, note? }`. Created by the liker (`auth.uid == likerUid`), who
may also delete it (rewind, account deletion). **Only `targetUid` (a verified adult) can read
them** (get and list), for the "Likes you" screen; nobody else, not even the liker. Rules also use
`exists()` on them to authorize match creation.

**Like notes.** A like aimed at one prompt or photo carries
`note: { kind: "prompt" | "photo", ref, text }`, all three required and nothing else: `ref` is the
prompt or photo id (`^[A-Za-z0-9_-]{1,128}$`) and `text` an optional comment of at most 140
characters (may be `""`). Rules can't look inside the profile's arrays, so they don't check that
`ref` exists; the app shows the note without the quote when it doesn't.

### `users/{uid}/blocks/{targetUid}`
`{ createdAt }`. The owner creates, reads and deletes.

### `users/{uid}/hiddenBy/{blockerUid}`
`{ createdAt == request.time }`. Written by the blocker (`auth.uid == blockerUid`) in the same
batch as their block (`existsAfter(users/{blockerUid}/blocks/{uid})`), so the blocked person's
deck drops the blocker too. Readable by `uid` only; deletable by the blocker only (account
deletion); never updated.

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
| `lastMessage` | `{text, senderId, type, sentAt}` or null | `type` as for messages |
| `lastRead` | map uid -> timestamp | |

- **Create:** a participant may create it, with `lastMessage == null` and `lastRead == {}`, only if:
  - `users/{me}/likedBy/{other}` and `users/{other}/likedBy/{me}` both exist, **or** the other
    user is a demo profile with `demoMeta/{other}.likesBack == true` and `users/{other}/likedBy/{me}` exists;
  - and neither user has blocked the other.
- **Update:** a participant may change only `lastRead.{self}` (`<= request.time`) and/or
  `lastMessage` (with `senderId == self`, `sentAt == request.time`). The one other `lastMessage`
  write is the unsend swap (see "Unsend" below): when the current preview is my own and under
  15 minutes old, I may replace it with `{text: "Message unsent", senderId: self, type: "text",
  sentAt: <unchanged>}`, so the chat neither jumps up the list nor turns unread.
- **Delete:** any participant (unmatch).
- **Read:** participants only.

### `matches/{matchId}/messages/{messageId}`
| field | type | notes |
|---|---|---|
| `senderId` | string | `== auth.uid` |
| `type` | string | `text`, `image`, `game`, `voice`, `date`, or `event` (see "Event rows") |
| `text` | string | at most 2000; at least 1 for `text`. Images may carry a caption, dates carry the title, event rows their fixed fallback text |
| `imageId` | string or null | required for `image` (the image doc must exist after the write), null otherwise |
| `imageBlurhash` | string or null | at most 100 chars |
| `game` | `{mode: "truth"\|"dare", prompt (<= 300)}` or null | required for `game`, null otherwise |
| `viewOnce` | bool, optional | `image` only. The recipient opens it once, then deletes the image doc |
| `viewedAt` | timestamp, optional | never written on create; see updates |
| `audioId` | string or null | required for `voice` (the audio doc must exist after the write), null otherwise |
| `durationMs` | int or null | `voice` only, 1 to 61000 |
| `date` | `{title (1..80), detail (<= 200), emoji (<= 16)}` or null | required for `date`, null otherwise |
| `replyTo` | `{id, senderId, text (<= 120), type}` or null, optional | `senderId` is one of the match's users |
| `reactions` | map uid -> emoji, optional | never written on create; see updates |
| `event` | string, `event` rows only | one of the event codes below; absent on every other type |
| `unsentAt` | timestamp, optional | never written on create; set when the sender unsends it |
| `createdAt` | timestamp | `== request.time` |

- **Create:** participants, while the match exists and neither has blocked the other. Unknown
  fields are rejected.
- **Read:** participants.
- **Update:** exactly one of:
  - **React:** a participant changes only `reactions.{self}` (set to one of 🔥 ❤️ 😂 😮 👍, or
    delete it), while neither has blocked the other.
  - **Open a view-once photo:** the recipient (not the sender) of a `viewOnce` message sets
    `viewedAt == request.time`, once. In the same batch they delete the image doc.
  - **Unsend:** see below.
- **Delete:** participants (unmatch cleanup).

#### Unsend (tombstone)

The sender may unsend their own message for **15 minutes** after `createdAt`, once. The message
becomes a tombstone rather than disappearing:

- After the write the doc holds exactly `senderId` and `createdAt` (both unchanged),
  `type == "text"`, `text == "Message unsent"` and `unsentAt == request.time`. Every other field
  (`replyTo`, `reactions`, `imageId`, `audioId`, `date`, ...) must be gone, so a tombstone can
  never double as an edit. Event rows can't be unsent; a tombstone can't be unsent again.
- In the same batch the app deletes the message's chat image or voice note (participants may
  delete those anyway) and, if it was the chat's latest, swaps the preview as described under
  `matches/{matchId}`.
- Why a tombstone and not a hard delete: the other person may already have seen the message (or
  its notification), so a silent disappearance would read as gaslighting; replies that quoted it
  keep making sense (the app shows their quote as "Message unsent" too); and a pinned shape is
  something the rules can check, whereas `delete` has to stay open to both participants for the
  client-side unmatch cleanup. App versions without unsend just see a text message saying
  "Message unsent".
- Unsending needs a connection: a queued write could reach the server after the window and be
  rejected after the app had already shown it as unsent.

#### Event rows

`type == "event"` is a centred system row, e.g. "Arjun took a screenshot". The rules accept only
a fixed list of codes, each with a fixed `text`, and nothing else attached (`replyTo`, `viewOnce`,
image, game, voice and date fields must be absent or null):

| `event` | `text` (the fallback older app versions show as a plain message) |
|---|---|
| `screenshot` | `Took a screenshot of the chat` |

Event rows never become `lastMessage` (its `type` must be one of the five message types) and
can't be quoted, so they never notify, reorder the chat list or mark it unread. Older app
versions map the unknown type to text (`MessageType.of` falls back to `Text`) and show the
fallback text in an ordinary bubble.

- **Screenshot alerts** (Android 14+, `Activity.ScreenCaptureCallback` while a chat is open,
  permission `DETECT_SCREEN_CAPTURE`): at most one row per 10 seconds. A device-local switch
  ("Screenshot alerts", on by default) turns both directions off: the app stops posting rows for
  my screenshots and hides the rows the other person's app posts.

Sending never waits for the network: the client commits the batch (message plus the match's
`lastMessage`) into Firestore's offline cache and shows "Sending…" while the snapshot reports
`hasPendingWrites`. Photo and voice uploads need a connection.

### `matches/{matchId}/images/{imageId}`
`{ owner == auth.uid, data: bytes (<= 900 KB), createdAt }`. Participants create, read and delete
(the recipient of a view-once photo deletes it after viewing).

### `matches/{matchId}/audio/{audioId}`
`{ owner == auth.uid, data: bytes (AAC in MPEG-4, <= 900 KB), createdAt == request.time,
durationMs: int (1 to 61000) }`. Participants create, read and delete. No updates. A voice note
is at most 60 s of mono 24 kHz AAC at about 32 kbps, roughly 240 KB.

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

### `games/{gameId}` (group Truth or Dare)
The host and up to three of the host's matches take turns. A top-level collection (not under a
match) because a game spans several matches; membership lives in the doc, so the rules read one
document to decide everything.

| field | type | notes |
|---|---|---|
| `host` | string | `== auth.uid` at create. Never changes |
| `members` | string[] | who has joined, in turn order. Starts as `[host]`; an invitee who accepts is appended |
| `invited` | string[] | who hasn't answered yet. 1 to 3 at create; only ever shrinks |
| `profiles` | map uid -> `{name, photoUrl, blurhash}` | exactly the members and invitees. `name` is a first name (1..40). `photoUrl` (only `ember-fs://images/{id}`, never an outside URL that could log players' IPs) and `blurhash` (<= 100) are null until that person joins and writes their own first photo |
| `status` | string | `active`, or `ended` (by the host) |
| `turn` | string | the current player, always a member. Starts as the host |
| `turnNo` | int | starts at 0, +1 per turn |
| `card` | `{mode: "truth"\|"dare", prompt (1..300)}` or null | the current player's card for this turn |
| `createdAt`, `updatedAt` | timestamp | `== request.time` when written. `updatedAt` moves on every join, card, answer and chat line |

`members.size() + invited.size()` is at most 4 at create and can only shrink, so there are never
more than four people. Nobody can be added after create.

**Live** means `status == "active"` and `updatedAt + 24 h > request.time`. A game that isn't live
accepts no joins, cards, answers, chat or reactions.

- **Read** (get and list): members and invitees, while a verified adult. Queries must be
  `array-contains` on `members` or on `invited`.
- **Create:** the host, with `members == [host]`, `turn == host`, `turnNo == 0`, `card == null`,
  `status == "active"`, 1 to 3 distinct invitees, and a `profiles` entry for each (the invitees'
  with name only). For every invitee: the match `matches/{sorted uids}` exists, the match doc's
  `isDemo` isn't `true`, and neither has blocked the other.
- **Update:** exactly one of
  - **Accept:** an invitee moves themselves from `invited` to the end of `members`, writes their
    own `profiles.{self}` (with their photo) and `updatedAt`. Only while live, while still matched
    with the host, and if neither they nor any current member has blocked the other.
  - **Decline / leave / take out:** one person disappears from `invited` or `members`, along with
    their `profiles` entry. If it was their turn, `turn` becomes the next member, `turnNo` + 1 and
    `card` null; otherwise those don't change. Anyone can do it for themselves (no account doc
    needed, so account deletion always can); the host can do it to anyone else. The host can't
    leave, only end.
  - **Draw:** the current player sets `card` (only when it's null) and `updatedAt`.
  - **Next turn:** `turn` becomes the member after the current one, `turnNo` + 1, `card` null,
    `updatedAt` now. By the current player in the same batch as their answer
    (`moves/t{old turnNo}` must exist after the write), or by the host at any time (skip).
  - **Touch:** a member moves only `updatedAt` (sent with each chat line).
  - **End:** the host sets `status: "ended"` and `updatedAt`.
- **Delete:** the host at any time; any member once the game isn't live.

### `games/{gameId}/moves/{moveId}`
| field | type | notes |
|---|---|---|
| `senderId` | string | `== auth.uid` |
| `type` | string | `answer` or `chat` |
| `text` | string | at most 500. Chat: at least 1. Answer: at least 1, or `""` when `pass` |
| `mode`, `prompt` | string | answers only: copied from the game's `card` |
| `pass` | bool | answers only |
| `turnNo` | int | answers only: the game's `turnNo` |
| `reactions` | map uid -> emoji, optional | never written on create |
| `createdAt` | timestamp | `== request.time` |

- **Read:** members only. Invitees see nothing here until they accept.
- **Create:** members of a live game, when neither they nor any other member has blocked the
  other. An **answer** has id `t{turnNo}` (so one per turn), comes from the current player, needs a
  drawn card, and must be written with the next-turn update. A **chat** line must be written
  with a touch of the game's `updatedAt`.
- **Update:** reactions only, as in 1:1 chat (own key, one of the five), while live and with no
  blocks between members.
- **Delete:** the sender (always), the host (always), any member once the game isn't live.

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

### `users/{uid}/limits/spotlight_{yyyy-MM-dd}`
`{ spotlights: int }`: Spotlights `uid` has started on that UTC day, the same pattern as the report
counter (whose rules never match this id, since it isn't a bare date).
- The id must be `spotlight_` + the UTC date of `request.time`.
- Created with `spotlights == 1`, then only ever +1 up to the daily maximum (**1**, so in practice
  never updated), and only in the same batch as a profile write that sets `spotlightUntil` to a new
  timestamp in the future. The profile rule demands the counter step in turn.
- Readable by the owner. Never deleted.

Likes are intentionally not rate-limited.

Reasons: `fake_profile`, `inappropriate_photos`, `harassment`, `underage`, `spam_scam`,
`offensive_messages`, `safety_concern`, `other`.

## Client-side flows

- **Discovery:** query `users` with `onboarded == true`, ordered by `lastActiveAt` descending,
  limit 300 (composite index). The client then drops itself, swiped, blocked and paused people,
  people in its own `hiddenBy` (who blocked it), and hidden demos, and filters by age, distance,
  mutual gender interest, lookingFor, After Dark and (optionally) "free tonight only" and "online
  now only" (the latter ignored while the user hides their own activity). People whose
  `spotlightUntil` is still ahead go first, soonest-ending first; everyone else keeps their order.
  (`paused` is filtered on the device rather than in the query so the existing index still
  serves, and so profiles written before the field existed still show up.)
  - **Travel mode** ("Browse from" a city) is device-local: distances and nearby-first ordering
    use that city's coordinates instead of the profile's. Nothing is written to Firestore.
  - **Today's picks** are chosen on the device from the same candidates: five people scored by
    shared interests (x2), shared vibes (x2), the same `lookingFor` (x3) and a distance bonus, seeded
    by the date and uid. A new set appears at 6 pm local time (before that, yesterday's set shows),
    and the chosen uids are remembered on the device so the set stays stable all day.
- **Distance label:** cards and profiles show "N km away" only when the profile's
  `hideDistance` is not `true`; otherwise just the city.
- **Download my data:** the app reads everything the signed-in user can read about themselves
  (login, account doc, profile, match-only details, swipes, blocks, report counters, album
  photo ids and access, matches and the messages they sent) and saves it as a JSON file through
  the system file picker. Image bytes are left out; nothing is sent anywhere.
- **Online now:** while the app is in the foreground and fully signed in, a heartbeat writes
  `lastActiveAt` (server time) about every 5 minutes, skipping a write within 2 minutes of the last
  one. Anyone seen in the last 11 minutes is "online": a green dot on deck cards, profiles and match
  avatars, plus "Active now" / "Active 2h ago" (up to 7 days) on profiles. The matches list reads
  its people's `lastActiveAt` with one `whereIn` query (at most 30) every 5 minutes while open.
  - **"Show when I'm active" off** (`hideActivity: true`): switching it on rewrites `lastActiveAt`
    to today's UTC midnight in the same write, and from then on the heartbeat writes only that
    midnight, at most once a UTC day (the rules refuse anything finer), so the profile still sorts
    into the deck by day. Like WhatsApp's "last seen", it's reciprocal: someone who hides theirs sees
    nobody's (no dots, labels or "Online now" chip). That's enforced on the device only.
- **Spotlight:** read today's counter (`users/{me}/limits/spotlight_{UTC date}`, from the server);
  if it's there, today's boost is used. Otherwise, in one batch, create it at 1 and set
  `spotlightUntil` to now + 30 min (minus 30 s, so a phone clock running a little fast is still
  under the server's cap). Boosts reset at 00:00 UTC.
- **Like:**
  1. Write the swipe and `users/{target}/likedBy/{me}` (both with the `note`, for a prompt or photo like).
  2. Try to create the match. The rules reject it until the like is mutual (or the target is a
     demo that likes back), so a permission error here just means "no match yet".
  3. When the other person likes back, their client creates the match.
  - **Offline:** the batch is committed without waiting for the server (Firestore keeps pending
    writes and sends them later), and the match attempt is retried on the next deck load once the
    device is back online.
  - **Notes become the first message.** When a noted like makes a match, the liker's own app posts
    the comment (or "❤️") as an ordinary text message from the liker, with `replyTo` quoting the
    prompt (`{id: "profile-prompt-{ref}", senderId: <the liked person>, text: "question: answer"}`)
    or photo (`type: "image"`, text "Photo"). Right away when the liker completed the match (a demo
    profile that likes back included); otherwise when the liker's app sees a new match with no
    messages yet whose other person it liked with a note (read back from its own swipe). Only into
    an empty chat, once per match per app run.
- **Likes you:** the owner lists `users/{me}/likedBy` and fetches each profile by id. Faces stay
  blurred until you match; super likes wear a blue star. A note shows on the tile ("Liked your
  prompt" and the comment) and, with the prompt or photo it points at, in the tile's sheet.
- **Unmatch:** delete the messages, chat images, voice notes, typing docs and match doc, plus
  album access both ways. The app hides the conversation at once and waits 10 s (an Undo
  window) before deleting anything; if the app is killed in that window, nothing is deleted.
  Group games built on the match go too: I leave (or decline) games they host, and take them
  out of games I host.
- **Block:** write the block doc and `users/{them}/hiddenBy/{me}` in one batch, then leave every
  group game we share (or, as host, take them out), then unmatch.
- **Report:** read today's counter (`users/{me}/limits/{UTC date}`, from the server), then in one
  batch set it to `n + 1` and create `reports/{me}_{date}_{n + 1}`. At 20 the app says so instead
  of writing (blocking still works). A lost race (two reports at once) retries once.
- **Delete account:** client-side deletes of everything the user owns or created (see
  `FirebaseSafetyRepository`), including `voices/{uid}` and the `hiddenBy` entries it wrote (the
  targets come from its own `blocks`), then `FirebaseUser.delete()`. Reports and the daily counters
  (reports and Spotlight) are kept. The account doc goes before the login, which would normally mean
  "show the age gate"; while a deletion runs the app keeps the current screen instead, then lands on
  the welcome screen with a short "Account deleted". The AI settings doc (`users/{uid}/ai/config`)
  is deleted first, while still signed in.
  Group games go before the rest: games the user hosts are ended and deleted with all their moves; in
  other games the user's own moves are deleted and they leave (their name and photo leave with
  them); pending invites are declined.
- **Group Truth or Dare:**
  - **Invite:** the picker lists my matches minus demo profiles and anyone in my `blocks` or
    `hiddenBy`. The host writes the game; nothing else happens until an invitee accepts.
  - **Invites** show in Matches as "Arjun invited you to Truth or Dare with 2 others" with the
    others' first names. Invites that include someone I blocked, or who blocked me, are hidden and
    declined automatically.
  - **Play:** the current player draws a card (the same non-explicit deck as the 1:1 game,
    avoiding prompts this game already had), then answers or passes. The answer and the next turn
    are one transaction. Anyone can leave; the host can skip a quiet player, take someone out, or
    end the game.
  - **Expiry and cleanup:** games not live (ended, or 24 h without a write) are hidden. Opening
    Matches or the game deletes them: any member deletes the moves, then the game doc. Invitees
    of a dead game decline.

## Chat privacy switches (device only)

The chat's ⋯ menu holds three switches. They live in the `ember_settings` DataStore on the phone
and change no rules.

- **Read receipts, per chat** (`chat_receipts_off`, a set of match ids). While off, the app stops
  writing `lastRead.{me}` for that match, both when reading and in the send batch, so the other
  person's "Read" never moves. My own unread badges keep working from a private marker,
  `chat_private_read_{matchId}` (epoch millis: the later of now and the newest message's
  `sentAt`), which the app merges into `myLastRead` (the later of the two) for the match list,
  the chat and the notifier. Turning receipts back on writes `lastRead.{me}` at once.
  - Why not a field in the match doc: rules decide who reads a *document*, not a field, so
    anything stored there (a flag, or a private read marker) is visible to the other participant
    to a modified client. Keeping the marker off Firestore is the only way it stays private on
    the free plan. The trade-off: the switch and the marker don't follow you to another phone or
    a reinstall (there, the chat simply has receipts on, and badges fall back to the last
    `lastRead` the server has).
- **Blur every photo** (`chat_blur_all_photos`, off by default). A received photo that arrived
  before I'd sent anything in the chat starts blurred (BlurHash only, no pixels) under a "May be
  sensitive · Tap to view" label until tapped; with this on, every received photo does.
- **Screenshot alerts** (`chat_screenshot_alerts`, on by default): see "Event rows".

## AI chat

Demo profiles can reply through the user's own AI key. The persona's side never touches
Firestore, so a client can still only post messages as itself.

- **Settings** live in the `ember_ai` DataStore on the phone: provider, model per provider, base
  URL (for OpenAI-compatible providers), pace, first move, voice notes, immersive mode, monthly
  reply cap, this month's usage, and each provider's API key sealed with AES-256-GCM under a
  non-exportable Android Keystore key (`ember_ai_keys`). Backups are off.
- **In the account** (`users/{uid}/ai/config`, on by default, switchable): the same settings with
  the keys, so they follow the login. Only the owner can read or write it; the rules whitelist
  every field and cap sizes (keys 400 chars, models 120, `baseUrl` must be `https://`). On sign-in
  the newer copy (`changedAt`) wins; on sign-out the phone forgets the keys; switching sync off
  or deleting the account deletes the document.
- **Persona state** lives in `files/ai_chats/{matchId}.json` (memory only in Preview mode): the
  persona's messages (text, quoted reply, voice-note file and length, check-in flag), my reactions
  to them, its reactions to mine, its mood, warmth (0-100, moving at most 12 per reply) and up to
  15 remembered facts about me, plus read/seen markers. Voice notes are WAV files in
  `files/ai_voice/`, spoken by the phone's text-to-speech. `AiMatchRepository` merges all of it into
  the chat, previews, unread state, receipts, reactions, voice playback and the typing indicator.
- **Check-ins**: the 15-minute message sync may let one quiet demo chat text first: after 3+
  messages from me, 8-20 hours of quiet (per persona), 9:00-22:59 local, at most once a day, never
  twice in a row unanswered.
- **Sent to the provider**, per demo reply: the demo's profile, its mood, warmth and memory, the
  last 40 messages of that chat with up to two of my recent photos (never view-once ones), and my
  first name, age, city, bio, prompts and interests. **Help me reply** sends a chat (including a
  real person's) only when tapped, and **writing help** sends my profile draft only when asked.
- Unmatching deletes that chat's file and voice notes; deleting the account deletes all of them,
  the account copy and the keys.

## Remote Config

| key | type | default |
|---|---|---|
| `hide_demo_profiles` | bool | `false`; flip it by hand once enough real people have joined |
