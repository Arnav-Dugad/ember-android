// Security rules tests for firestore.rules and storage.rules.
// Run from this folder with `npm test`; it boots the emulators for a demo project.
import { readFileSync } from "node:fs";
import { after, before, beforeEach, describe, test } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import {
  deleteDoc, doc, getDoc, serverTimestamp, setDoc, updateDoc, collection, query, where, getDocs,
} from "firebase/firestore";
import { getBytes, ref, uploadBytes } from "firebase/storage";

const ALICE = "alice";
const BOB = "bob";
const EVE = "eve";
const MATCH = "alice_bob";

let env;
const adult = (uid) => env.authenticatedContext(uid, { adult: true });

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-ember",
    firestore: { rules: readFileSync("../firestore.rules", "utf8"), host: "127.0.0.1", port: 8080 },
    storage: { rules: readFileSync("../storage.rules", "utf8"), host: "127.0.0.1", port: 9199 },
  });
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "users/alice"), { uid: ALICE, name: "Alice", age: 27, onboarded: true });
    await setDoc(doc(db, "users/bob"), { uid: BOB, name: "Bob", age: 29, onboarded: true });
    await setDoc(doc(db, "users/eve"), { uid: EVE, name: "Eve", age: 31, onboarded: true });
    await setDoc(doc(db, `matches/${MATCH}`), { users: [ALICE, BOB], lastRead: {}, profiles: {} });
    await setDoc(doc(db, "users/bob/matchOnly/details"), { boundaries: ["slow"], turnOns: [] });
    await setDoc(doc(db, "users/alice/private/account"), { birthDate: "1999-01-01" });
    await setDoc(doc(db, "albums/bob/photos/p1"), { path: "private/bob/p1.jpg", createdAt: new Date() });
  });
});

describe("profiles", () => {
  test("adults can read profiles, non-adults cannot", async () => {
    await assertSucceeds(getDoc(doc(adult(EVE).firestore(), "users/alice")));
    await assertFails(getDoc(doc(env.authenticatedContext(EVE).firestore(), "users/alice")));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "users/alice")));
  });

  test("owners edit their profile but never age or isDemo", async () => {
    const db = adult(ALICE).firestore();
    await assertSucceeds(updateDoc(doc(db, "users/alice"), { bio: "hi", lat: 40.71, lng: -74.01, geohash: "dr5re" }));
    await assertFails(updateDoc(doc(db, "users/alice"), { age: 40 }));
    await assertFails(updateDoc(doc(db, "users/alice"), { isDemo: true }));
    await assertFails(updateDoc(doc(db, "users/alice"), { lat: 40.712776 }));
    await assertFails(updateDoc(doc(db, "users/alice"), { geohash: "dr5regw" }));
    await assertFails(updateDoc(doc(db, "users/alice"), { password: "x" }));
    await assertFails(updateDoc(doc(adult(EVE).firestore(), "users/alice"), { bio: "pwned" }));
  });

  test("blocked users cannot read the blocker", async () => {
    await env.withSecurityRulesDisabled((ctx) => setDoc(doc(ctx.firestore(), "users/alice/blocks/eve"), { createdAt: new Date() }));
    await assertFails(getDoc(doc(adult(EVE).firestore(), "users/alice")));
  });

  test("boundaries are visible to matches only", async () => {
    await assertSucceeds(getDoc(doc(adult(ALICE).firestore(), "users/bob/matchOnly/details")));
    await assertFails(getDoc(doc(adult(EVE).firestore(), "users/bob/matchOnly/details")));
  });

  test("private account doc is owner-read, server-write", async () => {
    await assertSucceeds(getDoc(doc(adult(ALICE).firestore(), "users/alice/private/account")));
    await assertFails(setDoc(doc(adult(ALICE).firestore(), "users/alice/private/account"), { birthDate: "1990-01-01" }));
  });
});

