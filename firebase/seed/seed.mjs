#!/usr/bin/env node
// Seeds Ember with demo profiles (Firestore only: the free Spark plan has no Cloud Storage).
//
// Every profile is fictional. Each one's main photo is an AI-generated portrait of a person who
// does not exist (portraits/, served from this repo through jsDelivr); the others are DiceBear
// illustrations (CC0). Nothing is scraped, nothing is explicit.
//
// Every profile carries `isDemo: true`. The app shows a "Demo" badge on them, and no code path
// ever sends a message as a demo profile.
//
//   node seed.mjs --dry-run --count 6                    write .cache/profiles.json, touch nothing remote
//   node seed.mjs --cli-auth --project my-proj           seed 150 profiles using your `firebase login`
//   node seed.mjs --wipe --cli-auth --project my-proj    delete all demo profiles and their matches
//
// Credentials, in order: serviceAccount.json next to this file (git-ignored),
// GOOGLE_APPLICATION_CREDENTIALS, or --cli-auth (the Firebase CLI's own login).
import { execSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { encode as encodeBlurhash } from "blurhash";
import { geohashForLocation } from "geofire-common";
import sharp from "sharp";

const HERE = dirname(fileURLToPath(import.meta.url));
const readJson = (name) => JSON.parse(readFileSync(join(HERE, name), "utf8"));

const { values: args } = parseArgs({
  options: {
    // Default: every person in shared/people.json, or 150 generated profiles without it.
    count: { type: "string" },
    "dry-run": { type: "boolean", default: false },
    wipe: { type: "boolean", default: false },
    "cli-auth": { type: "boolean", default: false },
    project: { type: "string" },
  },
});

const COUNT = Math.max(1, Math.min(1000, Number(args.count) || 150));
const DRY_RUN = args["dry-run"];
const PORTRAIT_BASE = "https://cdn.jsdelivr.net/gh/Arnav-Dugad/ember-android@main/firebase/seed/portraits/";

const PROMPTS = readJson("prompts.json");
const ANSWERS = readJson("shared/answers.json");
const BIOS = readJson("shared/bios.json");
const CATALOG = readJson("catalog.json");

// ---------- deterministic randomness ----------

function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = mulberry32(0xe3be5);
const int = (min, max) => min + Math.floor(rand() * (max - min + 1));
const pick = (list) => list[Math.floor(rand() * list.length)];
const chance = (p) => rand() < p;
function sample(list, n) {
  const copy = [...list];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy.slice(0, n);
}
function weighted(entries) {
  const total = entries.reduce((s, [, w]) => s + w, 0);
  let r = rand() * total;
  for (const [value, w] of entries) {
    r -= w;
    if (r <= 0) return value;
  }
  return entries[entries.length - 1][0];
}

// ---------- vocabularies ----------

const NAMES = {
  woman: [
    "Ava", "Maya", "Priya", "Sofia", "Zara", "Chloe", "Amara", "Leila", "Isabela", "Hana", "Nia", "Elena",
    "Aisha", "Mei", "Freya", "Camila", "Ines", "Tara", "Yuki", "Noor", "Olivia", "Ananya", "Lucia", "Sienna",
    "Imani", "Rosa", "Kiara", "Nadia", "Esme", "Jasmine", "Thandi", "Mila", "Ishita", "Valentina", "Ruby", "Sakura",
    "Farah", "Greta", "Daniela", "Aaliyah",
  ],
  man: [
    "Leo", "Arjun", "Mateo", "Kai", "Omar", "Ethan", "Rafael", "Jonah", "Kenji", "Malik", "Luca", "Dev",
    "Theo", "Santiago", "Idris", "Noah", "Hiro", "Elias", "Kabir", "Marcus", "Diego", "Finn", "Rohan", "Tariq",
    "Julian", "Andre", "Sami", "Felix", "Vikram", "Mason", "Takumi", "Gabriel", "Zane", "Nikhil", "Oscar", "Kwame",
    "Ravi", "Henrik", "Joaquin", "Darius",
  ],
  nonbinary: [
    "Rowan", "Sage", "River", "Quinn", "Ari", "Jules", "Remy", "Indigo", "Sky", "Ash", "Kit", "Phoenix",
    "Robin", "Emery", "Noa", "Rio", "Blair", "Lux", "Marlowe", "Avery",
  ],
};

const CITIES = [
  { city: "New York", lat: 40.7128, lng: -74.006 },
  { city: "Los Angeles", lat: 34.0522, lng: -118.2437 },
  { city: "Chicago", lat: 41.8781, lng: -87.6298 },
  { city: "Austin", lat: 30.2672, lng: -97.7431 },
  { city: "Miami", lat: 25.7617, lng: -80.1918 },
  { city: "San Francisco", lat: 37.7749, lng: -122.4194 },
  { city: "Toronto", lat: 43.6532, lng: -79.3832 },
  { city: "London", lat: 51.5074, lng: -0.1278 },
  { city: "Berlin", lat: 52.52, lng: 13.405 },
  { city: "Amsterdam", lat: 52.3676, lng: 4.9041 },
  { city: "Barcelona", lat: 41.3874, lng: 2.1686 },
  { city: "Sydney", lat: -33.8688, lng: 151.2093 },
  { city: "Mumbai", lat: 19.076, lng: 72.8777 },
  { city: "Bengaluru", lat: 12.9716, lng: 77.5946 },
  { city: "Delhi", lat: 28.6139, lng: 77.209 },
];

// DiceBear background gradients in the Ember palette (hex without #).
const BACKGROUNDS = [
  "ffb38a,ff7aa8", "ffd6a5,ff8fab", "c8b6ff,ffb3c6", "ff9e7a,ffcf99",
  "a0c4ff,ffc6ff", "fbc4ab,f08080", "ffafcc,ffc8dd", "f9c6c9,c9a7eb",
];

const dicebear = (seed) =>
  `https://api.dicebear.com/9.x/notionists/svg?seed=${encodeURIComponent(seed)}&backgroundType=gradientLinear&backgroundColor=${pick(BACKGROUNDS)}`;

// ---------- profile generation ----------

function genderFor(index) {
  const slot = index % 20; // 9 women, 9 men, 2 nonbinary per 20
  if (slot < 9) return "woman";
  if (slot < 18) return "man";
  return "nonbinary";
}

function interestedInFor(gender) {
  const all = ["woman", "man", "nonbinary"];
  switch (gender) {
    case "woman":
      return weighted([[["man"], 60], [["woman"], 15], [all, 25]]);
    case "man":
      return weighted([[["woman"], 65], [["man"], 15], [all, 20]]);
    default:
      return weighted([[all, 60], [["woman", "nonbinary"], 20], [["man", "nonbinary"], 20]]);
  }
}

function heightFor(gender) {
  if (chance(0.15)) return null;
  if (gender === "woman") return int(152, 180);
  if (gender === "man") return int(165, 195);
  return int(155, 188);
}

/** Same rule as firestore.rules ageOf() and the app's Ages.ageOn(): UTC, whole years. */
function ageOn({ birthYear, birthMonth, birthDay }, now) {
  const m = now.getUTCMonth() + 1;
  const d = now.getUTCDate();
  return now.getUTCFullYear() - birthYear - (m < birthMonth || (m === birthMonth && d < birthDay) ? 1 : 0);
}

function birthFor(age, now) {
  const birth = new Date(Date.UTC(now.getUTCFullYear() - age, now.getUTCMonth(), now.getUTCDate()));
  birth.setUTCDate(birth.getUTCDate() - int(0, 360));
  return { birthYear: birth.getUTCFullYear(), birthMonth: birth.getUTCMonth() + 1, birthDay: birth.getUTCDate() };
}

const round2 = (n) => Math.round(n * 100) / 100;

function buildProfile(index, now) {
  const gender = genderFor(index);
  const uid = `demo-${String(index + 1).padStart(3, "0")}`;
  const name = pick(NAMES[gender]);
  const birth = birthFor(int(21, 40), now);
  const place = pick(CITIES);
  const lat = round2(place.lat + (rand() - 0.5) * 0.1);
  const lng = round2(place.lng + (rand() - 0.5) * 0.1);
  const afterDark = chance(0.4);
  const lookingFor = afterDark
    ? weighted([["casual", 45], ["exploring", 40], ["dating", 15]])
    : weighted([["dating", 40], ["casual", 25], ["exploring", 20], ["friends", 15]]);
  const prompts = sample(PROMPTS, int(2, 3)).map((p) => ({
    id: p.id,
    question: p.question,
    answer: pick(ANSWERS[p.id] ?? ["Ask me in person."]),
  }));
  const illustrations = Array.from({ length: int(1, 3) }, (_, n) => ({ id: `${uid}-${n + 1}`, url: dicebear(`${uid}-${n}`), blurhash: null }));

  return {
    uid,
    gender,
    illustrations,
    profile: {
      uid,
      name,
      age: ageOn(birth, now),
      gender,
      interestedIn: interestedInFor(gender),
      bio: `${pick(BIOS.openers)} ${pick(BIOS.closers)}`,
      photos: [],
      prompts,
      interests: sample(CATALOG.interests, int(3, 6)),
      vibes: sample(CATALOG.vibes, int(2, 4)),
      lookingFor,
      heightCm: heightFor(gender),
      city: place.city,
      geohash: geohashForLocation([lat, lng], 5),
      lat,
      lng,
      afterDark,
      privateAlbumCount: 0,
      isDemo: true,
      onboarded: true,
    },
    lastActiveMinutesAgo: int(5, 4 * 24 * 60),
    account: { ...birth, termsVersion: "demo" },
    likesBack: chance(0.5),
    matchOnly: {
      boundaries: sample(CATALOG.boundaries, int(2, 3)),
      turnOns: sample(CATALOG.turnOns, int(2, 3)),
    },
  };
}

/**
 * A profile written for one specific portrait (shared/people.json): name, city and bio match the
 * person in the photo, so each demo profile is a single coherent person with one photo.
 */
function personPlan(person, index, now) {
  const uid = `demo-${person.file.replace(/\.jpg$/, "")}`;
  const birth = birthFor(person.age, now);
  const lat = round2(person.lat);
  const lng = round2(person.lng);
  const byId = Object.fromEntries(PROMPTS.map((p) => [p.id, p.question]));
  return {
    uid,
    gender: person.gender,
    portraitFile: person.file,
    illustrations: [],
    profile: {
      uid,
      name: person.name,
      age: ageOn(birth, now),
      gender: person.gender,
      interestedIn: person.interestedIn?.length ? person.interestedIn : interestedInFor(person.gender),
      bio: person.bio,
      photos: [],
      prompts: (person.prompts ?? []).filter((p) => byId[p.id]).map((p) => ({ id: p.id, question: byId[p.id], answer: p.answer })),
      interests: person.interests ?? [],
      vibes: person.vibes ?? [],
      lookingFor: person.lookingFor ?? "dating",
      heightCm: person.heightCm ?? heightFor(person.gender),
      city: person.city,
      geohash: geohashForLocation([lat, lng], 5),
      lat,
      lng,
      afterDark: !!person.afterDark,
      privateAlbumCount: 0,
      isDemo: true,
      onboarded: true,
    },
    lastActiveMinutesAgo: int(5, 3 * 24 * 60),
    account: { ...birth, termsVersion: "demo" },
    likesBack: chance(0.55),
    matchOnly: { boundaries: person.boundaries ?? [], turnOns: person.turnOns ?? [] },
  };
}

// ---------- portraits ----------

/** Portraits grouped by gender, from portraits/manifest.json. */
function loadPortraits() {
  const manifest = join(HERE, "portraits", "manifest.json");
  if (!existsSync(manifest)) return {};
  const byGender = {};
  for (const p of JSON.parse(readFileSync(manifest, "utf8"))) (byGender[p.gender] ??= []).push(p.file);
  return byGender;
}

const blurhashes = new Map();
async function portraitBlurhash(file) {
  if (!blurhashes.has(file)) {
    const { data, info } = await sharp(join(HERE, "portraits", file))
      .resize(32, 40)
      .ensureAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true });
    blurhashes.set(file, encodeBlurhash(new Uint8ClampedArray(data), info.width, info.height, 4, 5));
  }
  return blurhashes.get(file);
}

