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

// ---------- chat: unsend and event rows ----------
describe("chat: unsend and event rows", () => {
  const UNSENT = "Message unsent";
  const SHOT = "Took a screenshot of the chat";
  const minutesAgo = (n) => new Date(Date.now() - n * 60 * 1000);
  const seedMessage = (id, data) => seed((fs) => setDoc(doc(fs, `matches/${MATCH}/messages/${id}`), data));
  const msgRef = (uid, id) => doc(db(uid), `matches/${MATCH}/messages/${id}`);
  // What the app sends: every content field deleted, the fixed text, server time.
  const tombstone = (extra = {}) => ({
    type: "text", text: UNSENT, unsentAt: serverTimestamp(),
    imageId: deleteField(), imageBlurhash: deleteField(), game: deleteField(), viewOnce: deleteField(),
    viewedAt: deleteField(), audioId: deleteField(), durationMs: deleteField(), date: deleteField(),
    replyTo: deleteField(), reactions: deleteField(), event: deleteField(), ...extra,
  });

  test("the sender unsends their own message within 15 minutes, once", async () => {
    await seedMessage("u1", {
      senderId: ALICE, type: "text", text: "regret", imageId: null, imageBlurhash: null, game: null,
      replyTo: { id: "m0", senderId: BOB, text: "hey", type: "text" }, reactions: { bob: "🔥" }, createdAt: minutesAgo(2),
    });
    await assertSucceeds(updateDoc(msgRef(ALICE, "u1"), tombstone()));
    const after = (await getDoc(msgRef(BOB, "u1"))).data();
    if (after.text !== UNSENT || after.replyTo !== undefined || after.reactions !== undefined) throw new Error("not a tombstone");
    // Once: a tombstone can't be unsent (or rewritten) again.
    await assertFails(updateDoc(msgRef(ALICE, "u1"), tombstone()));
    await assertFails(updateDoc(msgRef(ALICE, "u1"), { text: "edited" }));
  });

  test("only the sender, only within the window, never an outsider", async () => {
    await seedMessage("u2", { senderId: ALICE, type: "text", text: "hi", createdAt: minutesAgo(2) });
    await seedMessage("u3", { senderId: ALICE, type: "text", text: "old", createdAt: minutesAgo(16) });
    await assertFails(updateDoc(msgRef(BOB, "u2"), tombstone()));
    await assertFails(updateDoc(msgRef(EVE, "u2"), tombstone()));
    await assertFails(updateDoc(msgRef(ALICE, "u3"), tombstone()));
    await assertSucceeds(updateDoc(msgRef(ALICE, "u2"), tombstone()));
  });

  test("a tombstone keeps exactly the allowed fields", async () => {
    const original = { senderId: ALICE, type: "text", text: "hi", replyTo: { id: "m0", senderId: BOB, text: "hey", type: "text" }, createdAt: minutesAgo(1) };
    await seedMessage("u4", original);
    const ref = msgRef(ALICE, "u4");
    await assertFails(updateDoc(ref, tombstone({ text: "something else" })));
    await assertFails(updateDoc(ref, tombstone({ text: "" })));
    await assertFails(updateDoc(ref, tombstone({ type: "image" })));
    await assertFails(updateDoc(ref, tombstone({ replyTo: original.replyTo })));
    await assertFails(updateDoc(ref, tombstone({ unsentAt: new Date() })));
    await assertFails(updateDoc(ref, tombstone({ createdAt: serverTimestamp() })));
    await assertFails(updateDoc(ref, tombstone({ senderId: BOB })));
    await assertFails(updateDoc(ref, tombstone({ note: "x" })));
    await assertSucceeds(updateDoc(ref, tombstone()));
  });

  test("unsending a photo deletes its image in the same batch; event rows can't be unsent", async () => {
    await seed(async (fs) => {
      await setDoc(doc(fs, `matches/${MATCH}/images/i9`), { owner: ALICE, data: jpeg(64), createdAt: new Date() });
      await setDoc(doc(fs, `matches/${MATCH}/messages/u5`), { senderId: ALICE, type: "image", text: "", imageId: "i9", imageBlurhash: "LEHV6nWB2yk8", viewOnce: true, createdAt: minutesAgo(3) });
      await setDoc(doc(fs, `matches/${MATCH}/messages/u6`), { senderId: ALICE, type: "event", text: SHOT, event: "screenshot", createdAt: minutesAgo(1) });
    });
    const fs = db(ALICE);
    const batch = writeBatch(fs);
    batch.update(doc(fs, `matches/${MATCH}/messages/u5`), tombstone());
    batch.delete(doc(fs, `matches/${MATCH}/images/i9`));
    await assertSucceeds(batch.commit());
    await assertFails(updateDoc(msgRef(ALICE, "u6"), tombstone()));
  });

  test("the sender swaps their own latest preview for the tombstone text, keeping its time", async () => {
    const sentAt = Timestamp.fromDate(minutesAgo(2));
    const preview = (extra = {}) => ({ text: UNSENT, senderId: ALICE, type: "text", sentAt, ...extra });
    await seed((fs) => updateDoc(doc(fs, `matches/${MATCH}`), { lastMessage: { text: "regret", senderId: ALICE, type: "text", sentAt } }));
    const match = (uid) => doc(db(uid), `matches/${MATCH}`);
    await assertFails(updateDoc(match(ALICE), { lastMessage: preview({ sentAt: Timestamp.fromDate(minutesAgo(1)) }) }));
    await assertFails(updateDoc(match(ALICE), { lastMessage: preview({ text: "edited" }) }));
    await assertFails(updateDoc(match(ALICE), { lastMessage: preview({ type: "image" }) }));
    await assertFails(updateDoc(match(BOB), { lastMessage: preview({ senderId: BOB }) }));
    await assertSucceeds(updateDoc(match(ALICE), { lastMessage: preview() }));

    // Not Bob's preview to blank, and not after the window.
    const bobs = Timestamp.fromDate(minutesAgo(1));
    await seed((fs) => updateDoc(doc(fs, `matches/${MATCH}`), { lastMessage: { text: "yo", senderId: BOB, type: "text", sentAt: bobs } }));
    await assertFails(updateDoc(match(ALICE), { lastMessage: preview({ sentAt: bobs }) }));
    const stale = Timestamp.fromDate(minutesAgo(20));
    await seed((fs) => updateDoc(doc(fs, `matches/${MATCH}`), { lastMessage: { text: "old", senderId: ALICE, type: "text", sentAt: stale } }));
    await assertFails(updateDoc(match(ALICE), { lastMessage: preview({ sentAt: stale }) }));
  });

  test("event rows: a known code with its fixed text, nothing attached", async () => {
    const fs = db(ALICE);
    const event = (extra = {}) => ({ senderId: ALICE, type: "event", text: SHOT, event: "screenshot", createdAt: serverTimestamp(), ...extra });
    const post = (data) => setDoc(doc(fs, `matches/${MATCH}/messages/${Math.random().toString(36).slice(2)}`), data);
    await assertSucceeds(post(event()));
    await assertFails(post(event({ event: "recording" })));
    await assertFails(post(event({ text: "Arjun stole your photos" })));
    await assertFails(post(event({ event: null })));
    await assertFails(post({ senderId: ALICE, type: "event", text: SHOT, createdAt: serverTimestamp() }));
    await assertFails(post(event({ replyTo: { id: "m0", senderId: BOB, text: "hey", type: "text" } })));
    await assertFails(post(event({ viewOnce: false })));
    await assertFails(post(event({ senderId: BOB })));
    await assertFails(post({ senderId: ALICE, type: "text", text: "hi", event: "screenshot", createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(db(EVE), `matches/${MATCH}/messages/e1`), event({ senderId: EVE })));
    // Never the chat's preview, and never quoted.
    await assertFails(updateDoc(doc(fs, `matches/${MATCH}`), { lastMessage: { text: SHOT, senderId: ALICE, type: "event", sentAt: serverTimestamp() } }));
    await assertFails(post({ senderId: ALICE, type: "text", text: "hi", createdAt: serverTimestamp(), replyTo: { id: "e0", senderId: ALICE, text: SHOT, type: "event" } }));
    // Blocks stop them like any message.
    await seed((s) => setDoc(doc(s, "users/bob/blocks/alice"), { createdAt: new Date() }));
    await assertFails(post(event()));
  });
});
// ---------- end chat: unsend and event rows ----------

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

describe("AI chat settings in the account", () => {
  const config = (extra = {}) => ({
    provider: "claude", models: { claude: "claude-opus-5-5" }, baseUrl: "", pace: "natural", enabled: true,
    firstMove: true, voiceNotes: true, immersive: true, monthlyCap: 0, keys: { claude: "sk-ant-test" },
    changedAt: 1, updatedAt: serverTimestamp(), ...extra,
  });
  const path = (uid) => `users/${uid}/ai/config`;

  test("the owner can save, read, update and delete their AI settings", async () => {
    const fs = db(ALICE);
    await assertSucceeds(setDoc(doc(fs, path(ALICE)), config()));
    await assertSucceeds(getDoc(doc(fs, path(ALICE))));
    await assertSucceeds(setDoc(doc(fs, path(ALICE)), config({ provider: "compatible", baseUrl: "https://openrouter.ai/api/v1", keys: { compatible: "k" } })));
    await assertSucceeds(deleteDoc(doc(fs, path(ALICE))));
  });

  test("nobody else can read or write them", async () => {
    await seed((fs) => setDoc(doc(fs, path(ALICE)), { ...config(), updatedAt: new Date() }));
    await assertFails(getDoc(doc(db(BOB), path(ALICE))));
    await assertFails(setDoc(doc(db(BOB), path(ALICE)), config()));
    await assertFails(deleteDoc(doc(db(BOB), path(ALICE))));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), path(ALICE))));
  });

  test("only the config doc, only whitelisted fields, sizes capped", async () => {
    const fs = db(ALICE);
    await assertFails(setDoc(doc(fs, `users/${ALICE}/ai/other`), config()));
    await assertFails(setDoc(doc(fs, path(ALICE)), config({ extra: true })));
    await assertFails(setDoc(doc(fs, path(ALICE)), config({ provider: "mystery" })));
    await assertFails(setDoc(doc(fs, path(ALICE)), config({ keys: { claude: "x".repeat(401) } })));
    await assertFails(setDoc(doc(fs, path(ALICE)), config({ keys: { mystery: "k" } })));
    await assertFails(setDoc(doc(fs, path(ALICE)), config({ baseUrl: "http://insecure.example" })));
    await assertFails(setDoc(doc(fs, path(ALICE)), config({ monthlyCap: -1 })));
    await assertFails(setDoc(doc(fs, path(ALICE)), config({ updatedAt: new Date(0) })));
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

// ---------- presence, Spotlight and like notes (Discover & profiles) ----------

describe("presence: lastActiveAt and hideActivity", () => {
  const midnight = () => {
    const n = new Date();
    return Timestamp.fromDate(new Date(Date.UTC(n.getUTCFullYear(), n.getUTCMonth(), n.getUTCDate())));
  };
  const inMinutes = (m) => Timestamp.fromMillis(Date.now() + m * 60_000);

  test("the heartbeat writes server time; never the future", async () => {
    const fs = db(ALICE);
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { lastActiveAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { lastActiveAt: inMinutes(-90) }));
    // A future value would pin a profile to the top of everyone's deck.
    await assertFails(updateDoc(doc(fs, "users/alice"), { lastActiveAt: inMinutes(60) }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { lastActiveAt: "now" }));
    await assertFails(updateDoc(doc(db(EVE), "users/alice"), { lastActiveAt: serverTimestamp() }));
  });

  test("hideActivity is an optional boolean", async () => {
    const fs = db(ALICE);
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { hideActivity: false }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { hideActivity: "yes" }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { hideActivity: null }));
    await assertFails(updateDoc(doc(db(EVE), "users/alice"), { hideActivity: true }));
  });

  test("while hidden, lastActiveAt is only ever a UTC midnight", async () => {
    const fs = db(ALICE);
    // Turning it on must rewrite the precise heartbeat in the same write.
    await assertFails(updateDoc(doc(fs, "users/alice"), { hideActivity: true }));
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { hideActivity: true, lastActiveAt: midnight() }));
    // Today's midnight again (once a day) is fine; the time of day is not.
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { lastActiveAt: midnight() }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { lastActiveAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(fs, "users/alice"), { lastActiveAt: inMinutes(-5) }));
    // Other edits carry on while hidden.
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { bio: "still here" }));
    // Turning it back on resumes the precise heartbeat.
    await assertSucceeds(updateDoc(doc(fs, "users/alice"), { hideActivity: false, lastActiveAt: serverTimestamp() }));
  });
});

