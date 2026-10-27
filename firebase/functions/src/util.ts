import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { getRemoteConfig } from "firebase-admin/remote-config";
import { getStorage } from "firebase-admin/storage";
import { logger } from "firebase-functions";
import { defineBoolean } from "firebase-functions/params";
import { CallableOptions, CallableRequest, HttpsError } from "firebase-functions/v2/https";

if (getApps().length === 0) initializeApp();

export const db = getFirestore();
export const auth = getAuth();
export const messaging = getMessaging();
export const remoteConfig = getRemoteConfig();
export const bucket = () => getStorage().bucket();
export { FieldValue, Timestamp };

export const REGION = "us-central1";

/**
 * App Check enforcement for every callable. On by default. A sideloaded (non-Play) install can
 * fail Play Integrity, so the project owner may deploy with ENFORCE_APP_CHECK=false while
 * distributing through GitHub Releases. See docs/FIREBASE_SETUP.md.
 */
export const ENFORCE_APP_CHECK = defineBoolean("ENFORCE_APP_CHECK", {
  default: true,
  description: "Reject callable requests that do not carry a valid App Check token.",
});

export const callableOptions: CallableOptions = {
  region: REGION,
  enforceAppCheck: ENFORCE_APP_CHECK,
  memory: "256MiB",
  timeoutSeconds: 60,
};

export const GENDERS = ["woman", "man", "nonbinary"] as const;
export const LOOKING_FOR = ["casual", "dating", "friends", "exploring"] as const;

/** The deterministic id of the match between two users: the uids sorted and joined by "_". */
export function matchIdOf(a: string, b: string): string {
  return a < b ? `${a}_${b}` : `${b}_${a}`;
}

/** The signed-in caller's uid, or an `unauthenticated` error. */
export function requireAuth(request: CallableRequest<unknown>): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  return uid;
}

/** The caller's uid, provided they passed the age gate (custom claim `adult: true`). */
export function requireAdult(request: CallableRequest<unknown>): string {
  const uid = requireAuth(request);
  if (request.auth?.token?.adult !== true) {
    throw new HttpsError("permission-denied", "Complete the age check first.");
  }
  return uid;
}

/** Reads `data` as a plain object, rejecting anything else. */
export function inputObject(data: unknown): Record<string, unknown> {
  if (data === null || data === undefined) return {};
  if (typeof data !== "object" || Array.isArray(data)) {
    throw new HttpsError("invalid-argument", "Expected an object.");
  }
  return data as Record<string, unknown>;
}

export function requireUid(value: unknown, field: string): string {
  if (typeof value !== "string" || value.length === 0 || value.length > 128 || value.includes("/")) {
    throw new HttpsError("invalid-argument", `${field} must be a user id.`);
  }
  return value;
}

export function clampNumber(value: unknown, min: number, max: number, fallback: number): number {
  const n = typeof value === "number" && Number.isFinite(value) ? value : fallback;
  return Math.min(max, Math.max(min, n));
}

/** Converts Firestore timestamps to epoch millis so they survive callable JSON encoding. */
export function millis(value: unknown): number | null {
  if (value instanceof Timestamp) return value.toMillis();
  return null;
}

export type PushType = "match" | "message" | "album";

const CHANNELS: Record<PushType, string> = {
  match: "matches",
  message: "messages",
  album: "album",
};

/**
 * Sends a push to every device of `uid`. Demo profiles have no devices, and are skipped
 * explicitly as well. Tokens FCM reports as dead are deleted.
 */
export async function pushToUser(
  uid: string,
  push: { type: PushType; title: string; body: string; matchId?: string },
): Promise<void> {
  const userRef = db.collection("users").doc(uid);
  const [user, tokenSnap] = await Promise.all([userRef.get(), userRef.collection("tokens").get()]);
  if (!user.exists || user.get("isDemo") === true) return;
  const tokens = tokenSnap.docs.map((d) => d.id);
  if (tokens.length === 0) return;

  const data: Record<string, string> = { type: push.type, title: push.title, body: push.body };
  if (push.matchId) data.matchId = push.matchId;

  const response = await messaging.sendEachForMulticast({
    tokens,
    notification: { title: push.title, body: push.body },
    data,
    android: {
      priority: "high",
      notification: { channelId: CHANNELS[push.type], tag: push.matchId ?? push.type },
    },
  });

  const dead = response.responses
    .map((r, i) => ({ r, token: tokens[i] }))
    .filter(({ r }) => {
      const code = r.error?.code;
      return code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token" ||
        code === "messaging/invalid-argument";
    });
  if (dead.length > 0) {
    const batch = db.batch();
    dead.forEach(({ token }) => batch.delete(userRef.collection("tokens").doc(token)));
    await batch.commit();
    logger.info("Removed dead FCM tokens", { uid, count: dead.length });
  }
}

/** Deletes every Storage object under `prefix`. Missing prefixes are fine. */
export async function deleteStoragePrefix(prefix: string): Promise<void> {
  try {
    await bucket().deleteFiles({ prefix, force: true });
  } catch (error) {
    logger.warn("Storage prefix delete failed", { prefix, error: String(error) });
  }
}

let rcCache: { at: number; params: Record<string, string> } | null = null;
const RC_TTL_MS = 5 * 60 * 1000;

/** Reads the published Remote Config defaults, cached for five minutes per instance. */
export async function remoteConfigValues(): Promise<Record<string, string>> {
  if (rcCache && Date.now() - rcCache.at < RC_TTL_MS) return rcCache.params;
  try {
    const template = await remoteConfig.getTemplate();
    const params: Record<string, string> = {};
    for (const [key, param] of Object.entries(template.parameters)) {
      const def = param.defaultValue;
      if (def && "value" in def) params[key] = def.value;
    }
    rcCache = { at: Date.now(), params };
    return params;
  } catch (error) {
    logger.warn("Remote Config unavailable, using defaults", { error: String(error) });
    return rcCache?.params ?? {};
  }
}

export async function hideDemoProfiles(): Promise<boolean> {
  return (await remoteConfigValues()).hide_demo_profiles === "true";
}

export function invalidateRemoteConfigCache(): void {
  rcCache = null;
}