// ---------- Firebase ----------

/**
 * --cli-auth: reuse the Firebase CLI's login. firebase-admin's Firestore client only accepts a
 * service account or Application Default Credentials, so the CLI's refresh token is written to a
 * private temp file in the "authorized_user" ADC format, used, and deleted on exit.
 */
function cliCredentialsFile(projectId) {
  const store = join(homedir(), ".config", "configstore", "firebase-tools.json");
  if (!existsSync(store)) throw new Error("No Firebase CLI login found. Run `firebase login` first.");
  const refreshToken = JSON.parse(readFileSync(store, "utf8"))?.tokens?.refresh_token;
  if (!refreshToken) throw new Error("The Firebase CLI login has no refresh token. Run `firebase login --reauth`.");
  // The CLI's OAuth client (public, shipped inside firebase-tools).
  const globalRoot = execSync("npm root -g", { encoding: "utf8" }).trim();
  const api = JSON.parse(
    execSync(`node -e "const a=require(process.argv[1]);console.log(JSON.stringify({id:a.clientId(),secret:a.clientSecret()}))" "${join(globalRoot, "firebase-tools", "lib", "api.js")}"`, { encoding: "utf8" }),
  );
  const file = join(tmpdir(), `ember-seed-adc-${process.pid}.json`);
  writeFileSync(
    file,
    JSON.stringify({ type: "authorized_user", client_id: api.id, client_secret: api.secret, refresh_token: refreshToken, quota_project_id: projectId }),
    { mode: 0o600 },
  );
  const cleanup = () => rmSync(file, { force: true });
  process.on("exit", cleanup);
  return file;
}