describe("swipes", () => {
  test("create-only with server time", async () => {
    const db = adult(ALICE).firestore();
    await assertSucceeds(setDoc(doc(db, "users/alice/swipes/eve"), { direction: "like", createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(db, "users/alice/swipes/bob"), { direction: "like", createdAt: new Date(0) }));
    await assertFails(setDoc(doc(db, "users/alice/swipes/bob"), { direction: "love", createdAt: serverTimestamp() }));
    await assertFails(deleteDoc(doc(db, "users/alice/swipes/eve")));
    await assertFails(setDoc(doc(adult(EVE).firestore(), "users/alice/swipes/bob"), { direction: "like", createdAt: serverTimestamp() }));
  });
});

describe("matches and chat", () => {
  test("only participants read the match", async () => {
    await assertSucceeds(getDoc(doc(adult(ALICE).firestore(), `matches/${MATCH}`)));
    await assertFails(getDoc(doc(adult(EVE).firestore(), `matches/${MATCH}`)));
    await assertSucceeds(getDocs(query(collection(adult(ALICE).firestore(), "matches"), where("users", "array-contains", ALICE))));
  });

  test("participants move only their own read marker", async () => {
    const db = adult(ALICE).firestore();
    await assertSucceeds(updateDoc(doc(db, `matches/${MATCH}`), { "lastRead.alice": serverTimestamp() }));
    await assertFails(updateDoc(doc(db, `matches/${MATCH}`), { "lastRead.bob": serverTimestamp() }));
    await assertFails(updateDoc(doc(db, `matches/${MATCH}`), { lastMessage: { text: "fake" } }));
  });

  test("messages are validated and immutable", async () => {
    const db = adult(ALICE).firestore();
    const msg = doc(db, `matches/${MATCH}/messages/m1`);
    await assertSucceeds(setDoc(msg, { senderId: ALICE, type: "text", text: "hey", createdAt: serverTimestamp() }));
    await assertFails(updateDoc(msg, { text: "edited" }));
    await assertFails(setDoc(doc(db, `matches/${MATCH}/messages/m2`), { senderId: BOB, type: "text", text: "spoof", createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(adult(EVE).firestore(), `matches/${MATCH}/messages/m3`), { senderId: EVE, type: "text", text: "hi", createdAt: serverTimestamp() }));
    await assertSucceeds(setDoc(doc(db, `matches/${MATCH}/messages/m4`), {
      senderId: ALICE, type: "image", text: "", imagePath: `chats/${MATCH}/m4.jpg`, imageBlurhash: "LEHV6nWB2yk8", createdAt: serverTimestamp(),
    }));
    await assertFails(setDoc(doc(db, `matches/${MATCH}/messages/m5`), {
      senderId: ALICE, type: "image", text: "", imagePath: "private/bob/p1.jpg", createdAt: serverTimestamp(),
    }));
    await assertSucceeds(setDoc(doc(db, `matches/${MATCH}/messages/m6`), {
      senderId: ALICE, type: "game", text: "", game: { mode: "dare", prompt: "Send your best pickup line" }, createdAt: serverTimestamp(),
    }));
  });
});

describe("private albums", () => {
  test("request, approve, view, revoke", async () => {
    const alice = adult(ALICE).firestore();
    const bob = adult(BOB).firestore();
    const access = doc(alice, "albums/bob/access/alice");
    const req = { ownerId: BOB, viewerId: ALICE, status: "pending", requestedAt: serverTimestamp(), updatedAt: serverTimestamp() };

    await assertFails(getDoc(doc(alice, "albums/bob/photos/p1")));
    await assertFails(setDoc(doc(adult(EVE).firestore(), "albums/bob/access/eve"), { ...req, viewerId: EVE }));
    await assertFails(setDoc(access, { ...req, status: "approved" }));
    await assertSucceeds(setDoc(access, req));
    await assertFails(updateDoc(access, { status: "approved", updatedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(bob, "albums/bob/access/alice"), { status: "approved", updatedAt: serverTimestamp() }));
    await assertSucceeds(getDoc(doc(alice, "albums/bob/photos/p1")));
    await assertSucceeds(deleteDoc(doc(bob, "albums/bob/access/alice")));
    await assertFails(getDoc(doc(alice, "albums/bob/photos/p1")));
  });

  test("storage: private photos follow the access doc", async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await uploadBytes(ref(ctx.storage(), "private/bob/p1.jpg"), new Uint8Array([1, 2, 3]), { contentType: "image/jpeg" });
    });
    const file = ref(adult(ALICE).storage(), "private/bob/p1.jpg");
    await assertFails(getBytes(file));
    await env.withSecurityRulesDisabled((ctx) =>
      setDoc(doc(ctx.firestore(), "albums/bob/access/alice"), { ownerId: BOB, viewerId: ALICE, status: "approved" }));
    await assertSucceeds(getBytes(file));
    await assertSucceeds(getBytes(ref(adult(BOB).storage(), "private/bob/p1.jpg")));
    await assertFails(getBytes(ref(adult(EVE).storage(), "private/bob/p1.jpg")));
  });

  test("storage: uploads must be your own images", async () => {
    const img = new Uint8Array([1, 2, 3]);
    await assertSucceeds(uploadBytes(ref(adult(ALICE).storage(), "users/alice/photos/a.jpg"), img, { contentType: "image/jpeg" }));
    await assertFails(uploadBytes(ref(adult(ALICE).storage(), "users/bob/photos/a.jpg"), img, { contentType: "image/jpeg" }));
    await assertFails(uploadBytes(ref(adult(ALICE).storage(), "users/alice/photos/a.txt"), img, { contentType: "text/plain" }));
    await assertSucceeds(uploadBytes(ref(adult(ALICE).storage(), `chats/${MATCH}/m4.jpg`), img, { contentType: "image/jpeg" }));
    await assertFails(uploadBytes(ref(adult(EVE).storage(), `chats/${MATCH}/x.jpg`), img, { contentType: "image/jpeg" }));
  });
});
