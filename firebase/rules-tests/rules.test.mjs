// Security rules tests for firestore.rules (Spark plan: the rules are the server).
// Run from this folder with `npm test`; it boots the Firestore emulator for a demo project.
import { readFileSync } from "node:fs";
import { after, before, beforeEach, describe, test } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import {
  Bytes, Timestamp, collection, deleteDoc, deleteField, doc, getDoc, getDocs, limit, orderBy, query, serverTimestamp,
  setDoc, updateDoc, where, writeBatch,
} from "firebase/firestore";

const ALICE = "alice";
const BOB = "bob";
const EVE = "eve";
const DEMO = "demo-001";
const MATCH = "alice_bob";

let env;

// Today's UTC date, so birth dates can be built relative to the emulator's request.time.
const now = new Date();
const Y = now.getUTCFullYear();
const M = now.getUTCMonth() + 1;
const D = now.getUTCDate();

function ageFor(y, m, d) {
  return M < m || (M === m && D < d) ? Y - y - 1 : Y - y;
}

const adultBirth = { birthYear: Y - 27, birthMonth: 3, birthDay: 14 };
const adultAge = ageFor(adultBirth.birthYear, adultBirth.birthMonth, adultBirth.birthDay);

const db = (uid) => env.authenticatedContext(uid).firestore();
const jpeg = (size) => Bytes.fromUint8Array(new Uint8Array(size).fill(7));

async function seed(fn) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore()));
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-ember",
    // Host and port come from FIRESTORE_EMULATOR_HOST, which `firebase emulators:exec` sets.
    firestore: { rules: readFileSync("../firestore.rules", "utf8") },
  });
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
  await seed(async (fs) => {
    for (const uid of [ALICE, BOB, EVE]) {
      await setDoc(doc(fs, `users/${uid}/private/account`), { ...adultBirth, termsVersion: "2026-10", termsAcceptedAt: new Date() });
      await setDoc(doc(fs, `users/${uid}`), { uid, name: uid, age: adultAge, onboarded: true, lastActiveAt: new Date() });
    }
    await setDoc(doc(fs, `users/${DEMO}`), { uid: DEMO, name: "Demo", age: 30, onboarded: true, isDemo: true, lastActiveAt: new Date() });
    await setDoc(doc(fs, `demoMeta/${DEMO}`), { likesBack: true });
    await setDoc(doc(fs, `matches/${MATCH}`), { users: [ALICE, BOB], lastRead: {}, profiles: {}, lastMessage: null });
    await setDoc(doc(fs, "users/bob/matchOnly/details"), { boundaries: ["slow_burn"], turnOns: [] });
    await setDoc(doc(fs, "albums/bob/photos/p1"), { data: jpeg(10), createdAt: new Date() });
  });
});

describe("age gate", () => {
  const terms = () => ({ termsVersion: "2026-10", termsAcceptedAt: serverTimestamp() });

  test("an adult's account and first profile doc are written in one batch", async () => {
    const fs = db("newbie");
    const batch = writeBatch(fs);
    batch.set(doc(fs, "users/newbie/private/account"), { ...adultBirth, ...terms() });
    batch.set(doc(fs, "users/newbie"), {
      uid: "newbie", age: adultAge, onboarded: false, createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
    });
    await assertSucceeds(batch.commit());
  });

  test("exactly 18 today passes; 18 tomorrow does not", async () => {
    await assertSucceeds(setDoc(doc(db("a18"), "users/a18/private/account"), { birthYear: Y - 18, birthMonth: M, birthDay: D, ...terms() }));
    const tomorrow = new Date(Date.UTC(Y - 18, M - 1, D + 1));
    await assertFails(setDoc(doc(db("b17"), "users/b17/private/account"), {
      birthYear: tomorrow.getUTCFullYear(), birthMonth: tomorrow.getUTCMonth() + 1, birthDay: tomorrow.getUTCDate(), ...terms(),
    }));
  });

  test("under-18 and over-100 dates are rejected", async () => {
    await assertFails(setDoc(doc(db("kid"), "users/kid/private/account"), { birthYear: Y - 15, birthMonth: 1, birthDay: 1, ...terms() }));
    await assertFails(setDoc(doc(db("old"), "users/old/private/account"), { birthYear: Y - 102, birthMonth: 1, birthDay: 1, ...terms() }));
  });

  test("only real calendar dates", async () => {
    await assertSucceeds(setDoc(doc(db("leap"), "users/leap/private/account"), { birthYear: 2000, birthMonth: 2, birthDay: 29, ...terms() }));
    await assertFails(setDoc(doc(db("bad1"), "users/bad1/private/account"), { birthYear: 2001, birthMonth: 2, birthDay: 29, ...terms() }));
    await assertFails(setDoc(doc(db("bad2"), "users/bad2/private/account"), { birthYear: 1990, birthMonth: 4, birthDay: 31, ...terms() }));
    await assertFails(setDoc(doc(db("bad3"), "users/bad3/private/account"), { birthYear: 1990, birthMonth: 13, birthDay: 1, ...terms() }));
  });

  test("the account doc is create-once, owner-only", async () => {
    await assertFails(updateDoc(doc(db(ALICE), "users/alice/private/account"), { birthYear: 1950 }));
    await assertFails(setDoc(doc(db(EVE), "users/mallory/private/account"), { ...adultBirth, ...terms() }));
    await assertFails(getDoc(doc(db(EVE), "users/alice/private/account")));
    await assertSucceeds(getDoc(doc(db(ALICE), "users/alice/private/account")));
    await assertSucceeds(deleteDoc(doc(db(ALICE), "users/alice/private/account")));
  });

  test("no profile without an account doc", async () => {
    await assertFails(setDoc(doc(db("ghost"), "users/ghost"), { uid: "ghost", age: 25 }));
  });
});

describe("profiles", () => {
  test("adults read profiles; people without an account doc cannot", async () => {
    await assertSucceeds(getDoc(doc(db(EVE), "users/alice")));
    await assertFails(getDoc(doc(db("stranger"), "users/alice")));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "users/alice")));
  });

  test("discovery list is capped at 300", async () => {
    const base = collection(db(EVE), "users");
    await assertSucceeds(getDocs(query(base, where("onboarded", "==", true), orderBy("lastActiveAt", "desc"), limit(300))));
    await assertFails(getDocs(query(base, where("onboarded", "==", true), limit(301))));
    await assertFails(getDocs(query(base, where("onboarded", "==", true))));
  });

  test("age must match the birth date (one year of slack)", async () => {
    const fs = db(ALICE);
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { age: adultAge, bio: "hi" }));
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { age: adultAge + 1 }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { age: adultAge + 2 }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { age: 18 }));
  });

  test("clients can't claim to be demo profiles or write junk", async () => {
    const fs = db(ALICE);
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { isDemo: false }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { isDemo: true }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { lat: 40.712776 }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { geohash: "dr5regw" }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { password: "x" }));
    await assertFails(updateDoc(doc(db(EVE), "users/alice"), { bio: "pwned" }));
  });

  test("privacy switches are optional booleans the owner sets", async () => {
    const fs = db(ALICE);
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { hideDistance: true, paused: true }));
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { hideDistance: false }));
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { paused: false }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { hideDistance: "yes" }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { paused: 1 }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { paused: null }));
    await assertFails(updateDoc(doc(db(EVE), "users/alice"), { paused: true }));
    // Paused profiles are left out of the deck by the app, but stay readable (matches still see them).
    await assertSucceeds(getDoc(doc(db(BOB), "users/alice")));
  });

  test("a new profile may start with the privacy switches", async () => {
    const fs = db("newbie");
    const batch = writeBatch(fs);
    batch.set(doc(fs, "users/newbie/private/account"), { ...adultBirth, termsVersion: "2026-10", termsAcceptedAt: serverTimestamp() });
    batch.set(doc(fs, "users/newbie"), { uid: "newbie", age: adultAge, onboarded: false, hideDistance: true, paused: false });
    await assertSucceeds(batch.commit());
  });

  test("free tonight lasts at most six hours", async () => {
    const fs = db(ALICE);
    const inHours = (h) => Timestamp.fromMillis(Date.now() + h * 3600_000);
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { freeTonightUntil: inHours(6) }));
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { freeTonightUntil: inHours(2) }));
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { freeTonightUntil: null }));
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { freeTonightUntil: deleteField() }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { freeTonightUntil: inHours(7) }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { freeTonightUntil: "tonight" }));
    await assertFails(updateDoc(doc(db(EVE), "users/alice"), { freeTonightUntil: inHours(1) }));
  });

  test("voice intro duration is an int up to 30 s", async () => {
    const fs = db(ALICE);
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { voiceDurationMs: 12_000 }));
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { voiceDurationMs: null }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { voiceDurationMs: 45_000 }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { voiceDurationMs: 0 }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { voiceDurationMs: "12s" }));
  });

  test("blocked users cannot get the blocker", async () => {
    await seed((fs) => setDoc(doc(fs, "users/alice/blocks/eve"), { createdAt: new Date() }));
    await assertFails(getDoc(doc(db(EVE), "users/alice")));
  });

  test("boundaries are visible to matches only", async () => {
    await assertSucceeds(getDoc(doc(db(ALICE), "users/bob/matchOnly/details")));
    await assertFails(getDoc(doc(db(EVE), "users/bob/matchOnly/details")));
  });

  test("owners can delete their own profile", async () => {
    await assertFails(deleteDoc(doc(db(EVE), "users/alice")));
    await assertSucceeds(deleteDoc(doc(db(ALICE), "users/alice")));
  });
});

