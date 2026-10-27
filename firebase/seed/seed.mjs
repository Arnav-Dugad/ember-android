#!/usr/bin/env node
// Seeds Ember with illustrated demo profiles.
//
// Every profile is fictional: names are drawn from lists, and every "photo" is an illustrated
// avatar generated locally by DiceBear (CC0 styles "lorelei" and "notionists") on an ember and
// plum gradient. No real person's photo is used, nothing is scraped, nothing is explicit.
//
// Every profile carries `isDemo: true`. The app shows a "Demo" badge on them, and no code path
// ever sends a message as a demo profile.
//
//   node seed.mjs --dry-run --count 6         render into .cache/, touch nothing remote
//   node seed.mjs --project my-proj           seed 150 profiles (default)
//   node seed.mjs --wipe --project my-proj    delete all demo profiles, their matches and files
//
// Credentials: serviceAccount.json next to this file (git-ignored), or
// GOOGLE_APPLICATION_CREDENTIALS. Bucket: --bucket, else <project>.firebasestorage.app.
import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { createAvatar } from "@dicebear/core";
import { lorelei, notionists } from "@dicebear/collection";
import { encode as encodeBlurhash } from "blurhash";
import { geohashForLocation } from "geofire-common";
import sharp from "sharp";

const HERE = dirname(fileURLToPath(import.meta.url));
const readJson = (name) => JSON.parse(readFileSync(join(HERE, name), "utf8"));

const { values: args } = parseArgs({
  options: {
    count: { type: "string", default: "150" },
    "dry-run": { type: "boolean", default: false },
    wipe: { type: "boolean", default: false },
    project: { type: "string" },
    bucket: { type: "string" },
    concurrency: { type: "string", default: "6" },
  },
});

const COUNT = Math.max(1, Math.min(1000, Number(args.count) || 150));
const DRY_RUN = args["dry-run"];
const WIDTH = 768;
const HEIGHT = 960;

const PROMPTS = readJson("prompts.json");
const ANSWERS = readJson("shared/answers.json");
const BIOS = readJson("shared/bios.json");
const BIO_OPENERS = BIOS.openers;
const BIO_CLOSERS = BIOS.closers;
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


// Soft gradients in the Ember palette: plum and midnight into ember orange and rose.
const PALETTES = [
  ["#2a0f2e", "#ff6a3d", "#ffb38a"],
  ["#3b0d3a", "#ff4f7b", "#ffc2a8"],
  ["#1f1230", "#f7a072", "#ffd9b8"],
  ["#4a1942", "#ff8a5c", "#ffe0c7"],
  ["#2b1b3d", "#e85d75", "#ffb1a3"],
  ["#1d0f24", "#c94b7c", "#f9a8c0"],
  ["#331433", "#ff7f50", "#ffd1a1"],
  ["#22102b", "#ff5e62", "#ff9966"],
];

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

const pad = (n) => String(n).padStart(2, "0");

/** Same rule as the age gate: whole years, UTC, birthday not yet reached counts as younger. */
function ageOn(birthDate, now) {
  const [y, m, d] = birthDate.split("-").map(Number);
  const ny = now.getUTCFullYear();
  const nm = now.getUTCMonth() + 1;
  const nd = now.getUTCDate();
  return ny - y - (nm > m || (nm === m && nd >= d) ? 0 : 1);
}

function birthDateFor(age, now) {
  const birth = new Date(Date.UTC(now.getUTCFullYear() - age, now.getUTCMonth(), now.getUTCDate()));
  birth.setUTCDate(birth.getUTCDate() - int(0, 360));
  return `${birth.getUTCFullYear()}-${pad(birth.getUTCMonth() + 1)}-${pad(birth.getUTCDate())}`;
}

const round2 = (n) => Math.round(n * 100) / 100;

