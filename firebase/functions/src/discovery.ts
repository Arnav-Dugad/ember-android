import { distanceBetween, geohashQueryBounds } from "geofire-common";
import type { DocumentData, QueryDocumentSnapshot } from "firebase-admin/firestore";
import { onCall } from "firebase-functions/v2/https";
import { LOOKING_FOR, callableOptions, clampNumber, db, hideDemoProfiles, inputObject, millis, requireAdult } from "./util.js";

const PER_BOUND_LIMIT = 300;
const FALLBACK_LIMIT = 400;
const AFTER_DARK_INTENTS = new Set(["casual", "exploring"]);

/** Only these fields ever leave the server in a deck. No coordinates, no geohash. */
function publicProfile(doc: QueryDocumentSnapshot<DocumentData>, distanceKm: number | null) {
  const d = doc.data();
  return {
    uid: doc.id,
    name: d.name ?? "",
    age: d.age ?? null,
    gender: d.gender ?? null,
    bio: d.bio ?? "",
    photos: Array.isArray(d.photos) ? d.photos : [],
    prompts: Array.isArray(d.prompts) ? d.prompts : [],
    interests: Array.isArray(d.interests) ? d.interests : [],
    vibes: Array.isArray(d.vibes) ? d.vibes : [],
    lookingFor: d.lookingFor ?? null,
    heightCm: typeof d.heightCm === "number" ? d.heightCm : null,
    city: d.city ?? "",
    afterDark: d.afterDark === true,
    privateAlbumCount: typeof d.privateAlbumCount === "number" ? d.privateAlbumCount : 0,
    isDemo: d.isDemo === true,
    lastActiveAt: millis(d.lastActiveAt),
    distanceKm,
  };
}

function shuffle<T>(items: T[]): T[] {
  for (let i = items.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [items[i], items[j]] = [items[j], items[i]];
  }
  return items;
}

/**
 * Builds the caller's swipe deck on the server, so the client never needs to read anyone's block
 * list, and filters it by distance, age, intent, mutual gender interest and After Dark.
 */
export const getDiscoveryDeck = onCall(callableOptions, async (request) => {
  const uid = requireAdult(request);
  const input = inputObject(request.data);

  const maxDistanceKm = clampNumber(input.maxDistanceKm, 1, 500, 50);
  const minAge = Math.round(clampNumber(input.minAge, 18, 100, 18));
  const maxAge = Math.round(clampNumber(input.maxAge, minAge, 100, 99));
  const limit = Math.round(clampNumber(input.limit, 1, 50, 20));
  const afterDark = input.afterDark === true;
  const lookingFor = new Set(
    (Array.isArray(input.lookingFor) ? input.lookingFor : [])
      .filter((v): v is string => typeof v === "string" && (LOOKING_FOR as readonly string[]).includes(v)),
  );

  const userRef = db.collection("users").doc(uid);
  const [me, swipes, blocks, hideDemo] = await Promise.all([
    userRef.get(),
    userRef.collection("swipes").select().get(),
    userRef.collection("blocks").select().get(),
    hideDemoProfiles(),
  ]);
  if (!me.exists) return { profiles: [] };

  const exclude = new Set<string>([uid]);
  swipes.docs.forEach((d) => exclude.add(d.id));
  // blockUser writes a block doc on both sides, so this covers "I blocked them" and "they blocked me".
  blocks.docs.forEach((d) => exclude.add(d.id));

  const myGender = me.get("gender") as string | undefined;
  const myInterest = new Set((me.get("interestedIn") as string[] | undefined) ?? []);
  const lat = me.get("lat");
  const lng = me.get("lng");
  const hasLocation = typeof lat === "number" && typeof lng === "number";

  const users = db.collection("users");
  let candidates: QueryDocumentSnapshot<DocumentData>[];
  if (hasLocation) {
    const bounds = geohashQueryBounds([lat, lng], maxDistanceKm * 1000);
    const snaps = await Promise.all(
      bounds.map(([start, end]) =>
        users.where("onboarded", "==", true).orderBy("geohash").startAt(start).endAt(end).limit(PER_BOUND_LIMIT).get(),
      ),
    );
    const seen = new Map<string, QueryDocumentSnapshot<DocumentData>>();
    snaps.forEach((s) => s.docs.forEach((d) => seen.set(d.id, d)));
    candidates = [...seen.values()];
  } else {
    candidates = (await users.where("onboarded", "==", true).limit(FALLBACK_LIMIT).get()).docs;
  }

  const picked = [];
  for (const doc of candidates) {
    if (exclude.has(doc.id)) continue;
    const d = doc.data();
    if (hideDemo && d.isDemo === true) continue;
    if (!Array.isArray(d.photos) || d.photos.length === 0) continue;
    if (typeof d.age !== "number" || d.age < minAge || d.age > maxAge) continue;
    if (lookingFor.size > 0 && !lookingFor.has(d.lookingFor)) continue;
    if (afterDark && d.afterDark !== true && !AFTER_DARK_INTENTS.has(d.lookingFor)) continue;

    // Mutual interest: an empty interestedIn list means "anyone".
    const theirInterest: string[] = Array.isArray(d.interestedIn) ? d.interestedIn : [];
    if (myInterest.size > 0 && !myInterest.has(d.gender)) continue;
    if (theirInterest.length > 0 && (!myGender || !theirInterest.includes(myGender))) continue;

    let distanceKm: number | null = null;
    if (hasLocation && typeof d.lat === "number" && typeof d.lng === "number") {
      const exact = distanceBetween([lat, lng], [d.lat, d.lng]);
      if (exact > maxDistanceKm) continue;
      distanceKm = Math.max(1, Math.ceil(exact));
    }
    picked.push(publicProfile(doc, distanceKm));
  }

  // Real people first, each group in random order so the deck does not feel stale.
  const real = shuffle(picked.filter((p) => !p.isDemo));
  const demo = shuffle(picked.filter((p) => p.isDemo));
  return { profiles: [...real, ...demo].slice(0, limit) };
});