describe("images", () => {
  test("adults upload their own JPEGs up to 900 KB", async () => {
    const fs = db(ALICE);
    await assertSucceeds(setDoc(doc(fs, "images/i1"), { owner: ALICE, data: jpeg(1024), createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(fs, "images/i2"), { owner: ALICE, data: jpeg(901 * 1024), createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(fs, "images/i3"), { owner: BOB, data: jpeg(10), createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(db("stranger"), "images/i4"), { owner: "stranger", data: jpeg(10), createdAt: serverTimestamp() }));
  });

  test("adults read, only the owner deletes", async () => {
    await seed((fs) => setDoc(doc(fs, "images/i1"), { owner: ALICE, data: jpeg(10), createdAt: new Date() }));
    await assertSucceeds(getDoc(doc(db(BOB), "images/i1")));
    await assertFails(getDoc(doc(db("stranger"), "images/i1")));
    await assertFails(deleteDoc(doc(db(BOB), "images/i1")));
    await assertSucceeds(deleteDoc(doc(db(ALICE), "images/i1")));
  });
});

describe("swipes and likes", () => {
  test("swipes are owner-only with server time", async () => {
    const fs = db(ALICE);
    await assertSucceeds(setDoc(doc(fs, "users/alice/swipes/eve"), { direction: "like", createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(fs, "users/alice/swipes/bob"), { direction: "like", createdAt: new Date(0) }));
    await assertFails(setDoc(doc(fs, "users/alice/swipes/alice"), { direction: "like", createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(db(EVE), "users/alice/swipes/x"), { direction: "nope", createdAt: serverTimestamp() }));
  });

  test("rewind: recent swipes without a match only", async () => {
    await seed(async (fs) => {
      await setDoc(doc(fs, "users/alice/swipes/eve"), { direction: "like", createdAt: new Date() });
      await setDoc(doc(fs, "users/alice/swipes/bob"), { direction: "like", createdAt: new Date() });
      await setDoc(doc(fs, "users/alice/swipes/old"), { direction: "nope", createdAt: new Date(Date.now() - 3600_000) });
    });
    const fs = db(ALICE);
    await assertSucceeds(deleteDoc(doc(fs, "users/alice/swipes/eve")));
    await assertFails(deleteDoc(doc(fs, "users/alice/swipes/bob"))); // matched
    await assertFails(deleteDoc(doc(fs, "users/alice/swipes/old"))); // too old
    // During account deletion (account doc gone), old swipes can be cleared.
    await seed((s) => deleteDoc(doc(s, "users/alice/private/account")));
    await assertSucceeds(deleteDoc(doc(fs, "users/alice/swipes/old")));
  });

  test("likedBy is written by the liker and readable only by the liked person", async () => {
    await assertSucceeds(setDoc(doc(db(EVE), "users/alice/likedBy/eve"), { createdAt: serverTimestamp(), super: false }));
    await assertFails(setDoc(doc(db(EVE), "users/alice/likedBy/bob"), { createdAt: serverTimestamp(), super: false }));
    // "Likes you": the owner can get and list.
    await assertSucceeds(getDoc(doc(db(ALICE), "users/alice/likedBy/eve")));
    await assertSucceeds(getDocs(collection(db(ALICE), "users/alice/likedBy")));
    // Nobody else, including the liker, and not before the age gate.
    await assertFails(getDoc(doc(db(EVE), "users/alice/likedBy/eve")));
    await assertFails(getDocs(collection(db(BOB), "users/alice/likedBy")));
    await assertFails(getDocs(collection(db("stranger"), "users/stranger/likedBy")));
    await assertFails(updateDoc(doc(db(ALICE), "users/alice/likedBy/eve"), { super: true }));
    await assertFails(deleteDoc(doc(db(ALICE), "users/alice/likedBy/eve")));
    await assertSucceeds(deleteDoc(doc(db(EVE), "users/alice/likedBy/eve")));
  });
});

describe("voice intros", () => {
  const voice = (owner, extra = {}) => ({ owner, data: jpeg(64 * 1024), durationMs: 14_000, createdAt: serverTimestamp(), ...extra });

  test("owners write one clip of up to 900 KB and 30 s", async () => {
    const fs = db(ALICE);
    await assertSucceeds(setDoc(doc(fs, "voices/alice"), voice(ALICE)));
    await assertSucceeds(setDoc(doc(fs, "voices/alice"), voice(ALICE, { durationMs: 30_000 }))); // re-record
    await assertFails(setDoc(doc(fs, "voices/alice"), voice(ALICE, { data: jpeg(901 * 1024) })));
    await assertFails(setDoc(doc(fs, "voices/alice"), voice(ALICE, { durationMs: 60_000 })));
    await assertFails(setDoc(doc(fs, "voices/alice"), voice(ALICE, { createdAt: new Date(0) })));
    await assertFails(setDoc(doc(fs, "voices/alice"), voice(ALICE, { transcript: "hi" })));
    await assertFails(setDoc(doc(fs, "voices/bob"), voice(BOB)));
    await assertFails(setDoc(doc(fs, "voices/bob"), voice(ALICE)));
    await assertFails(setDoc(doc(db("stranger"), "voices/stranger"), voice("stranger")));
  });

  test("adults play them; blocked users, strangers and listing cannot", async () => {
    await seed((fs) => setDoc(doc(fs, "voices/alice"), { owner: ALICE, data: jpeg(10), durationMs: 5_000, createdAt: new Date() }));
    await assertSucceeds(getDoc(doc(db(BOB), "voices/alice")));
    await assertSucceeds(getDoc(doc(db(ALICE), "voices/alice")));
    await assertFails(getDoc(doc(db("stranger"), "voices/alice")));
    await assertFails(getDocs(query(collection(db(BOB), "voices"), limit(10))));
    await seed((fs) => setDoc(doc(fs, "users/alice/blocks/eve"), { createdAt: new Date() }));
    await assertFails(getDoc(doc(db(EVE), "voices/alice")));
  });

  test("only the owner deletes", async () => {
    await seed((fs) => setDoc(doc(fs, "voices/alice"), { owner: ALICE, data: jpeg(10), durationMs: 5_000, createdAt: new Date() }));
    await assertFails(deleteDoc(doc(db(BOB), "voices/alice")));
    await assertSucceeds(deleteDoc(doc(db(ALICE), "voices/alice")));
  });
});

describe("matching", () => {
  const matchDoc = (users, extra = {}) => ({
    users, profiles: {}, createdAt: serverTimestamp(), superLike: false, isDemo: false, lastMessage: null, lastRead: {}, ...extra,
  });

  test("a one-sided like cannot create a match", async () => {
    const fs = db(ALICE);
    await setDoc(doc(fs, "users/eve/likedBy/alice"), { createdAt: serverTimestamp(), super: false });
    await assertFails(setDoc(doc(fs, "matches/alice_eve"), matchDoc([ALICE, EVE])));
  });

  test("mutual likes create the match, even in the same batch as the second like", async () => {
    await seed((fs) => setDoc(doc(fs, "users/eve/likedBy/alice"), { createdAt: new Date(), super: false }));
    // Eve liked Alice already (alice/likedBy/eve is missing): Eve's like of Alice is written now.
    await seed((fs) => setDoc(doc(fs, "users/alice/likedBy/eve"), { createdAt: new Date(), super: false }));
    await assertSucceeds(setDoc(doc(db(ALICE), "matches/alice_eve"), matchDoc([ALICE, EVE])));

    await env.clearFirestore();
    await seed(async (fs) => {
      for (const uid of [ALICE, EVE]) await setDoc(doc(fs, `users/${uid}/private/account`), { ...adultBirth, termsVersion: "x", termsAcceptedAt: new Date() });
      await setDoc(doc(fs, "users/eve/likedBy/alice"), { createdAt: new Date(), super: false }); // Alice liked Eve earlier
    });
    const fs = db(EVE);
    const batch = writeBatch(fs);
    batch.set(doc(fs, "users/alice/likedBy/eve"), { createdAt: serverTimestamp(), super: true });
    batch.set(doc(fs, "matches/alice_eve"), matchDoc([ALICE, EVE], { superLike: true }));
    await assertSucceeds(batch.commit());
  });

  test("match ids and fields are strict", async () => {
    await seed(async (fs) => {
      await setDoc(doc(fs, "users/eve/likedBy/alice"), { createdAt: new Date(), super: false });
      await setDoc(doc(fs, "users/alice/likedBy/eve"), { createdAt: new Date(), super: false });
    });
    const fs = db(ALICE);
    await assertFails(setDoc(doc(fs, "matches/eve_alice"), matchDoc([EVE, ALICE])));
    await assertFails(setDoc(doc(fs, "matches/alice_eve"), matchDoc([ALICE, EVE], { lastMessage: { text: "x" } })));
    await assertFails(setDoc(doc(db(BOB), "matches/alice_eve"), matchDoc([ALICE, EVE])));
  });

  test("demo profiles that like back match on a single like", async () => {
    const fs = db(ALICE);
    await setDoc(doc(fs, `users/${DEMO}/likedBy/alice`), { createdAt: serverTimestamp(), super: false });
    await assertSucceeds(setDoc(doc(fs, `matches/alice_${DEMO}`), matchDoc([ALICE, DEMO], { isDemo: true })));
    await seed((s) => setDoc(doc(s, `demoMeta/${DEMO}`), { likesBack: false }));
    await seed((s) => deleteDoc(doc(s, `matches/alice_${DEMO}`)));
    await assertFails(setDoc(doc(fs, `matches/alice_${DEMO}`), matchDoc([ALICE, DEMO], { isDemo: true })));
  });

  test("blocks prevent matches", async () => {
    await seed(async (fs) => {
      await setDoc(doc(fs, "users/eve/likedBy/alice"), { createdAt: new Date(), super: false });
      await setDoc(doc(fs, "users/alice/likedBy/eve"), { createdAt: new Date(), super: false });
      await setDoc(doc(fs, "users/eve/blocks/alice"), { createdAt: new Date() });
    });
    await assertFails(setDoc(doc(db(ALICE), "matches/alice_eve"), matchDoc([ALICE, EVE])));
  });

  test("participants move only their own read marker and post the preview", async () => {
    const fs = db(ALICE);
    await assertSucceeds(updateDoc(doc(fs, `matches/${MATCH}`), { "lastRead.alice": serverTimestamp() }));
    await assertFails(updateDoc(doc(fs, `matches/${MATCH}`), { "lastRead.bob": serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(fs, `matches/${MATCH}`), {
      lastMessage: { text: "hey", senderId: ALICE, type: "text", sentAt: serverTimestamp() },
    }));
    await assertFails(updateDoc(doc(fs, `matches/${MATCH}`), {
      lastMessage: { text: "hey", senderId: BOB, type: "text", sentAt: serverTimestamp() },
    }));
    await assertFails(updateDoc(doc(fs, `matches/${MATCH}`), { users: [ALICE, EVE] }));
    await assertFails(getDoc(doc(db(EVE), `matches/${MATCH}`)));
  });

  test("either participant can unmatch", async () => {
    await assertFails(deleteDoc(doc(db(EVE), `matches/${MATCH}`)));
    await assertSucceeds(deleteDoc(doc(db(BOB), `matches/${MATCH}`)));
  });
});

describe("chat", () => {
  const text = (sender, t = "hi") => ({ senderId: sender, type: "text", text: t, imageId: null, imageBlurhash: null, game: null, createdAt: serverTimestamp() });

  test("participants send messages with the last-message preview in one batch", async () => {
    const fs = db(ALICE);
    const batch = writeBatch(fs);
    batch.set(doc(fs, `matches/${MATCH}/messages/m1`), text(ALICE));
    batch.update(doc(fs, `matches/${MATCH}`), { lastMessage: { text: "hi", senderId: ALICE, type: "text", sentAt: serverTimestamp() } });
    await assertSucceeds(batch.commit());
    await assertSucceeds(getDoc(doc(db(BOB), `matches/${MATCH}/messages/m1`)));
    await assertFails(getDoc(doc(db(EVE), `matches/${MATCH}/messages/m1`)));
  });

  test("no spoofing, no edits, no outsiders", async () => {
    await assertFails(setDoc(doc(db(ALICE), `matches/${MATCH}/messages/m2`), text(BOB)));
    await assertFails(setDoc(doc(db(EVE), `matches/${MATCH}/messages/m3`), text(EVE)));
    await seed((fs) => setDoc(doc(fs, `matches/${MATCH}/messages/m4`), { senderId: ALICE, type: "text", text: "a", createdAt: new Date() }));
    await assertFails(updateDoc(doc(db(ALICE), `matches/${MATCH}/messages/m4`), { text: "edited" }));
    await assertSucceeds(deleteDoc(doc(db(BOB), `matches/${MATCH}/messages/m4`)));
  });

  test("blocks stop messages", async () => {
    await seed((fs) => setDoc(doc(fs, "users/bob/blocks/alice"), { createdAt: new Date() }));
    await assertFails(setDoc(doc(db(ALICE), `matches/${MATCH}/messages/m5`), text(ALICE)));
  });

  test("image messages need the image doc", async () => {
    const fs = db(ALICE);
    const image = { senderId: ALICE, type: "image", text: "", imageId: "img1", imageBlurhash: "LEHV6nWB2yk8", game: null, createdAt: serverTimestamp() };
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/m6`), image));
    await assertSucceeds(setDoc(doc(fs, `matches/${MATCH}/images/img1`), { owner: ALICE, data: jpeg(2048), createdAt: serverTimestamp() }));
    await assertSucceeds(setDoc(doc(fs, `matches/${MATCH}/messages/m6`), image));
    await assertSucceeds(getDoc(doc(db(BOB), `matches/${MATCH}/images/img1`)));
    await assertFails(getDoc(doc(db(EVE), `matches/${MATCH}/images/img1`)));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/images/img2`), { owner: ALICE, data: jpeg(901 * 1024), createdAt: serverTimestamp() }));
  });

  test("game cards", async () => {
    await assertSucceeds(setDoc(doc(db(ALICE), `matches/${MATCH}/messages/g1`), {
      senderId: ALICE, type: "game", text: "Dare", imageId: null, imageBlurhash: null,
      game: { mode: "dare", prompt: "Send your best pickup line." }, createdAt: serverTimestamp(),
    }));
    await assertFails(setDoc(doc(db(ALICE), `matches/${MATCH}/messages/g2`), {
      senderId: ALICE, type: "game", text: "x", game: { mode: "kiss", prompt: "x" }, createdAt: serverTimestamp(),
    }));
  });

  test("typing indicators are per user", async () => {
    await assertSucceeds(setDoc(doc(db(ALICE), `matches/${MATCH}/typing/alice`), { typing: true, updatedAt: serverTimestamp() }));
    await assertFails(setDoc(doc(db(ALICE), `matches/${MATCH}/typing/bob`), { typing: true, updatedAt: serverTimestamp() }));
    await assertSucceeds(getDoc(doc(db(BOB), `matches/${MATCH}/typing/alice`)));
    await assertFails(getDoc(doc(db(EVE), `matches/${MATCH}/typing/alice`)));
  });

  test("reactions: only your own key, only from the fixed set", async () => {
    await seed((fs) => setDoc(doc(fs, `matches/${MATCH}/messages/r1`), { senderId: ALICE, type: "text", text: "a", createdAt: new Date() }));
    const ref = (uid) => doc(db(uid), `matches/${MATCH}/messages/r1`);
    await assertSucceeds(updateDoc(ref(BOB), { "reactions.bob": "🔥" }));
    await assertSucceeds(updateDoc(ref(ALICE), { "reactions.alice": "❤️" }));
    await assertSucceeds(updateDoc(ref(BOB), { "reactions.bob": "😂" }));
    await assertFails(updateDoc(ref(BOB), { "reactions.alice": "👍" }));
    await assertFails(updateDoc(ref(BOB), { reactions: { bob: "🔥" } })); // would wipe Alice's
    await assertFails(updateDoc(ref(BOB), { "reactions.bob": "🍆" }));
    await assertFails(updateDoc(ref(BOB), { "reactions.bob": "🔥", text: "edited" }));
    await assertFails(updateDoc(ref(EVE), { "reactions.eve": "🔥" }));
    await assertSucceeds(updateDoc(ref(BOB), { "reactions.bob": deleteField() }));
    // Reactions are never part of a new message.
    await assertFails(setDoc(doc(db(ALICE), `matches/${MATCH}/messages/r2`), { ...text(ALICE), reactions: { alice: "🔥" } }));
    // Blocking stops reactions too.
    await seed((fs) => setDoc(doc(fs, "users/alice/blocks/bob"), { createdAt: new Date() }));
    await assertFails(updateDoc(ref(BOB), { "reactions.bob": "🔥" }));
  });

  test("replies carry a small, well-formed quote", async () => {
    const quote = { id: "m0", senderId: BOB, text: "hey you", type: "text" };
    const reply = (q) => setDoc(doc(db(ALICE), `matches/${MATCH}/messages/${Math.random().toString(36).slice(2)}`), { ...text(ALICE), replyTo: q });
    await assertSucceeds(reply(quote));
    await assertSucceeds(reply(null));
    await assertSucceeds(reply({ ...quote, type: "voice", text: "" }));
    await assertFails(reply({ ...quote, senderId: EVE }));
    await assertFails(reply({ ...quote, text: "x".repeat(121) }));
    await assertFails(reply({ ...quote, type: "sticker" }));
    await assertFails(reply({ ...quote, extra: true }));
    await assertFails(reply({ ...quote, id: "" }));
    await assertFails(reply("m0"));
  });

  test("view-once photos: only the recipient opens them, once, and deletes the image", async () => {
    const fs = db(ALICE);
    const photo = { senderId: ALICE, type: "image", text: "", imageId: "v1", imageBlurhash: null, game: null, viewOnce: true, createdAt: serverTimestamp() };
    const batch = writeBatch(fs);
    batch.set(doc(fs, `matches/${MATCH}/images/v1`), { owner: ALICE, data: jpeg(2048), createdAt: serverTimestamp() });
    batch.set(doc(fs, `matches/${MATCH}/messages/vo1`), photo);
    await assertSucceeds(batch.commit());
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/vo2`), { ...text(ALICE), viewOnce: true }));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/vo3`), { ...photo, viewedAt: serverTimestamp() }));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/vo4`), { ...photo, viewOnce: "yes" }));

    // The sender can't mark their own photo opened, and nobody can backdate it.
    await assertFails(updateDoc(doc(fs, `matches/${MATCH}/messages/vo1`), { viewedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db(BOB), `matches/${MATCH}/messages/vo1`), { viewedAt: new Date(0) }));
    await assertFails(updateDoc(doc(db(EVE), `matches/${MATCH}/messages/vo1`), { viewedAt: serverTimestamp() }));

    const bob = db(BOB);
    const open = writeBatch(bob);
    open.delete(doc(bob, `matches/${MATCH}/images/v1`));
    open.update(doc(bob, `matches/${MATCH}/messages/vo1`), { viewedAt: serverTimestamp() });
    await assertSucceeds(open.commit());
    // Once.
    await assertFails(updateDoc(doc(bob, `matches/${MATCH}/messages/vo1`), { viewedAt: serverTimestamp() }));

    // Ordinary photos can't be "opened".
    await seed((s) => setDoc(doc(s, `matches/${MATCH}/messages/plain`), { senderId: ALICE, type: "image", text: "", imageId: "x", createdAt: new Date() }));
    await assertFails(updateDoc(doc(bob, `matches/${MATCH}/messages/plain`), { viewedAt: serverTimestamp() }));
  });

  test("voice notes: owner-only audio docs, at most 900 KB and 61 s", async () => {
    const fs = db(ALICE);
    const audio = (extra = {}) => ({ owner: ALICE, data: jpeg(4096), createdAt: serverTimestamp(), durationMs: 4200, ...extra });
    await assertSucceeds(setDoc(doc(fs, `matches/${MATCH}/audio/a1`), audio()));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/audio/a2`), audio({ owner: BOB })));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/audio/a3`), audio({ data: jpeg(901 * 1024) })));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/audio/a4`), audio({ durationMs: 0 })));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/audio/a5`), audio({ durationMs: 61001 })));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/audio/a6`), audio({ durationMs: 1.5 })));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/audio/a7`), audio({ codec: "aac" })));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/audio/a8`), audio({ createdAt: new Date(0) })));
    await assertFails(setDoc(doc(db(EVE), `matches/${MATCH}/audio/a9`), audio({ owner: EVE })));
    await assertSucceeds(getDoc(doc(db(BOB), `matches/${MATCH}/audio/a1`)));
    await assertFails(getDoc(doc(db(EVE), `matches/${MATCH}/audio/a1`)));
    await assertFails(updateDoc(doc(fs, `matches/${MATCH}/audio/a1`), { durationMs: 100 }));

    const voice = { senderId: ALICE, type: "voice", text: "", imageId: null, imageBlurhash: null, game: null, audioId: "a1", durationMs: 4200, createdAt: serverTimestamp() };
    await assertSucceeds(setDoc(doc(fs, `matches/${MATCH}/messages/vn1`), voice));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/vn2`), { ...voice, audioId: "missing" }));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/vn3`), { ...voice, durationMs: null }));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/vn4`), { ...text(ALICE), audioId: "a1" }));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/vn5`), { ...text(ALICE), durationMs: 4200 }));
    await assertSucceeds(updateDoc(doc(fs, `matches/${MATCH}`), {
      lastMessage: { text: "Voice note", senderId: ALICE, type: "voice", sentAt: serverTimestamp() },
    }));
    await assertSucceeds(deleteDoc(doc(db(BOB), `matches/${MATCH}/audio/a1`)));
  });

  test("date cards", async () => {
    const fs = db(ALICE);
    const date = { title: "Rooftop cocktails at golden hour", detail: "Somewhere with a view.", emoji: "🍸" };
    const card = (d, extra = {}) => ({ senderId: ALICE, type: "date", text: d?.title ?? "", imageId: null, imageBlurhash: null, game: null, date: d, createdAt: serverTimestamp(), ...extra });
    await assertSucceeds(setDoc(doc(fs, `matches/${MATCH}/messages/d1`), card(date)));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/d2`), card(null)));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/d3`), card({ ...date, title: "" })));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/d4`), card({ ...date, title: "x".repeat(81) })));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/d5`), card({ ...date, detail: "x".repeat(201) })));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/d6`), card({ ...date, where: "my place" })));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/d7`), { ...text(ALICE), date }));
    await assertFails(setDoc(doc(fs, `matches/${MATCH}/messages/d8`), card(date, { type: "sticker" })));
  });
});

