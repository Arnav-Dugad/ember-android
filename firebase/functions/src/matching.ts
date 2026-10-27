import type { DocumentData } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import {
  FieldValue,
  REGION,
  Timestamp,
  callableOptions,
  db,
  inputObject,
  matchIdOf,
  pushToUser,
  requireAdult,
  requireUid,
} from "./util.js";

const LIKES = new Set(["like", "super"]);
const REWIND_WINDOW_MS = 10 * 60 * 1000;

function snapshotOf(data: DocumentData | undefined) {
  const photos = Array.isArray(data?.photos) ? data.photos : [];
  return {
    name: data?.name ?? "",
    photoUrl: photos[0]?.url ?? null,
    blurhash: photos[0]?.blurhash ?? null,
    isDemo: data?.isDemo === true,
  };
}

/**
 * Turns a mutual like into a match. The match id is deterministic and the write is a
 * transaction that checks for an existing match, so two near-simultaneous likes create exactly
 * one match and one pair of notifications.
 */
export const onSwipeCreated = onDocumentCreated(
  { document: "users/{uid}/swipes/{targetId}", region: REGION },
  async (event) => {
    const { uid, targetId } = event.params;
    const direction = event.data?.get("direction");
    if (!LIKES.has(direction) || uid === targetId) return;

    const [me, target, iBlocked, theyBlocked] = await Promise.all([
      db.doc(`users/${uid}`).get(),
      db.doc(`users/${targetId}`).get(),
      db.doc(`users/${uid}/blocks/${targetId}`).get(),
      db.doc(`users/${targetId}/blocks/${uid}`).get(),
    ]);
    if (!me.exists || !target.exists || iBlocked.exists || theyBlocked.exists) return;

    let reverseDirection: string | undefined;
    if (target.get("isDemo") === true) {
      // Demo profiles exist so people can test matching. About half of them like back; none of
      // them ever writes a message.
      const account = await db.doc(`users/${targetId}/private/account`).get();
      if (account.get("demoLikesBack") !== true) return;
      reverseDirection = "like";
    } else {
      const reverse = await db.doc(`users/${targetId}/swipes/${uid}`).get();
      reverseDirection = reverse.get("direction");
      if (!LIKES.has(reverseDirection ?? "")) return;
    }

    const matchId = matchIdOf(uid, targetId);
    const matchRef = db.collection("matches").doc(matchId);
    const created = await db.runTransaction(async (tx) => {
      const existing = await tx.get(matchRef);
      if (existing.exists) return false;
      tx.create(matchRef, {
        users: [uid, targetId].sort(),
        profiles: {
          [uid]: snapshotOf(me.data()),
          [targetId]: snapshotOf(target.data()),
        },
        createdAt: FieldValue.serverTimestamp(),
        superLike: direction === "super" || reverseDirection === "super",
        isDemo: me.get("isDemo") === true || target.get("isDemo") === true,
        lastMessage: null,
        lastRead: {},
      });
      return true;
    });
    if (!created) return;

    logger.info("Match created", { matchId });
    await Promise.all([
      pushToUser(uid, {
        type: "match",
        title: "It's a match!",
        body: `You and ${target.get("name") ?? "someone"} liked each other.`,
        matchId,
      }),
      pushToUser(targetId, {
        type: "match",
        title: "It's a match!",
        body: `You and ${me.get("name") ?? "someone"} liked each other.`,
        matchId,
      }),
    ]);
  },
);

/**
 * Undoes the caller's most recent swipe, if it is no older than ten minutes and did not produce
 * a match. Error messages: `not-found`, `not-latest`, `expired`, `matched`.
 */
export const rewindSwipe = onCall(callableOptions, async (request) => {
  const uid = requireAdult(request);
  const targetId = requireUid(inputObject(request.data).targetId, "targetId");

  const swipes = db.collection(`users/${uid}/swipes`);
  const [swipe, latest, match] = await Promise.all([
    swipes.doc(targetId).get(),
    swipes.orderBy("createdAt", "desc").limit(1).get(),
    db.doc(`matches/${matchIdOf(uid, targetId)}`).get(),
  ]);
  if (!swipe.exists) throw new HttpsError("not-found", "not-found");
  if (latest.docs[0]?.id !== targetId) throw new HttpsError("failed-precondition", "not-latest");
  const createdAt = swipe.get("createdAt") as Timestamp | undefined;
  if (!createdAt || Date.now() - createdAt.toMillis() > REWIND_WINDOW_MS) {
    throw new HttpsError("failed-precondition", "expired");
  }
  if (match.exists) throw new HttpsError("failed-precondition", "matched");

  await swipe.ref.delete();
  return { ok: true };
});