async function initFirebase() {
  const { initializeApp, cert, applicationDefault } = await import("firebase-admin/app");
  const keyPath = join(HERE, "serviceAccount.json");
  let projectId = args.project;
  let credential;
  if (existsSync(keyPath)) {
    const key = JSON.parse(readFileSync(keyPath, "utf8"));
    credential = cert(key);
    projectId ??= key.project_id;
  } else {
    if (args["cli-auth"]) {
      if (!projectId) throw new Error("--cli-auth needs --project <id>.");
      process.env.GOOGLE_APPLICATION_CREDENTIALS = cliCredentialsFile(projectId);
    }
    credential = applicationDefault();
    projectId ??= process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT;
  }
  if (!projectId) throw new Error("Pass --project <id>, or put serviceAccount.json next to seed.mjs.");
  initializeApp({ credential, projectId });
  const { getFirestore, FieldValue, Timestamp } = await import("firebase-admin/firestore");
  return { db: getFirestore(), FieldValue, Timestamp, projectId };
}

async function wipe({ db }) {
  const demoUsers = await db.collection("users").where("isDemo", "==", true).get();
  console.log(`Deleting ${demoUsers.size} demo profiles...`);
  for (const user of demoUsers.docs) {
    await db.recursiveDelete(user.ref);
    await db.recursiveDelete(db.collection("albums").doc(user.id));
    await db.collection("demoMeta").doc(user.id).delete();
  }
  const demoMatches = await db.collection("matches").where("isDemo", "==", true).get();
  console.log(`Deleting ${demoMatches.size} matches with demo profiles...`);
  for (const match of demoMatches.docs) await db.recursiveDelete(match.ref);
  console.log("Wipe complete.");
}