describe("private albums", () => {
  const request = { ownerId: BOB, viewerId: ALICE, status: "pending", requestedAt: serverTimestamp(), updatedAt: serverTimestamp() };

  test("request, approve, view bytes, revoke", async () => {
    await assertFails(getDoc(doc(db(ALICE), "albums/bob/photos/p1")));
    await assertSucceeds(setDoc(doc(db(ALICE), "albums/bob/access/alice"), request));
    await assertFails(getDoc(doc(db(ALICE), "albums/bob/photos/p1")));
    await assertFails(updateDoc(doc(db(ALICE), "albums/bob/access/alice"), { status: "approved", updatedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(db(BOB), "albums/bob/access/alice"), { status: "approved", updatedAt: serverTimestamp() }));
    const snap = await assertSucceeds(getDoc(doc(db(ALICE), "albums/bob/photos/p1")));
    if (!(snap.data().data instanceof Bytes)) throw new Error("photo bytes missing");
    await assertSucceeds(deleteDoc(doc(db(BOB), "albums/bob/access/alice")));
    await assertFails(getDoc(doc(db(ALICE), "albums/bob/photos/p1")));
  });

  test("only matches can ask, and requests start pending", async () => {
    await assertFails(setDoc(doc(db(EVE), "albums/bob/access/eve"), { ...request, viewerId: EVE }));
    await assertFails(setDoc(doc(db(ALICE), "albums/bob/access/alice"), { ...request, status: "approved" }));
  });

  test("owners manage their own album", async () => {
    await assertSucceeds(setDoc(doc(db(BOB), "albums/bob/photos/p2"), { data: jpeg(100), createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(db(ALICE), "albums/bob/photos/p3"), { data: jpeg(100), createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(db(BOB), "albums/bob/photos/p4"), { data: jpeg(901 * 1024), createdAt: serverTimestamp() }));
    await assertSucceeds(deleteDoc(doc(db(BOB), "albums/bob/photos/p1")));
  });
});

describe("safety", () => {
  test("blocks are owner-managed", async () => {
    await assertSucceeds(setDoc(doc(db(ALICE), "users/alice/blocks/eve"), { createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(db(EVE), "users/alice/blocks/bob"), { createdAt: serverTimestamp() }));
    await assertFails(getDoc(doc(db(EVE), "users/alice/blocks/eve")));
    await assertSucceeds(deleteDoc(doc(db(ALICE), "users/alice/blocks/eve")));
  });

  test("hiddenBy is written by the blocker, with the block, and read by the blocked person", async () => {
    // Without a block it is rejected.
    await assertFails(setDoc(doc(db(ALICE), "users/eve/hiddenBy/alice"), { createdAt: serverTimestamp() }));
    // Block and hide in one batch.
    const fs = db(ALICE);
    const batch = writeBatch(fs);
    batch.set(doc(fs, "users/alice/blocks/eve"), { createdAt: serverTimestamp() });
    batch.set(doc(fs, "users/eve/hiddenBy/alice"), { createdAt: serverTimestamp() });
    await assertSucceeds(batch.commit());
    // Strict fields, server time, no impersonation, no self-entries.
    await assertFails(setDoc(doc(db(ALICE), "users/eve/hiddenBy/alice"), { createdAt: new Date(0) }));
    await assertFails(setDoc(doc(db(ALICE), "users/eve/hiddenBy/alice"), { createdAt: serverTimestamp(), note: "x" }));
    await assertFails(setDoc(doc(db(ALICE), "users/eve/hiddenBy/bob"), { createdAt: serverTimestamp() }));
    await seed((s) => setDoc(doc(s, "users/alice/blocks/alice"), { createdAt: new Date() }));
    await assertFails(setDoc(doc(db(ALICE), "users/alice/hiddenBy/alice"), { createdAt: serverTimestamp() }));
    // Only the parent owner reads (so their deck can drop the blocker).
    await assertSucceeds(getDocs(collection(db(EVE), "users/eve/hiddenBy")));
    await assertSucceeds(getDoc(doc(db(EVE), "users/eve/hiddenBy/alice")));
    await assertFails(getDoc(doc(db(ALICE), "users/eve/hiddenBy/alice")));
    await assertFails(getDocs(collection(db(BOB), "users/eve/hiddenBy")));
    // Only the creator deletes (account deletion), and nobody edits.
    await assertFails(updateDoc(doc(db(ALICE), "users/eve/hiddenBy/alice"), { createdAt: serverTimestamp() }));
    await assertFails(deleteDoc(doc(db(EVE), "users/eve/hiddenBy/alice")));
    await assertSucceeds(deleteDoc(doc(db(ALICE), "users/eve/hiddenBy/alice")));
  });

  test("account deletion can clear hiddenBy entries after the account doc is gone", async () => {
    await seed(async (fs) => {
      await setDoc(doc(fs, "users/alice/blocks/eve"), { createdAt: new Date() });
      await setDoc(doc(fs, "users/eve/hiddenBy/alice"), { createdAt: new Date() });
      await deleteDoc(doc(fs, "users/alice/private/account"));
    });
    await assertSucceeds(deleteDoc(doc(db(ALICE), "users/eve/hiddenBy/alice")));
  });

  test("demo metadata is invisible", async () => {
    await assertFails(getDoc(doc(db(ALICE), `demoMeta/${DEMO}`)));
    await assertFails(setDoc(doc(db(ALICE), "demoMeta/alice"), { likesBack: true }));
  });
});

describe("reports and the daily report limit", () => {
  // The rules compare against the UTC date of request.time. (Run near 00:00 UTC, a test could
  // straddle midnight; that is the only source of flakiness here.)
  const today = () => new Date().toISOString().slice(0, 10);
  const yesterday = () => new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);
  const report = (extra = {}) => ({
    reporterId: ALICE, targetId: EVE, reason: "harassment", details: "", matchId: null, createdAt: serverTimestamp(), status: "open", ...extra,
  });

  // The client's write: today's counter +1 and the report whose id names that step, in one batch.
  function sendReport(uid, n, { day = today(), counter = n, id = `${uid}_${day}_${n}`, extra = {} } = {}) {
    const fs = db(uid);
    const batch = writeBatch(fs);
    batch.set(doc(fs, `users/${uid}/limits/${day}`), { reports: counter });
    batch.set(doc(fs, `reports/${id}`), report({ reporterId: uid, ...extra }));
    return batch.commit();
  }

  test("reports are create-only and go with today's counter", async () => {
    await assertSucceeds(sendReport(ALICE, 1));
    await assertSucceeds(sendReport(ALICE, 2));
    await assertFails(getDoc(doc(db(ALICE), `reports/alice_${today()}_1`)));
    await assertFails(updateDoc(doc(db(ALICE), `reports/alice_${today()}_1`), { details: "edited" }));
    await assertFails(deleteDoc(doc(db(ALICE), `reports/alice_${today()}_1`)));
    await assertFails(sendReport(ALICE, 3, { extra: { reporterId: BOB } }));
    await assertFails(sendReport(ALICE, 3, { extra: { reason: "bored" } }));
    await assertFails(sendReport(ALICE, 3, { extra: { targetId: ALICE } }));
    await assertFails(sendReport("stranger", 1));
  });

  test("a report without exactly one counter step is rejected", async () => {
    // No counter at all.
    await assertFails(setDoc(doc(db(ALICE), `reports/alice_${today()}_1`), report()));
    // A free-form report id.
    await assertFails(sendReport(ALICE, 1, { id: "r1" }));
    // Skipping a step, or an id that doesn't match the step.
    await assertFails(sendReport(ALICE, 2));
    await assertFails(sendReport(ALICE, 1, { id: `alice_${today()}_7` }));
    // Yesterday's (or any other day's) counter.
    await assertFails(sendReport(ALICE, 1, { day: yesterday() }));
    // A second report can't ride on a step that's already been used.
    await assertSucceeds(sendReport(ALICE, 1));
    await assertFails(setDoc(doc(db(ALICE), `reports/alice_${today()}_2`), report()));
  });

  test("the counter only moves with a report", async () => {
    const fs = db(ALICE);
    await assertFails(setDoc(doc(fs, `users/alice/limits/${today()}`), { reports: 1 }));
    await assertSucceeds(sendReport(ALICE, 1));
    await assertFails(setDoc(doc(fs, `users/alice/limits/${today()}`), { reports: 2 }));
    await assertFails(setDoc(doc(fs, `users/alice/limits/${today()}`), { reports: 0 }));
    await assertFails(setDoc(doc(fs, `users/alice/limits/${today()}`), { reports: 2, extra: true }));
    // Nobody resets it, and only the owner reads it.
    await assertFails(deleteDoc(doc(fs, `users/alice/limits/${today()}`)));
    await assertSucceeds(getDoc(doc(fs, `users/alice/limits/${today()}`)));
    await assertFails(getDoc(doc(db(EVE), `users/alice/limits/${today()}`)));
    // Nobody writes someone else's counter.
    const eve = db(EVE);
    const batch = writeBatch(eve);
    batch.set(doc(eve, `users/alice/limits/${today()}`), { reports: 2 });
    batch.set(doc(eve, `reports/alice_${today()}_2`), report());
    await assertFails(batch.commit());
  });

  test("at most 20 reports per UTC day", async () => {
    await seed((fs) => setDoc(doc(fs, `users/alice/limits/${today()}`), { reports: 19 }));
    await assertSucceeds(sendReport(ALICE, 20));
    await assertFails(sendReport(ALICE, 21));
    // The limit is per reporter.
    await assertSucceeds(sendReport(BOB, 1));
  });
});

// ---------- group Truth or Dare (games/{gameId}) ----------
// Kept in one block so it merges cleanly with the rest of this file. Imports are hoisted, so
// these extra ones can live here.
import { arrayRemove, arrayUnion, increment } from "firebase/firestore";

describe("group truth or dare", () => {
  const CAT = "cat";
  const DAN = "dan";
  const GAME = "g1";
  const HOUR = 3600_000;

  const player = (name, photo) => (photo
    ? { name, photoUrl: `ember-fs://images/${name}`, blurhash: "LEHV6nWB2yk8" }
    : { name, photoUrl: null, blurhash: null });

  // What the host's client writes.
  const newGame = (invited, extra = {}) => ({
    host: ALICE,
    members: [ALICE],
    invited,
    profiles: Object.fromEntries([[ALICE, player("Alice", true)], ...invited.map((uid) => [uid, player(uid, false)])]),
    status: "active",
    turn: ALICE,
    turnNo: 0,
    card: null,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...extra,
  });

  // A game already under way, written with rules off.
  async function seedGame({ members = [ALICE, BOB], invited = [CAT], turn = ALICE, turnNo = 0, card = null, status = "active", idleHours = 0 } = {}) {
    const at = new Date(Date.now() - idleHours * HOUR);
    await seed((fs) => setDoc(doc(fs, `games/${GAME}`), {
      host: ALICE,
      members,
      invited,
      profiles: Object.fromEntries([...members.map((u) => [u, player(u, true)]), ...invited.map((u) => [u, player(u, false)])]),
      status,
      turn,
      turnNo,
      card,
      createdAt: at,
      updatedAt: at,
    }));
  }

  const game = (uid) => doc(db(uid), `games/${GAME}`);
  const move = (uid, id) => doc(db(uid), `games/${GAME}/moves/${id}`);
  const dare = { mode: "dare", prompt: "Plan our first date in exactly five emojis." };

  // The current player's answer, written with the turn advance, as the client does it.
  function answer(uid, { turnNo = 0, next, text = "🍝🎡🌙🚕💋", pass = false, card = dare, id = `t${turnNo}`, advance = true, extra = {} } = {}) {
    const fs = db(uid);
    const batch = writeBatch(fs);
    batch.set(doc(fs, `games/${GAME}/moves/${id}`), {
      senderId: uid, type: "answer", text, mode: card.mode, prompt: card.prompt, pass, turnNo, createdAt: serverTimestamp(), ...extra,
    });
    if (advance) batch.update(doc(fs, `games/${GAME}`), { turn: next, turnNo: increment(1), card: null, updatedAt: serverTimestamp() });
    return batch.commit();
  }

  // A chat line, written with a touch of the game's updatedAt.
  function chat(uid, text = "this is chaos", { touch = true, extra = {}, id = Math.random().toString(36).slice(2) } = {}) {
    const fs = db(uid);
    const batch = writeBatch(fs);
    batch.set(doc(fs, `games/${GAME}/moves/${id}`), { senderId: uid, type: "chat", text, createdAt: serverTimestamp(), ...extra });
    if (touch) batch.update(doc(fs, `games/${GAME}`), { updatedAt: serverTimestamp() });
    return batch.commit();
  }

  const accept = (uid, extra = {}) => updateDoc(game(uid), {
    invited: arrayRemove(uid), members: arrayUnion(uid), [`profiles.${uid}`]: player(uid, true), updatedAt: serverTimestamp(), ...extra,
  });

  const decline = (uid) => updateDoc(game(uid), { invited: arrayRemove(uid), [`profiles.${uid}`]: deleteField() });

  beforeEach(async () => {
    await seed(async (fs) => {
      for (const uid of [CAT, DAN]) {
        await setDoc(doc(fs, `users/${uid}/private/account`), { ...adultBirth, termsVersion: "2026-10", termsAcceptedAt: new Date() });
        await setDoc(doc(fs, `users/${uid}`), { uid, name: uid, age: adultAge, onboarded: true, lastActiveAt: new Date() });
      }
      for (const other of [CAT, DAN]) {
        await setDoc(doc(fs, `matches/alice_${other}`), { users: [ALICE, other], lastRead: {}, profiles: {}, lastMessage: null, isDemo: false });
      }
      await setDoc(doc(fs, `matches/alice_${DEMO}`), { users: [ALICE, DEMO], lastRead: {}, profiles: {}, lastMessage: null, isDemo: true });
    });
  });

  test("the host invites one to three of their matches", async () => {
    await assertSucceeds(setDoc(game(ALICE), newGame([BOB, CAT, DAN])));
    await assertSucceeds(setDoc(doc(db(ALICE), "games/g2"), newGame([BOB])));
  });

  test("never more than four players, never strangers", async () => {
    await seed((fs) => setDoc(doc(fs, "matches/alice_eve"), { users: [ALICE, EVE], lastRead: {}, profiles: {}, lastMessage: null }));
    await assertFails(setDoc(game(ALICE), newGame([BOB, CAT, DAN, EVE])));
    await assertFails(setDoc(game(ALICE), newGame([])));
    await seed((fs) => deleteDoc(doc(fs, "matches/alice_eve")));
    await assertFails(setDoc(game(ALICE), newGame([BOB, EVE]))); // not matched with Eve
    await assertFails(setDoc(game(EVE), newGame([BOB], { host: EVE, members: [EVE], turn: EVE }))); // Eve isn't matched with Bob
    await assertFails(setDoc(game(BOB), newGame([CAT]))); // the host must be the writer
    await assertFails(setDoc(game(ALICE), newGame([BOB, BOB])));
    await assertFails(setDoc(game(ALICE), newGame([ALICE])));
    await assertFails(setDoc(doc(db("stranger"), `games/${GAME}`), newGame([BOB], { host: "stranger", members: ["stranger"], turn: "stranger" })));
  });

  test("a new game's fields are strict", async () => {
    const bad = [
      { members: [ALICE, BOB] },
      { turn: BOB },
      { turnNo: 3 },
      { status: "ended" },
      { card: dare },
      { createdAt: new Date(0) },
      { updatedAt: new Date(0) },
      { score: 10 },
      // Invitees appear by first name only; their photo arrives when they accept.
      { profiles: { [ALICE]: player("Alice", true), [BOB]: player("Bob", true) } },
      { profiles: { [ALICE]: player("Alice", true) } },
      { profiles: { [ALICE]: player("Alice", true), [BOB]: player("Bob", false), [EVE]: player("Eve", false) } },
      { profiles: { [ALICE]: player("x".repeat(41), true), [BOB]: player("Bob", false) } },
      { profiles: { [ALICE]: { ...player("Alice", true), bio: "hi" }, [BOB]: player("Bob", false) } },
      { profiles: { [ALICE]: { ...player("Alice", true), photoUrl: "https://tracker.example/a.jpg" }, [BOB]: player("Bob", false) } },
    ];
    for (const extra of bad) await assertFails(setDoc(game(ALICE), newGame([BOB], extra)));
  });

  test("demo profiles and blocks either way can't be invited", async () => {
    await assertFails(setDoc(game(ALICE), newGame([DEMO])));
    await seed((fs) => setDoc(doc(fs, "users/alice/blocks/cat"), { createdAt: new Date() }));
    await assertFails(setDoc(game(ALICE), newGame([CAT])));
    await assertFails(setDoc(game(ALICE), newGame([BOB, CAT])));
    await seed((fs) => setDoc(doc(fs, "users/dan/blocks/alice"), { createdAt: new Date() }));
    await assertFails(setDoc(game(ALICE), newGame([DAN])));
    await assertSucceeds(setDoc(game(ALICE), newGame([BOB])));
  });

  test("only players read the game; only members read the moves", async () => {
    await seedGame();
    await seed((fs) => setDoc(doc(fs, `games/${GAME}/moves/t0`), { senderId: ALICE, type: "chat", text: "hi", createdAt: new Date() }));
    await assertSucceeds(getDoc(game(ALICE)));
    await assertSucceeds(getDoc(game(BOB)));
    await assertSucceeds(getDoc(game(CAT))); // the invite
    await assertFails(getDoc(game(EVE)));
    await assertFails(getDoc(game("stranger")));
    await assertSucceeds(getDocs(query(collection(db(BOB), "games"), where("members", "array-contains", BOB))));
    await assertSucceeds(getDocs(query(collection(db(CAT), "games"), where("invited", "array-contains", CAT))));
    await assertFails(getDocs(query(collection(db(EVE), "games"), where("members", "array-contains", BOB))));
    await assertFails(getDocs(collection(db(ALICE), "games")));
    await assertSucceeds(getDoc(move(BOB, "t0")));
    await assertSucceeds(getDocs(collection(db(BOB), `games/${GAME}/moves`)));
    await assertFails(getDoc(move(CAT, "t0"))); // invited, not yet in
    await assertFails(getDocs(collection(db(CAT), `games/${GAME}/moves`)));
    await assertFails(getDoc(move(EVE, "t0")));
  });

  test("invitees accept for themselves, at the end of the line", async () => {
    await seedGame({ invited: [CAT, DAN] });
    await assertFails(accept(EVE));
    await assertFails(updateDoc(game(BOB), {
      invited: arrayRemove(CAT), members: arrayUnion(CAT), [`profiles.${CAT}`]: player(CAT, true), updatedAt: serverTimestamp(),
    }));
    await assertFails(updateDoc(game(CAT), { invited: [DAN], members: [CAT, ALICE, BOB], [`profiles.${CAT}`]: player(CAT, true), updatedAt: serverTimestamp() }));
    await assertFails(accept(CAT, { [`profiles.${BOB}`]: player("Bobby", true) }));
    // Only Ember's own image docs as photos: no outside URL that would log the others' IPs.
    await assertFails(accept(CAT, { [`profiles.${CAT}`]: { name: "Cat", photoUrl: "https://tracker.example/p.jpg", blurhash: null } }));
    await assertFails(accept(CAT, { turn: CAT }));
    await assertFails(accept(CAT, { updatedAt: new Date(0) }));
    await assertSucceeds(accept(CAT));
    const after = (await getDoc(game(ALICE))).data();
    if (after.members.join() !== "alice,bob,cat" || after.invited.join() !== "dan") throw new Error(`unexpected ${JSON.stringify(after)}`);
    await assertSucceeds(getDocs(collection(db(CAT), `games/${GAME}/moves`)));
    await assertSucceeds(accept(DAN));
  });

  test("no joining next to someone with a block either way, after unmatching, or once it's over", async () => {
    await seedGame({ invited: [CAT, DAN] });
    await seed((fs) => setDoc(doc(fs, "users/bob/blocks/cat"), { createdAt: new Date() }));
    await assertFails(accept(CAT));
    await seed((fs) => setDoc(doc(fs, "users/dan/blocks/bob"), { createdAt: new Date() }));
    await assertFails(accept(DAN));

    await seed(async (fs) => {
      await deleteDoc(doc(fs, "users/bob/blocks/cat"));
      await deleteDoc(doc(fs, "matches/alice_cat"));
    });
    await assertFails(accept(CAT)); // no longer matched with the host

    await seed((fs) => setDoc(doc(fs, "matches/alice_cat"), { users: [ALICE, CAT], lastRead: {}, profiles: {}, lastMessage: null }));
    await seedGame({ idleHours: 25 });
    await assertFails(accept(CAT));
    await seedGame({ status: "ended" });
    await assertFails(accept(CAT));
    await seedGame();
    await assertSucceeds(accept(CAT));
  });

  test("declining, leaving and the host taking someone out", async () => {
    await seedGame({ members: [ALICE, BOB, DAN], invited: [CAT] });
    await assertFails(updateDoc(game(CAT), { invited: arrayRemove(CAT) })); // the card must go too
    await assertFails(updateDoc(game(CAT), { invited: arrayRemove(CAT), [`profiles.${CAT}`]: deleteField(), [`profiles.${BOB}`]: deleteField() }));
    await assertFails(updateDoc(game(BOB), { invited: arrayRemove(CAT), [`profiles.${CAT}`]: deleteField() })); // only Cat, or the host
    await assertSucceeds(decline(CAT));

    // Not my turn: the turn stays where it is.
    await assertSucceeds(updateDoc(game(DAN), { members: arrayRemove(DAN), [`profiles.${DAN}`]: deleteField() }));
    // The host can't walk out of their own game, only end it.
    await assertFails(updateDoc(game(ALICE), { members: arrayRemove(ALICE), [`profiles.${ALICE}`]: deleteField() }));

    // My turn: leaving hands it to the next member.
    await seedGame({ members: [ALICE, BOB, CAT], invited: [], turn: BOB, turnNo: 4, card: dare });
    await assertFails(updateDoc(game(BOB), { members: arrayRemove(BOB), [`profiles.${BOB}`]: deleteField() }));
    await assertFails(updateDoc(game(BOB), { members: arrayRemove(BOB), [`profiles.${BOB}`]: deleteField(), turn: ALICE, turnNo: 5, card: null }));
    await assertSucceeds(updateDoc(game(BOB), { members: arrayRemove(BOB), [`profiles.${BOB}`]: deleteField(), turn: CAT, turnNo: 5, card: null }));

    // The host takes someone out; nobody else can, and only one at a time.
    await seedGame({ members: [ALICE, BOB, CAT], invited: [DAN], turn: CAT, turnNo: 2 });
    await assertFails(updateDoc(game(BOB), { members: arrayRemove(CAT), [`profiles.${CAT}`]: deleteField(), turn: ALICE, turnNo: 3, card: null }));
    await assertFails(updateDoc(game(ALICE), {
      members: arrayRemove(BOB), invited: arrayRemove(DAN), [`profiles.${BOB}`]: deleteField(), [`profiles.${DAN}`]: deleteField(),
    }));
    await assertSucceeds(updateDoc(game(ALICE), { invited: arrayRemove(DAN), [`profiles.${DAN}`]: deleteField() }));
    await assertSucceeds(updateDoc(game(ALICE), { members: arrayRemove(CAT), [`profiles.${CAT}`]: deleteField(), turn: ALICE, turnNo: 3, card: null }));
  });

  test("leaving works without an account doc, so account deletion can always do it", async () => {
    await seedGame({ members: [ALICE, BOB], invited: [CAT] });
    await seed(async (fs) => {
      await deleteDoc(doc(fs, "users/bob/private/account"));
      await deleteDoc(doc(fs, "users/cat/private/account"));
    });
    await assertSucceeds(decline(CAT));
    await assertSucceeds(updateDoc(game(BOB), { members: arrayRemove(BOB), [`profiles.${BOB}`]: deleteField() }));
  });

  test("only the current player draws a card, once per turn", async () => {
    await seedGame({ members: [ALICE, BOB, CAT], invited: [] });
    const pick = (uid, card = dare, extra = {}) => updateDoc(game(uid), { card, updatedAt: serverTimestamp(), ...extra });
    await assertFails(pick(BOB));
    await assertFails(pick(ALICE, { mode: "kiss", prompt: "x" }));
    await assertFails(pick(ALICE, { mode: "truth", prompt: "x".repeat(301) }));
    await assertFails(pick(ALICE, { ...dare, extra: true }));
    await assertFails(pick(ALICE, dare, { turn: BOB }));
    await assertSucceeds(pick(ALICE));
    await assertFails(pick(ALICE, { mode: "truth", prompt: "What was your first impression of me?" })); // no fishing for a better card
  });

  test("only the current player answers, and the turn passes in member order", async () => {
    await seedGame({ members: [ALICE, BOB, CAT], invited: [] });
    await assertFails(answer(ALICE, { next: BOB })); // no card drawn yet
    await seed((fs) => updateDoc(doc(fs, `games/${GAME}`), { card: dare }));
    await assertFails(answer(BOB, { next: CAT })); // not Bob's turn
    await assertFails(answer(ALICE, { next: CAT })); // skips Bob
    await assertFails(answer(ALICE, { next: BOB, advance: false })); // the turn must move with the answer
    await assertFails(answer(ALICE, { next: BOB, id: "t7" }));
    await assertFails(answer(ALICE, { next: BOB, card: { mode: "truth", prompt: "Something easier" } }));
    await assertFails(answer(ALICE, { next: BOB, text: "x".repeat(501) }));
    await assertFails(answer(ALICE, { next: BOB, text: "" }));
    await assertFails(answer(ALICE, { next: BOB, pass: true, text: "nope" }));
    await assertFails(answer(ALICE, { next: BOB, extra: { senderId: BOB } }));
    await assertFails(answer(ALICE, { next: BOB, extra: { createdAt: new Date(0) } }));
    await assertSucceeds(answer(ALICE, { next: BOB }));
    // Bob draws and passes; then Cat; then it wraps around to Alice.
    await assertSucceeds(updateDoc(game(BOB), { card: dare, updatedAt: serverTimestamp() }));
    await assertSucceeds(answer(BOB, { turnNo: 1, next: CAT, pass: true, text: "" }));
    await assertSucceeds(updateDoc(game(CAT), { card: dare, updatedAt: serverTimestamp() }));
    await assertFails(answer(CAT, { turnNo: 2, next: BOB }));
    await assertSucceeds(answer(CAT, { turnNo: 2, next: ALICE }));
    const after = (await getDoc(game(ALICE))).data();
    if (after.turn !== ALICE || after.turnNo !== 3 || after.card !== null) throw new Error(`unexpected ${JSON.stringify(after)}`);
    // Answers are final.
    await assertFails(updateDoc(move(ALICE, "t0"), { text: "edited" }));
  });

  test("the host can skip a quiet player; nobody else can", async () => {
    await seedGame({ members: [ALICE, BOB, CAT], invited: [], turn: BOB, turnNo: 1, card: dare });
    const skip = (uid, next) => updateDoc(game(uid), { turn: next, turnNo: increment(1), card: null, updatedAt: serverTimestamp() });
    await assertFails(skip(CAT, CAT));
    await assertFails(skip(BOB, CAT)); // Bob passes by answering, not by skipping
    await assertFails(skip(ALICE, ALICE));
    await assertSucceeds(skip(ALICE, CAT));
  });

  test("game chat: members only, with a touch, small and well-formed", async () => {
    await seedGame();
    await assertSucceeds(chat(BOB));
    await assertSucceeds(chat(ALICE, "x".repeat(500)));
    await assertFails(chat(BOB, ""));
    await assertFails(chat(BOB, "x".repeat(501)));
    await assertFails(chat(BOB, "hi", { touch: false }));
    await assertFails(chat(BOB, "hi", { extra: { mode: "dare" } }));
    await assertFails(chat(BOB, "hi", { extra: { senderId: ALICE } }));
    await assertFails(chat(BOB, "hi", { extra: { createdAt: new Date(0) } }));
    await assertFails(chat(BOB, "hi", { extra: { type: "sticker" } }));
    await assertFails(chat(CAT)); // invited, not in
    await assertFails(chat(EVE));
    // A touch moves only updatedAt, and only to now.
    await assertFails(updateDoc(game(BOB), { updatedAt: serverTimestamp(), status: "ended" }));
    await assertFails(updateDoc(game(BOB), { updatedAt: new Date(Date.now() + HOUR) }));
  });

  test("reactions: your own, from the same five", async () => {
    await seedGame({ members: [ALICE, BOB, CAT], invited: [] });
    await seed((fs) => setDoc(doc(fs, `games/${GAME}/moves/t0`), {
      senderId: ALICE, type: "answer", text: "ok", mode: "dare", prompt: "p", pass: false, turnNo: 0, createdAt: new Date(),
    }));
    await assertSucceeds(updateDoc(move(BOB, "t0"), { "reactions.bob": "🔥" }));
    await assertSucceeds(updateDoc(move(CAT, "t0"), { "reactions.cat": "😂" }));
    await assertFails(updateDoc(move(BOB, "t0"), { "reactions.cat": "👍" }));
    await assertFails(updateDoc(move(BOB, "t0"), { "reactions.bob": "🍆" }));
    await assertFails(updateDoc(move(BOB, "t0"), { "reactions.bob": "🔥", text: "edited" }));
    await assertFails(updateDoc(move(EVE, "t0"), { "reactions.eve": "🔥" }));
    await assertSucceeds(updateDoc(move(BOB, "t0"), { "reactions.bob": deleteField() }));
  });

  test("a block between two members stops both of them posting", async () => {
    await seedGame({ members: [ALICE, BOB, CAT], invited: [] });
    await seed((fs) => setDoc(doc(fs, "users/bob/blocks/cat"), { createdAt: new Date() }));
    await assertFails(chat(BOB));
    await assertFails(chat(CAT));
    await assertSucceeds(chat(ALICE));
  });

  test("24 quiet hours end a game; then any member may clean it up", async () => {
    await seedGame({ members: [ALICE, BOB], invited: [CAT], idleHours: 25 });
    await seed((fs) => setDoc(doc(fs, `games/${GAME}/moves/m1`), { senderId: ALICE, type: "chat", text: "hi", createdAt: new Date() }));
    await assertFails(chat(BOB));
    await assertFails(updateDoc(game(ALICE), { card: dare, updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(game(BOB), { updatedAt: serverTimestamp() })); // can't revive it
    await assertFails(deleteDoc(move(EVE, "m1")));
    await assertFails(deleteDoc(move(CAT, "m1"))); // invitees decline instead
    await assertSucceeds(deleteDoc(move(BOB, "m1")));
    await assertFails(deleteDoc(game(CAT)));
    await assertSucceeds(deleteDoc(game(BOB)));
  });

  test("the host ends the game; members clean up; nobody else can", async () => {
    await seedGame();
    await seed((fs) => setDoc(doc(fs, `games/${GAME}/moves/m1`), { senderId: ALICE, type: "chat", text: "hi", createdAt: new Date() }));
    await assertFails(deleteDoc(move(BOB, "m1"))); // still live, and not Bob's move
    await assertFails(deleteDoc(game(BOB)));
    await assertFails(updateDoc(game(BOB), { status: "ended", updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(game(ALICE), { status: "paused", updatedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(game(ALICE), { status: "ended", updatedAt: serverTimestamp() }));
    await assertFails(chat(ALICE));
    await assertSucceeds(deleteDoc(move(BOB, "m1")));
    await assertSucceeds(deleteDoc(game(BOB)));
  });

  test("senders delete their own moves; the host deletes any, any time", async () => {
    await seedGame({ members: [ALICE, BOB, CAT], invited: [] });
    await seed(async (fs) => {
      await setDoc(doc(fs, `games/${GAME}/moves/b1`), { senderId: BOB, type: "chat", text: "hi", createdAt: new Date() });
      await setDoc(doc(fs, `games/${GAME}/moves/c1`), { senderId: CAT, type: "chat", text: "yo", createdAt: new Date() });
    });
    await assertFails(deleteDoc(move(CAT, "b1")));
    await assertSucceeds(deleteDoc(move(BOB, "b1")));
    await assertSucceeds(deleteDoc(move(ALICE, "c1")));
    await assertSucceeds(deleteDoc(game(ALICE)));
  });
});