describe("Spotlight: once per UTC day, at most 30 minutes", () => {
  const today = () => new Date().toISOString().slice(0, 10);
  const yesterday = () => new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);
  const inMinutes = (m) => Timestamp.fromMillis(Date.now() + m * 60_000);

  // The client's write: today's counter at its next step and spotlightUntil, in one batch.
  function startSpotlight(uid, { until = inMinutes(29), counter = 1, day = today(), extra = {} } = {}) {
    const fs = db(uid);
    const batch = writeBatch(fs);
    batch.set(doc(fs, `users/${uid}/limits/spotlight_${day}`), { spotlights: counter, ...extra });
    batch.update(doc(fs, `users/${uid}`), { spotlightUntil: until });
    return batch.commit();
  }

  test("a boost with today's counter goes through", async () => {
    await assertSucceeds(startSpotlight(ALICE));
    const snap = await assertSucceeds(getDoc(doc(db(ALICE), `users/alice/limits/spotlight_${today()}`)));
    if (snap.data().spotlights !== 1) throw new Error("counter not at 1");
  });

  test("only one a day: the counter can't be reused or raised", async () => {
    await assertSucceeds(startSpotlight(ALICE));
    await assertSucceeds(updateDoc(doc(db(ALICE), "users/alice"), { spotlightUntil: null }));
    await assertFails(startSpotlight(ALICE, { counter: 1 }));
    await assertFails(startSpotlight(ALICE, { counter: 2 }));
    // Without touching the counter at all.
    await assertFails(updateDoc(doc(db(ALICE), "users/alice"), { spotlightUntil: inMinutes(20) }));
    // Nobody resets it.
    await assertFails(deleteDoc(doc(db(ALICE), `users/alice/limits/spotlight_${today()}`)));
    // It's per user.
    await assertSucceeds(startSpotlight(BOB));
  });

  test("at most 30 minutes, a timestamp, and not on a new profile", async () => {
    await assertFails(startSpotlight(ALICE, { until: inMinutes(45) }));
    await assertFails(startSpotlight(ALICE, { until: "soon" }));
    // Already over: there's nothing to start.
    await assertFails(startSpotlight(ALICE, { until: inMinutes(-1) }));
    const fs = db("newbie");
    const batch = writeBatch(fs);
    batch.set(doc(fs, "users/newbie/private/account"), { ...adultBirth, termsVersion: "2026-10", termsAcceptedAt: serverTimestamp() });
    batch.set(doc(fs, "users/newbie"), { uid: "newbie", age: adultAge, onboarded: false, spotlightUntil: inMinutes(10) });
    await assertFails(batch.commit());
  });

  test("the counter only moves with a boost, only today's, only its own field", async () => {
    await assertFails(setDoc(doc(db(ALICE), `users/alice/limits/spotlight_${today()}`), { spotlights: 1 }));
    await assertFails(startSpotlight(ALICE, { day: yesterday() }));
    await assertFails(startSpotlight(ALICE, { extra: { reports: 3 } }));
    await assertFails(startSpotlight(ALICE, { counter: 0 }));
    // Nobody writes someone else's counter or profile.
    const eve = db(EVE);
    const batch = writeBatch(eve);
    batch.set(doc(eve, `users/alice/limits/spotlight_${today()}`), { spotlights: 1 });
    batch.update(doc(eve, "users/alice"), { spotlightUntil: inMinutes(20) });
    await assertFails(batch.commit());
    // Owner-only reads, like the report counter.
    await assertSucceeds(startSpotlight(ALICE));
    await assertFails(getDoc(doc(db(EVE), `users/alice/limits/spotlight_${today()}`)));
  });

  test("turning it off is always allowed, and other edits carry on while it runs", async () => {
    await assertSucceeds(startSpotlight(ALICE));
    await assertSucceeds(updateDoc(doc(db(ALICE), "users/alice"), { bio: "boosted" }));
    await assertSucceeds(updateDoc(doc(db(ALICE), "users/alice"), { spotlightUntil: deleteField() }));
  });

  test("the report counter still works next to it", async () => {
    await assertSucceeds(startSpotlight(ALICE));
    const fs = db(ALICE);
    const batch = writeBatch(fs);
    batch.set(doc(fs, `users/alice/limits/${today()}`), { reports: 1 });
    batch.set(doc(fs, `reports/alice_${today()}_1`), {
      reporterId: ALICE, targetId: EVE, reason: "spam_scam", details: "", matchId: null, createdAt: serverTimestamp(), status: "open",
    });
    await assertSucceeds(batch.commit());
    // And a Spotlight step can't be taken on the report counter.
    const sneaky = writeBatch(fs);
    sneaky.set(doc(fs, `users/alice/limits/${today()}`), { spotlights: 1 });
    sneaky.update(doc(fs, "users/alice"), { spotlightUntil: inMinutes(20) });
    await assertFails(sneaky.commit());
  });
});