// ---------- main ----------

async function main() {
  const now = new Date();
  const fb = DRY_RUN ? null : await initFirebase();
  if (args.wipe) {
    await wipe(fb);
    return;
  }

  // Build every profile up front, in order, so the output is identical across runs.
  const peopleFile = join(HERE, "shared", "people.json");
  const people = existsSync(peopleFile) ? JSON.parse(readFileSync(peopleFile, "utf8")) : null;
  const plans = people
    ? people.slice(0, args.count ? COUNT : people.length).map((person, i) => personPlan(person, i, now))
    : Array.from({ length: COUNT }, (_, i) => buildProfile(i, now));
  const portraits = loadPortraits();
  const used = {};
  for (const plan of plans) {
    const pool = portraits[plan.gender] ?? [];
    const main = [];
    if (plan.portraitFile) {
      main.push({ id: `${plan.uid}-0`, url: PORTRAIT_BASE + plan.portraitFile, blurhash: await portraitBlurhash(plan.portraitFile) });
    } else if (pool.length) {
      const i = (used[plan.gender] = (used[plan.gender] ?? -1) + 1);
      const file = pool[i % pool.length];
      main.push({ id: `${plan.uid}-0`, url: PORTRAIT_BASE + file, blurhash: await portraitBlurhash(file) });
    }
    plan.profile.photos = [...main, ...plan.illustrations];
  }

  if (DRY_RUN) {
    const cacheDir = join(HERE, ".cache");
    mkdirSync(cacheDir, { recursive: true });
    writeFileSync(join(cacheDir, "profiles.json"), JSON.stringify(plans.map((p) => p.profile), null, 2));
    console.log(`Dry run: ${COUNT} profiles written to ${join(cacheDir, "profiles.json")}`);
    return;
  }

  const { db, FieldValue, Timestamp } = fb;
  const writer = db.bulkWriter();
  for (const plan of plans) {
    const userRef = db.collection("users").doc(plan.uid);
    writer.set(userRef, {
      ...plan.profile,
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
      lastActiveAt: Timestamp.fromMillis(now.getTime() - plan.lastActiveMinutesAgo * 60_000),
    });
    writer.set(userRef.collection("private").doc("account"), {
      ...plan.account,
      termsAcceptedAt: FieldValue.serverTimestamp(),
    });
    writer.set(userRef.collection("matchOnly").doc("details"), plan.matchOnly);
    writer.set(db.collection("demoMeta").doc(plan.uid), { likesBack: plan.likesBack });
  }
  await writer.close();
  const verified = await db.collection("users").where("isDemo", "==", true).count().get();
  console.log(`Seeded ${plans.length} demo profiles into ${fb.projectId}; ${verified.data().count} demo profiles now in Firestore.`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