function buildProfile(index, now) {
  const gender = genderFor(index);
  const uid = `demo-${String(index + 1).padStart(3, "0")}`;
  const name = pick(NAMES[gender]);
  const targetAge = int(21, 40);
  const birthDate = birthDateFor(targetAge, now);
  const age = ageOn(birthDate, now);
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

  const bio = `${pick(BIO_OPENERS)} ${pick(BIO_CLOSERS)}`;

  return {
    uid,
    gender,
    photoCount: int(2, 4),
    profile: {
      uid,
      name,
      age,
      gender,
      interestedIn: interestedInFor(gender),
      bio,
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
    lastActiveMinutesAgo: int(5, 3 * 24 * 60),
    account: {
      birthDate,
      termsVersion: "demo",
      demoLikesBack: chance(0.5),
    },
    matchOnly: {
      boundaries: sample(CATALOG.boundaries, int(2, 3)),
      turnOns: sample(CATALOG.turnOns, int(2, 3)),
    },
  };
}

// ---------- illustration ----------

function avatarSvg(seed, gender, styleIndex) {
  const style = styleIndex % 2 === 0 ? lorelei : notionists;
  const beard = gender === "man" ? 45 : 0;
  const options = {
    seed,
    backgroundColor: ["transparent"],
    ...(style === lorelei ? { beardProbability: beard, frecklesProbability: 25 } : { beardProbability: beard }),
  };
  return createAvatar(style, options).toString();
}

/** A portrait card: gradient backdrop, soft glow, and the avatar framed in the lower two thirds. */
function portraitSvg(seed, gender, styleIndex, palette, rng) {
  const [deep, ember, glow] = palette;
  const avatar = avatarSvg(seed, gender, styleIndex)
    .replace(/<svg /, `<svg x="${(WIDTH - 700) / 2}" y="${HEIGHT - 700}" width="700" height="700" `);
  const between = (min, max) => min + Math.floor(rng() * (max - min + 1));
  const bokeh = Array.from({ length: 7 }, () => {
    const r = between(30, 110);
    return `<circle cx="${between(0, WIDTH)}" cy="${between(0, HEIGHT / 2)}" r="${r}" fill="${glow}" opacity="${(0.05 + rng() * 0.12).toFixed(2)}"/>`;
  }).join("");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${WIDTH}" height="${HEIGHT}" viewBox="0 0 ${WIDTH} ${HEIGHT}">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0.4" y2="1">
      <stop offset="0" stop-color="${deep}"/>
      <stop offset="0.75" stop-color="${ember}"/>
      <stop offset="1" stop-color="${glow}"/>
    </linearGradient>
    <radialGradient id="halo" cx="0.5" cy="0.62" r="0.5">
      <stop offset="0" stop-color="#ffffff" stop-opacity="0.35"/>
      <stop offset="1" stop-color="#ffffff" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="${WIDTH}" height="${HEIGHT}" fill="url(#bg)"/>
  ${bokeh}
  <ellipse cx="${WIDTH / 2}" cy="${HEIGHT * 0.62}" rx="${WIDTH * 0.48}" ry="${HEIGHT * 0.4}" fill="url(#halo)"/>
  ${avatar}
</svg>`;
}

/** A stable 32-bit hash, so each photo gets its own RNG and renders identically every run. */
function hash(text) {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 16777619);
  return h >>> 0;
}

/**
 * AI-generated portraits of people who don't exist (portraits/manifest.json). Each profile's
 * first photo is a portrait matching its gender, cycling so the set is spread evenly; the rest
 * stay illustrated.
 */
function loadPortraits() {
  const manifest = join(HERE, "portraits", "manifest.json");
  if (!existsSync(manifest)) return {};
  const byGender = {};
  for (const p of JSON.parse(readFileSync(manifest, "utf8"))) (byGender[p.gender] ??= []).push(p.file);
  return byGender;
}

async function portraitPhoto(file) {
  const jpg = readFileSync(join(HERE, "portraits", file));
  const { data, info } = await sharp(jpg).resize(32, 40).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  return { bytes: jpg, blurhash: encodeBlurhash(new Uint8ClampedArray(data), info.width, info.height, 4, 5) };
}

async function renderPhoto(uid, gender, n) {
  const rng = mulberry32(hash(`${uid}/${n}`));
  const palette = PALETTES[Math.floor(rng() * PALETTES.length)];
  const svg = portraitSvg(`${uid}-${n}`, gender, n + Number(uid.slice(-1)), palette, rng);
  const png = await sharp(Buffer.from(svg)).png({ compressionLevel: 9 }).toBuffer();
  const { data, info } = await sharp(png).resize(32, 40).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  const blurhash = encodeBlurhash(new Uint8ClampedArray(data), info.width, info.height, 4, 5);
  return { png, blurhash };
}

// ---------- Firebase ----------

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
    credential = applicationDefault();
    projectId ??= process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT;
  }
  if (!projectId) throw new Error("Pass --project <id>, or put serviceAccount.json next to seed.mjs.");
  const storageBucket = args.bucket ?? `${projectId}.firebasestorage.app`;
  initializeApp({ credential, projectId, storageBucket });

  const { getFirestore, FieldValue, Timestamp } = await import("firebase-admin/firestore");
  const { getStorage } = await import("firebase-admin/storage");
  return { db: getFirestore(), bucket: getStorage().bucket(), FieldValue, Timestamp, projectId, storageBucket };
}

async function uploadPhoto(bucket, path, png, contentType = "image/png") {
  const token = randomUUID();
  await bucket.file(path).save(png, {
    resumable: false,
    contentType,
    metadata: {
      cacheControl: "public, max-age=31536000",
      metadata: { firebaseStorageDownloadTokens: token },
    },
  });
  return `https://firebasestorage.googleapis.com/v0/b/${bucket.name}/o/${encodeURIComponent(path)}?alt=media&token=${token}`;
}

async function wipe(fb) {
  const { db, bucket } = fb;
  const demoUsers = await db.collection("users").where("isDemo", "==", true).get();
  console.log(`Deleting ${demoUsers.size} demo profiles...`);
  for (const user of demoUsers.docs) {
    await db.recursiveDelete(user.ref);
    await db.recursiveDelete(db.collection("albums").doc(user.id));
  }
  const demoMatches = await db.collection("matches").where("isDemo", "==", true).get();
  console.log(`Deleting ${demoMatches.size} matches with demo profiles...`);
  for (const match of demoMatches.docs) {
    await db.recursiveDelete(match.ref);
    await bucket.deleteFiles({ prefix: `chats/${match.id}/`, force: true }).catch(() => {});
  }
  await bucket.deleteFiles({ prefix: "demo/", force: true }).catch(() => {});
  console.log("Wipe complete.");
}

async function runPool(items, size, worker) {
  let next = 0;
  const runners = Array.from({ length: Math.min(size, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      await worker(items[i], i);
    }
  });
  await Promise.all(runners);
}

// ---------- main ----------

async function main() {
  const now = new Date();
  const fb = DRY_RUN ? null : await initFirebase();

  if (args.wipe) {
    await wipe(fb);
    return;
  }

  // Build every profile (and every photo's random choices) up front, in order, so the output is
  // identical across runs regardless of upload timing.
  const plans = Array.from({ length: COUNT }, (_, i) => buildProfile(i, now));
  const portraits = loadPortraits();
  const used = {};
  const renders = [];
  for (const plan of plans) {
    const pool = portraits[plan.gender] ?? [];
    if (pool.length) {
      const i = (used[plan.gender] = (used[plan.gender] ?? -1) + 1);
      plan.portrait = pool[i % pool.length];
    }
    for (let n = 0; n < plan.photoCount; n++) renders.push({ plan, n });
  }

  const cacheDir = join(HERE, ".cache");
  if (DRY_RUN) mkdirSync(cacheDir, { recursive: true });
  console.log(`${DRY_RUN ? "Rendering" : "Seeding"} ${COUNT} demo profiles (${renders.length} illustrations)...`);

  await runPool(renders, Number(args.concurrency) || 6, async ({ plan, n }) => {
    const usePortrait = n === 0 && plan.portrait;
    const { bytes, blurhash } = usePortrait
      ? await portraitPhoto(plan.portrait)
      : await renderPhoto(plan.uid, plan.gender, n).then((r) => ({ bytes: r.png, blurhash: r.blurhash }));
    const ext = usePortrait ? "jpg" : "png";
    const path = `demo/${plan.uid}/${n}.${ext}`;
    let url = `file://${join(cacheDir, `${plan.uid}-${n}.${ext}`).split(sep).join("/")}`;
    if (DRY_RUN) {
      writeFileSync(join(cacheDir, `${plan.uid}-${n}.${ext}`), bytes);
    } else {
      url = await uploadPhoto(fb.bucket, path, bytes, usePortrait ? "image/jpeg" : "image/png");
    }
    plan.profile.photos[n] = { id: `${plan.uid}-${n}`, url, path, blurhash };
  });

  if (DRY_RUN) {
    writeFileSync(join(cacheDir, "profiles.json"), JSON.stringify(plans.map((p) => p.profile), null, 2));
    console.log(`Dry run done. Inspect ${cacheDir}`);
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
      ageVerifiedAt: FieldValue.serverTimestamp(),
    });
    writer.set(userRef.collection("matchOnly").doc("details"), plan.matchOnly);
  }
  await writer.close();
  console.log(`Seeded ${plans.length} demo profiles into ${fb.projectId} (bucket ${fb.storageBucket}).`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
