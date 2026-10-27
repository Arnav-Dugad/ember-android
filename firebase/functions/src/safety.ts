import { HttpsError, onCall } from "firebase-functions/v2/https";
import { deleteMatch, revokeAlbumAccessBetween } from "./account.js";
import { FieldValue, Timestamp, callableOptions, db, inputObject, matchIdOf, requireAdult, requireUid } from "./util.js";

export const REPORT_REASONS = [
  "fake_profile",
  "inappropriate_photos",
  "harassment",
  "underage",
  "spam_scam",
  "offensive_messages",
  "safety_concern",
  "other",
] as const;

const MAX_REPORTS_PER_DAY = 20;

/** Unmatches two users if they are matched; a no-op otherwise. */
async function unmatchPair(a: string, b: string): Promise<void> {
  const matchId = matchIdOf(a, b);
  const match = await db.doc(`matches/${matchId}`).get();
  if (match.exists) {
    await deleteMatch(matchId, (match.get("users") as string[]) ?? [a, b]);
  } else {
    await revokeAlbumAccessBetween(a, b);
  }
}

/**
 * Blocks in both directions. Each side gets a block doc, which is what the rules and the
 * discovery deck read; `initiatedByMe` lets the app list only the people *you* blocked.
 */
async function block(uid: string, targetId: string): Promise<void> {
  const now = FieldValue.serverTimestamp();
  const batch = db.batch();
  batch.set(db.doc(`users/${uid}/blocks/${targetId}`), { createdAt: now, initiatedByMe: true });
  batch.set(db.doc(`users/${targetId}/blocks/${uid}`), { createdAt: now, initiatedByMe: false }, { merge: true });
  await batch.commit();
  await unmatchPair(uid, targetId);
}

export const unmatch = onCall(callableOptions, async (request) => {
  const uid = requireAdult(request);
  const matchId = inputObject(request.data).matchId;
  if (typeof matchId !== "string" || !matchId.includes("_") || matchId.includes("/")) {
    throw new HttpsError("invalid-argument", "matchId is required.");
  }
  const match = await db.doc(`matches/${matchId}`).get();
  if (!match.exists) return { ok: true };
  const users = (match.get("users") as string[]) ?? [];
  if (!users.includes(uid)) throw new HttpsError("permission-denied", "Not your match.");
  await deleteMatch(matchId, users);
  return { ok: true };
});

export const blockUser = onCall(callableOptions, async (request) => {
  const uid = requireAdult(request);
  const targetId = requireUid(inputObject(request.data).targetId, "targetId");
  if (targetId === uid) throw new HttpsError("invalid-argument", "You cannot block yourself.");
  await block(uid, targetId);
  return { ok: true };
});

export const reportUser = onCall(callableOptions, async (request) => {
  const uid = requireAdult(request);
  const input = inputObject(request.data);
  const targetId = requireUid(input.targetId, "targetId");
  if (targetId === uid) throw new HttpsError("invalid-argument", "You cannot report yourself.");

  const reason = input.reason;
  if (typeof reason !== "string" || !(REPORT_REASONS as readonly string[]).includes(reason)) {
    throw new HttpsError("invalid-argument", "Pick a reason.");
  }
  const details = typeof input.details === "string" ? input.details.trim().slice(0, 1000) : "";
  const matchId = typeof input.matchId === "string" && !input.matchId.includes("/") ? input.matchId : null;

  const since = Timestamp.fromMillis(Date.now() - 24 * 60 * 60 * 1000);
  const recent = await db.collection("reports")
    .where("reporterId", "==", uid)
    .where("createdAt", ">", since)
    .count()
    .get();
  if (recent.data().count >= MAX_REPORTS_PER_DAY) {
    throw new HttpsError("resource-exhausted", "Too many reports today. Our team is reviewing the ones you sent.");
  }

  await db.collection("reports").add({
    reporterId: uid,
    targetId,
    reason,
    details,
    matchId,
    createdAt: FieldValue.serverTimestamp(),
    status: "open",
  });
  if (input.block === true) await block(uid, targetId);
  return { ok: true };
});
