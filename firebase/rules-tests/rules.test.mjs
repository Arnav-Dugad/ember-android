// Security rules tests for firestore.rules (Spark plan: the rules are the server).
// Run from this folder with `npm test`; it boots the Firestore emulator for a demo project.
import { readFileSync } from "node:fs";
import { after, before, beforeEach, describe, test } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import {
  Bytes, collection, deleteDoc, deleteField, doc, getDoc, getDocs, limit, orderBy, query, serverTimestamp, setDoc,
  updateDoc, where, writeBatch,
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

  test("likedBy is written by the liker and readable by nobody", async () => {
    await assertSucceeds(setDoc(doc(db(EVE), "users/alice/likedBy/eve"), { createdAt: serverTimestamp(), super: false }));
    await assertFails(setDoc(doc(db(EVE), "users/alice/likedBy/bob"), { createdAt: serverTimestamp(), super: false }));
    await assertFails(getDoc(doc(db(ALICE), "users/alice/likedBy/eve")));
    await assertFails(getDocs(collection(db(ALICE), "users/alice/likedBy")));
    await assertSucceeds(deleteDoc(doc(db(EVE), "users/alice/likedBy/eve")));
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

  test("reports are create-only", async () => {
    const report = {
      reporterId: ALICE, targetId: EVE, reason: "harassment", details: "", matchId: null, createdAt: serverTimestamp(), status: "open",
    };
    await assertSucceeds(setDoc(doc(db(ALICE), "reports/r1"), report));
    await assertFails(setDoc(doc(db(ALICE), "reports/r2"), { ...report, reporterId: BOB }));
    await assertFails(setDoc(doc(db(ALICE), "reports/r3"), { ...report, reason: "bored" }));
    await assertFails(getDoc(doc(db(ALICE), "reports/r1")));
  });

  test("demo metadata is invisible", async () => {
    await assertFails(getDoc(doc(db(ALICE), `demoMeta/${DEMO}`)));
    await assertFails(setDoc(doc(db(ALICE), "demoMeta/alice"), { likesBack: true }));
  });
});