describe("like notes on prompts and photos", () => {
  const note = (extra = {}) => ({ kind: "prompt", ref: "p_flirt_style", text: "Okay, this made me laugh.", ...extra });
  const like = (n) => ({ createdAt: serverTimestamp(), super: false, note: n });

  test("a like may carry one well-formed note", async () => {
    const fs = db(EVE);
    await assertSucceeds(setDoc(doc(fs, "users/alice/likedBy/eve"), like(note())));
    await assertSucceeds(setDoc(doc(fs, "users/bob/likedBy/eve"), like(note({ kind: "photo", ref: "4b0c6f1e-2a7d-4c52-9a51-0d3b7f2e9c11", text: "" }))));
    await assertSucceeds(setDoc(doc(fs, `users/${DEMO}/likedBy/eve`), like(note({ text: "x".repeat(140) }))));
    // Only the liked person reads it, as before.
    await assertSucceeds(getDoc(doc(db(ALICE), "users/alice/likedBy/eve")));
    await assertFails(getDoc(doc(db(EVE), "users/alice/likedBy/eve")));
  });

  test("notes are strict", async () => {
    const fs = db(EVE);
    const bad = [
      note({ text: "x".repeat(141) }),
      note({ kind: "bio" }),
      note({ ref: "" }),
      note({ ref: "https://example.com/a.jpg" }),
      note({ ref: "x".repeat(129) }),
      note({ text: 42 }),
      note({ extra: true }),
      { kind: "prompt", ref: "p_flirt_style" },
      "nice prompt",
      null,
    ];
    for (const n of bad) await assertFails(setDoc(doc(fs, "users/alice/likedBy/eve"), like(n)));
    await assertFails(setDoc(doc(fs, "users/alice/likedBy/eve"), { createdAt: serverTimestamp(), super: false, comment: "hi" }));
  });

  test("my own swipe keeps a copy; a nope never carries one", async () => {
    const fs = db(ALICE);
    await assertSucceeds(setDoc(doc(fs, "users/alice/swipes/eve"), { direction: "like", createdAt: serverTimestamp(), note: note() }));
    await assertSucceeds(setDoc(doc(fs, "users/alice/swipes/bob"), { direction: "super", createdAt: serverTimestamp(), note: note({ kind: "photo", ref: "bob-0" }) }));
    await assertFails(setDoc(doc(fs, `users/alice/swipes/${DEMO}`), { direction: "nope", createdAt: serverTimestamp(), note: note() }));
    await assertFails(setDoc(doc(fs, `users/alice/swipes/${DEMO}`), { direction: "like", createdAt: serverTimestamp(), note: note({ text: "x".repeat(141) }) }));
    await assertSucceeds(getDoc(doc(fs, "users/alice/swipes/eve")));
  });

  test("a noted like still makes the match, and the note opens the chat as the liker", async () => {
    await seed((s) => setDoc(doc(s, "users/eve/likedBy/alice"), { createdAt: new Date(), super: false }));
    const fs = db(EVE);
    const batch = writeBatch(fs);
    batch.set(doc(fs, "users/eve/swipes/alice"), { direction: "like", createdAt: serverTimestamp(), note: note() });
    batch.set(doc(fs, "users/alice/likedBy/eve"), like(note()));
    batch.set(doc(fs, "matches/alice_eve"), {
      users: [ALICE, EVE], profiles: {}, createdAt: serverTimestamp(), superLike: false, isDemo: false, lastMessage: null, lastRead: {},
    });
    await assertSucceeds(batch.commit());
    // Eve's first message quotes Alice's prompt; the quote's sender is Alice (a match participant).
    const first = writeBatch(fs);
    first.set(doc(fs, "matches/alice_eve/messages/n1"), {
      senderId: EVE, type: "text", text: "Okay, this made me laugh.", imageId: null, imageBlurhash: null, game: null, createdAt: serverTimestamp(),
      replyTo: { id: "profile-prompt-p_flirt_style", senderId: ALICE, text: "My flirting style is: eye contact and bad puns", type: "text" },
    });
    first.update(doc(fs, "matches/alice_eve"), { lastMessage: { text: "Okay, this made me laugh.", senderId: EVE, type: "text", sentAt: serverTimestamp() } });
    await assertSucceeds(first.commit());
    // Never as the person who was liked.
    await assertFails(setDoc(doc(fs, "matches/alice_eve/messages/n2"), {
      senderId: ALICE, type: "text", text: "spoofed", imageId: null, imageBlurhash: null, game: null, createdAt: serverTimestamp(),
    }));
  });
});
